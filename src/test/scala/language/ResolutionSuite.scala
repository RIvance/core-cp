package cp.language

import cp.fiobs.runtime.Value
import cp.fitrie.evaluation.Evaluation
import cp.language.compilation.{CpFiTrieCompiler, CpSourceFile, SourcePath}
import cp.language.elaboration.{CpElaborationError, NameKind, NameResolutionError, TypeExpansionError}
import cp.language.evaluation.CpEvaluator
import cp.language.parser.ParsingError
import cp.naming.{NameReference, Namespace}
import cp.primitive.PrimitiveValue
import cp.util.Result

class ResolutionSuite extends munit.FunSuite {
  private val namespace = Namespace("Test")

  private def sourceFile(source: String): CpSourceFile = CpSourceFile(SourcePath("Test.cp"), source)

  /** Both consumers use the same checked CP definitions. */
  private def assertValue(source: String, expected: PrimitiveValue): Unit = {
    val program = Cp.compileModules(List(sourceFile(source))) match {
      case Result.Ok(program) => program
      case Result.Err(error) => fail(s"unexpected compilation failure: $error")
    }
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

  test("shadowing a type binder preserves types of existing term bindings") {
    List("A", "B").foreach { inner =>
      assertValue(s"""
        def keep[A](value: A) = Λ $inner . value;
        def main: Int = keep[Int](42)[Bool];
      """, PrimitiveValue.Integer(42))
    }
  }

  test("nested type binders preserve types in opened fields") {
    assertValue("""
      def keep[A](record: { value: A }) = open record in Λ A . value;
      def main: Int = keep[Int]({ value = 42 })[Bool];
    """, PrimitiveValue.Integer(42))
  }

  test("lazy local values preserve enclosing term and type scopes under later binders") {
    assertValue("""
      def keep[A](value: A) = {
        let saved = value;
        λ(value: Int) . Λ A . saved
      };
      def main: Int = keep[Int](42)(0)[Bool];
    """, PrimitiveValue.Integer(42))
  }

  test("sort substitution respects universal binders and their enclosing bounds") {
    List(
      """
        type Signature<Sort> = { identity: ∀ [type Sort] -> Sort -> Sort };
        def value: Signature<Int> = { identity = Λ Actual . λ(value: Actual) . value };
        def main = value.identity[Int](42);
      """,
      """
        type Signature<Sort> = { result: ∀ [type T] -> Sort };
        def value: Signature<Bool % Int> = { result = Λ T . 42 };
        def main = value.result[String];
      """,
      """
        type Signature<Sort> = { result: ∀ [type Sort * Sort] -> Int };
        def value: Signature<Bool> = { result = Λ (T * Bool) . 42 };
        def main = value.result[Int];
      """
    ).foreach(assertValue(_, PrimitiveValue.Integer(42)))
  }

  test("lambda parameter and disjointness-bound equality are alpha-invariant") {
    assertValue("""
      def consume: (∀ [type A] -> A -> A) -> Int =
        λ(function: ∀ [type B] -> B -> B) . function[Int](42);
      def main = consume(Λ C . λ(value: C) . value);
    """, PrimitiveValue.Integer(42))
    assertValue("""
      def bounded: ∀ [type A * (∀ [type B] -> B -> B)] -> Int =
        Λ (Actual * (∀ [type C] -> C -> C)) . 42;
      def main = bounded[Int];
    """, PrimitiveValue.Integer(42))
  }

  test("qualified self recursion cannot be captured by a local term binder") {
    List("f", "other").foreach { local =>
      assertValue(s"""
        def f(value: Int): Int =
          if value == 0 then 42 else (λ($local: Int) . Test::f(value - 1))(123);
        def main = f(2);
      """, PrimitiveValue.Integer(42))
    }
  }

  test("mutual global references remain distinct from shadowing term and type binders") {
    assertValue("""
      def even[T](value: Int): Bool =
        if value == 0 then true else (λ(odd: Int) . Test::odd[T](value - 1))(1);
      def odd[T](value: Int): Bool =
        if value == 0 then false else (λ(even: Int) . Test::even[T](value - 1))(1);
      def main = even[Int](6);
    """, PrimitiveValue.Boolean(true))
  }

  test("opened fields are local bindings when discovering module dependencies") {
    assertValue("""
      def answer = open { answer = 42 } in answer;
      def main = answer;
    """, PrimitiveValue.Integer(42))
    assertValue("""
      def first = open { second = 42 } in second;
      def second = first;
      def main = second;
    """, PrimitiveValue.Integer(42))
    assertValue("""
      def record = { answer = 42 };
      def answer = open record in answer;
      def main = answer;
    """, PrimitiveValue.Integer(42))
  }

  test("implicit open self does not invent a recursive module dependency") {
    assertValue("""
      type Required = { answer: Int };
      def answer = trait [self: Required] => { result = answer };
      def base = trait => { answer = 42 };
      def main = (new (base ,, answer)).result;
    """, PrimitiveValue.Integer(42))
  }

  test("ordinary definitions and constructor methods share dependency handling") {
    val declarations = List(
      "def main = (new Box(40)).eval;",
      "(Box value: Int).eval = value + offset;",
      "def offset = 2;"
    )
    declarations.permutations.foreach { ordered =>
      assertValue(ordered.mkString("\n"), PrimitiveValue.Integer(42))
    }
  }

  test("shared forward dependencies are independent of declaration order and compilation session") {
    val declarations = List(
      "def left = seed + 1;",
      "def right = seed + 1;",
      "def seed = 20;",
      "def main = left + right;"
    )
    declarations.permutations.foreach { ordered =>
      assertValue(ordered.mkString("\n"), PrimitiveValue.Integer(42))
    }
    assertValue("def seed = 42; def main = seed;", PrimitiveValue.Integer(42))
    assertValue("def seed = true; def main = seed;", PrimitiveValue.Boolean(true))
  }

  test("expression ascriptions do not declare recursive signatures") {
    List(
      "def loop = (loop : Int);",
      "def loop(value: Int) = (loop(value) : Int);"
    ).foreach { source =>
      assertEquals(elaborationError(source), CpElaborationError.RecursiveDeclarationRequiresType("loop"))
    }
    assertValue("def answer = (42 : Int); def main = answer;", PrimitiveValue.Integer(42))
  }

  test("signature names do not mask local or global polymorphic term bindings") {
    List(
      "def main = { let Signature = Λ T . λ(value: T) . value; Signature[Int](42) };",
      "def Signature[T](value: T) = value; def main = Signature[Int](42);",
      "def Signature[T](value: T) = value; def main = Test::Signature[Int](42);"
    ).foreach { definitions =>
      assertValue("type Signature<Sort> = { field: Sort }; " + definitions, PrimitiveValue.Integer(42))
    }
  }

  test("type names used as terms receive the same diagnostic in every expression position") {
    List("Signature", "Signature[Int]", "(Signature)[Int]", "Test::Signature[Int]").foreach { expression =>
      assertEquals(
        elaborationError(s"type Signature<Sort> = { field: Sort }; def main = $expression;"),
        CpElaborationError.NameResolution(NameResolutionError.KindMismatch(
          if (expression.startsWith("Test::")) NameReference.Qualified(namespace.identifier("Signature"))
          else NameReference.Unqualified("Signature"),
          NameKind.Term,
          NameKind.Type,
          List(namespace.identifier("Signature"))
        ))
      )
    }
  }

  test("term names used as types are diagnosed by the same name-kind boundary") {
    List("answer", "Test::answer").foreach { reference =>
      val mismatch = NameResolutionError.KindMismatch(
        if (reference.startsWith("Test::")) NameReference.Qualified(namespace.identifier("answer"))
        else NameReference.Unqualified("answer"),
        NameKind.Type,
        NameKind.Term,
        List(namespace.identifier("answer"))
      )
      assertEquals(
        elaborationError(s"def answer = 42; def main: $reference = 42;"),
        CpElaborationError.TypeExpansion(TypeExpansionError.NameResolution(mismatch))
      )
      assertEquals(
        elaborationError(s"def answer = 42; type Alias = $reference; def main = 42;"),
        CpElaborationError.NameResolution(mismatch)
      )
    }
  }

  test("primitive overload resolution preserves failures inside either operand") {
    List("missing + 1", "1 + missing", "1 + (missing * 2)", "1 == missing").foreach { expression =>
      assertEquals(
        elaborationError(s"def main = $expression;"),
        CpElaborationError.NameResolution(NameResolutionError.UnknownTerm(NameReference.Unqualified("missing")))
      )
    }
  }

  test("malformed string escapes are parsing errors") {
    List("", "prefix ").foreach { prefix =>
      List("\\q", "\\u12", "\\u12xz").foreach { contents =>
        Cp.compile(sourceFile("\ndef main = \"" + prefix + contents + "\";")) match {
          case Result.Err(CpCompilationError.Parsing(_, ParsingError.Syntax(_, line, column))) =>
            assertEquals(line, 2)
            assertEquals(column, 13 + prefix.length)
          case other => fail(s"expected a parsing error, received: $other")
        }
      }
    }
    assertValue("def main = \"line\\n\\t\\u0041\\\\\\\"\";", PrimitiveValue.Text("line\n\tA\\\""))
  }
}
