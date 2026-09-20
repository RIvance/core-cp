package cp.language

import cp.fiobs.runtime.Value
import cp.fitrie.evaluation.Evaluation
import cp.language.compilation.{CpFiTrieCompiler, CpSourceFile, SourcePath}
import cp.language.elaboration.CpElaborationError
import cp.language.evaluation.CpEvaluator
import cp.language.typing.Type
import cp.naming.Namespace
import cp.primitive.PrimitiveValue
import cp.util.Result

/** Algebraic laws observed through both public execution paths. */
class AlgebraSuite extends munit.FunSuite {
  private val namespace = Namespace("Algebra")

  private def sourceFile(source: String): CpSourceFile = CpSourceFile(SourcePath("Algebra.cp"), source)

  private def assertAnswer(source: String): Unit = {
    val program = Cp.compileModules(List(sourceFile(source))) match {
      case Result.Ok(program) => program
      case Result.Err(error) => fail(s"unexpected compilation failure: $error")
    }
    val expected = PrimitiveValue.Integer(42)
    assertEquals(CpEvaluator.evaluate(program, namespace), Result.Ok(Value.Primitive(expected)))
    val target = CpFiTrieCompiler.compile(program, namespace) match {
      case Result.Ok(target) => target
      case Result.Err(error) => fail(s"unexpected FiTrie compilation failure: $error")
    }
    assertEquals(
      Evaluation.observeTermination(target.entry, expected.primitiveType, target.globalEnvironment),
      Result.Ok(expected)
    )
  }

  private def elaborationError(source: String): CpElaborationError = Cp.compile(sourceFile(source)) match {
    case Result.Err(CpCompilationError.Elaboration(_, error)) => error.underlying
    case other => fail(s"expected an elaboration error, received: $other")
  }

  test("top is a two-sided merge identity under primitive observation") {
    List("42 ,, top", "top ,, 42", "(42 ,, top) ,, top", "top ,, (top ,, 42)").foreach { expression =>
      assertAnswer(s"def main: Int = $expression;")
    }
  }

  test("merge synthesis retains the intersection in the E-Merge conclusion") {
    val source = "def main = 42 ,, top;"
    Cp.compile(sourceFile(source)) match {
      case Result.Ok(module) =>
        assertEquals(
          module.elaboratedModule.header.termSignatures(namespace.identifier("main")),
          Type.Intersection(Type.Integer, Type.Top)
        )
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("entering a type binder preserves the synthesized interface of a local merge") {
    val source = "def main = let value = 42 ,, top in Λ A . value;"
    Cp.compile(sourceFile(source)) match {
      case Result.Ok(module) =>
        assertEquals(
          module.elaboratedModule.header.termSignatures(namespace.identifier("main")),
          Type.ForAll(Type.Top, Type.Intersection(Type.Integer, Type.Top))
        )
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("selecting a merge contributor does not demand the discarded computation") {
    List(
      "42 ,, (1 / 0 == 0)",
      "(1 / 0 == 0) ,, 42",
      "(42 ,, top) ,, (1 / 0 == 0)",
      "(1 / 0 == 0) ,, (top ,, 42)"
    ).foreach { expression =>
      assertAnswer(s"def main: Int = $expression;")
    }
  }

  test("record projection does not demand an unrelated merged computation") {
    List(
      "{ answer = 42 } ,, (if 1 / 0 == 0 then { flag = true } else { flag = false })",
      "(if 1 / 0 == 0 then { flag = true } else { flag = false }) ,, { answer = 42 }"
    ).foreach { expression =>
      assertAnswer(s"def main: Int = ($expression).answer;")
    }
  }

  test("intersection with top preserves observations beneath every positive constructor") {
    List("Int & Top", "Top & Int", "(Top & Int) & Top").foreach { resultType =>
      List(
        s"def value: $resultType = 42; def main: Int = value;",
        s"def function(value: Int): $resultType = value; def main: Int = function(42);",
        s"def record: { value: $resultType } = { value = 42 }; def main: Int = record.value;",
        s"def universal[A]: $resultType = 42; def main: Int = universal[Bool];"
      ).foreach(assertAnswer)
    }
  }

  test("omitted parents and empty trait parents provide the empty inherited interface") {
    List("", "inherits empty", "inherits (trait => {})", "inherits (empty ,, top)", "inherits (top ,, empty)")
      .foreach { parent =>
        List(s"def component = trait $parent =>", s"impl component from Top $parent =").foreach { declaration =>
          assertAnswer(s"""
            def empty = trait => {};
            $declaration { answer = 42; inherited = super };
            def main: Int = (new component).answer;
          """)
        }
      }
  }

  test("every explicit parent must provide a trait interface") {
    List(
      "top" -> Type.Top,
      "(top)" -> Type.Top,
      "(top : Top)" -> Type.Top,
      "empty" -> Type.Top,
      "(let parent = top in parent)" -> Type.Top,
      "(top ,, top)" -> Type.Intersection(Type.Top, Type.Top)
    ).foreach { case (parent, expectedType) =>
      List(s"def component = trait inherits $parent =>", s"impl component from Top inherits $parent =")
        .foreach { declaration =>
          elaborationError(s"def empty = top; $declaration { value = 42 };") match {
            case CpElaborationError.ExpectedTrait(_, actualType) => assertEquals(actualType, expectedType)
            case other => fail(s"expected a trait interface error, received: $other")
          }
        }
    }
  }

  test("an invalid explicit parent is reported at the parent expression") {
    val source = "def component = trait inherits top => { value = 42 };"
    Cp.compile(sourceFile(source)) match {
      case Result.Err(CpCompilationError.Elaboration(_, error)) =>
        error.underlying match {
          case CpElaborationError.ExpectedTrait(_, Type.Top) => ()
          case other => fail(s"expected an exact-top trait error, received: $other")
        }
        val span = error.location.getOrElse(fail("expected the invalid parent's source span"))
        assertEquals(source.substring(span.startOffset, span.endOffset), "top")
      case other => fail(s"expected an elaboration error, received: $other")
    }
  }

  test("an empty trait parent retains its self requirement") {
    val source = """
      def empty = trait [self: { required: Int }] => {};
      def component = trait inherits empty => { value = 42 };
    """
    assertEquals(
      elaborationError(source),
      CpElaborationError.TraitRequirementNotSatisfied(Type.Top, Type.Record("required", Type.Integer))
    )
  }

  test("an omitted parent binds a fresh empty super in nested traits") {
    val source = """
      def parent = trait => { value = 42 };
      def child = trait inherits parent => {
        nested = trait => { value = super.value }
      };
      def main = (new (new child).nested).value;
    """
    elaborationError(source) match {
      case CpElaborationError.MissingRecordField(_, "value", Type.Top) => ()
      case other => fail(s"expected a missing field on the nested trait's super, received: $other")
    }
  }

  test("empty traits are two-sided composition identities") {
    List("empty ,, component", "component ,, empty", "empty ,, (component ,, empty)").foreach { composition =>
      assertAnswer(s"""
        def empty = trait => {};
        def component = trait => { answer = 42 };
        def main: Int = (new ($composition)).answer;
      """)
    }
  }

  test("neutral intersections preserve trait views through aliases and polymorphic instantiation") {
    List("Trait[Top, { answer: Int }] & Top", "Top & Trait[Top, { answer: Int }]").foreach { interfaceType =>
      assertAnswer(s"""
        type Component = $interfaceType;
        def component: Component = trait => { answer = 42 };
        def identity[A](value: A): A & Top = value;
        def main: Int = (new identity[Component](component)).answer;
      """)
    }
  }

  test("merging a trait with exact top retains its callable interface") {
    List("component ,, top", "top ,, component", "top ,, (component ,, top)").foreach { expression =>
      assertAnswer(s"""
        def component = trait => { answer = 42 };
        def main: Int = (new ($expression)).answer;
      """)
    }
  }

  test("trait intersections obtained by projection support each trait elimination") {
    List(
      "new both.make",
      "both.make ^ top",
      "new (trait inherits both.make => {})",
      "new (both.make ,, (trait => {}))"
    ).foreach { expression =>
      assertAnswer(s"""
        def left = { make = trait => { x = 20 } };
        def right = { make = trait => { y = 22 } };
        def both = left ,, right;
        def instance = $expression;
        def main: Int = instance.x + instance.y;
      """)
    }
  }

  test("empty record interfaces remain openable through neutral intersections") {
    List("top", "top ,, top", "new (trait => {})").foreach { receiver =>
      assertAnswer(s"def main: Int = open ($receiver) in 42;")
    }
  }

  test("a bottom disjointness bound admits silent interfaces without erasing their constructors") {
    List(
      "Top" -> "top",
      "Top & Top" -> "(top ,, top)",
      "Int -> Top" -> "(λ(value: Int) . top)",
      "{ field: Top }" -> "{ field = top }",
      "forall [type B] -> Top" -> "(Λ B . top)"
    ).foreach { case (argumentType, argument) =>
      assertAnswer(s"""
        def combine[A * Bottom](value: A) = value ,, 42;
        def main: Int = combine[$argumentType]($argument);
      """)
    }
    assertEquals(
      elaborationError("def combine[A * Bottom](value: A) = value ,, 42; def main = combine[Int](42);"),
      CpElaborationError.TypeArgumentViolatesBound(Type.Integer, Type.Bottom)
    )
  }

  test("exact top has no callable or trait interface") {
    elaborationError("def main = top(42);") match {
      case CpElaborationError.ExpectedFunction(_, Type.Top) => ()
      case other => fail(s"expected an exact-top application error, received: $other")
    }
    elaborationError("def main = top[Int];") match {
      case CpElaborationError.ExpectedUniversal(_, Type.Top) => ()
      case other => fail(s"expected an exact-top type application error, received: $other")
    }
    List(
      "def main = new top;",
      "def empty = top; def main = trait inherits empty => {};"
    ).foreach { source =>
      elaborationError(source) match {
        case CpElaborationError.ExpectedTrait(_, Type.Top) => ()
        case other => fail(s"expected an exact-top trait error, received: $other")
      }
    }
  }
}
