package cp.language

import cp.fiobs.runtime.Value
import cp.language.compilation.CpSourceFile
import cp.language.core.Type
import cp.language.elaboration.{CpElaborationError, ModuleDefinitionVisibility}
import cp.language.evaluation.CpEvaluationError
import cp.naming.Namespace
import cp.primitive.PrimitiveValue
import cp.util.Result

import java.nio.file.Paths

class CpSuite extends munit.FunSuite {
  private val testNamespace = Namespace("Test")

  private object Cp {
    def parse(source: String) = _root_.cp.language.Cp.parse(source)

    def elaborate(module: cp.language.core.Module) = {
      _root_.cp.language.Cp.elaborate(module, testNamespace)
    }

    def compile(source: String) = {
      _root_.cp.language.Cp.compile(CpSourceFile(Paths.get("Test.cp"), source))
    }

    def evaluate(source: String) = {
      _root_.cp.language.Cp.evaluate(CpSourceFile(Paths.get("Test.cp"), source))
    }
  }

  test("the complete parser-to-Fiobs pipeline evaluates a grouped declaration") {
    val source =
      """
        |def maximum(left: Int, right: Int): Int =
        |  if left > right then left else right;
        |def main: Int = maximum(1, 2);
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(2)))
    )
  }

  test("the complete pipeline accepts omitted definition, member, and block terminators") {
    val source =
      """
        |type Pair = { left: Int right: Int }
        |def pair(left: Int, right: Int): Pair = {
        |  left = left
        |  right = right
        |}
        |def main: Int = {
        |  let result = pair(40, 2)
        |  result.left + result.right
        |}
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("a typed CP lambda synthesizes an omitted declaration result") {
    val source =
      """
        |def identity(value: Int) = value;
        |def main: Int = identity(4);
        |""".stripMargin

    Cp.compile(source) match {
      case Result.Ok(module) =>
        assertEquals(module.elaboratedModule.header.termSignatures(testNamespace.identifier("main")), Type.Integer)
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("compilation preserves top-level definitions as a module") {
    val source =
      """
        |type Number = Int;
        |def increment(value: Number): Number = value + 1;
        |def main: Number = increment(41);
        |""".stripMargin

    Cp.compile(source) match {
      case Result.Ok(module) =>
        assertEquals(module.sourceModule.definitions.map(_.definitionName), List("Number", "increment", "main"))
        assertEquals(module.elaboratedModule.header.typeDefinitions.keySet.map(_.name), Set("Number"))
        assertEquals(module.elaboratedModule.header.termSignatures.keySet.map(_.name), Set("increment", "main"))
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("a module requires main only when the module is evaluated") {
    val source = "def value: Int = 42;"

    Cp.parse(source) match {
      case Result.Ok(sourceModule) =>
        Cp.elaborate(sourceModule) match {
          case Result.Ok(elaboratedModule) =>
            assertEquals(elaboratedModule.header.termSignatures.keySet.map(_.name), Set("value"))
          case other => fail(s"unexpected elaboration result: $other")
        }
      case other => fail(s"unexpected parsing result: $other")
    }

    Cp.compile(source) match {
      case Result.Ok(_) => ()
      case other => fail(s"unexpected compilation result: $other")
    }
    assertEquals(
      Cp.evaluate(source),
      Result.Err(CpProgramError.Evaluation(
        CpEvaluationError.EntryPointNotFound(testNamespace.identifier("main"))
      ))
    )
  }

  test("block lets elaborate through ordinary lazy bindings") {
    val source =
      """
        |def squareHalf(value: Float): Float = {
        |  let half: Float = value / 2.0;
        |  half * half
        |};
        |def main: Float = squareHalf(4.0);
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Decimal(BigDecimal("4.00"))))
    )
  }

  test("recursive lets translate to Fiobs fixpoints") {
    val source =
      """
        |def main: Int =
        |  let rec countDown: Int -> Int = (value: Int) =>
        |    if value == 0 then 42 else countDown(value - 1)
        |  in countDown(3);
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("an ordinary let initializer uses the surrounding scope") {
    val source =
      """
        |def value: Int = 41;
        |def main: Int = let value: Int = value + 1 in value;
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("an ordinary let does not bind its name in its initializer") {
    val source = "def main: Int = let loop: Int = loop in 42;"

    Cp.compile(source) match {
      case Result.Err(CpCompilationError.Elaboration(
            `testNamespace`,
            CpElaborationError.Located(_, CpElaborationError.NameResolution(_))
          )) => ()
      case other => fail(s"expected an unbound-variable error, received: $other")
    }
  }

  test("expression failures retain exact spans in declarations and block initializers") {
    val sources = List(
      "def main = 1 ,, 2;",
      """
        |def main = {
        |  let invalid = 1 ,, 2
        |  invalid
        |};
        |""".stripMargin
    )

    sources.foreach { source =>
      Cp.compile(source) match {
        case Result.Err(CpCompilationError.Elaboration(
              `testNamespace`,
              CpElaborationError.Located(
                sourceSpan,
                CpElaborationError.TypesAreNotDisjoint(Type.Integer, Type.Integer)
              )
            )) =>
          assertEquals(source.substring(sourceSpan.startOffset, sourceSpan.endOffset), "1 ,, 2")
        case other => fail(s"expected a located disjointness error, received: $other")
      }
    }
  }

  test("annotated top-level definitions are recursively scoped") {
    val source =
      """
        |def size: Int = 5;
        |
        |def linear(value: Int): Int =
        |  if value == 0 then 0 else linear(value - 1) + 1;
        |
        |def quadratic(value: Int): Int =
        |  if value == 0 then 0 else linear(size) + quadratic(value - 1);
        |
        |def cubic(value: Int): Int =
        |  if value == 0 then 0 else quadratic(size) + cubic(value - 1);
        |
        |def main: Int = cubic(size);
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(125)))
    )
  }

  test("a recursive top-level definition requires a result annotation") {
    val source =
      """
        |def loop(value: Int) = loop(value);
        |def main: Int = loop(0);
        |""".stripMargin

    assertEquals(
      Cp.compile(source),
      Result.Err(CpCompilationError.Elaboration(
        testNamespace,
        CpElaborationError.RecursiveDeclarationRequiresType("loop")
      ))
    )
  }

  test("mutually recursive top-level definitions share a generated record fixpoint") {
    val source =
      """
        |def even(value: Int): Bool =
        |  if value == 0 then true else odd(value - 1);
        |
        |def odd(value: Int): Bool =
        |  if value == 0 then false else even(value - 1);
        |
        |def main: Bool = odd(9);
        |""".stripMargin

    Cp.compile(source) match {
      case Result.Ok(module) =>
        val mutualBinding = module.elaboratedModule.termDefinitions.values.collectFirst {
          case definition
              if definition.visibility == ModuleDefinitionVisibility.Internal &&
                definition.identifier.name.startsWith("$mutual_") => definition
        }.getOrElse(fail("the mutual-recursion bundle was not generated"))
        assert(mutualBinding.identifier.name.matches("\\$mutual_[0-9a-f]{8,}"))
        mutualBinding.initializer match {
          case cp.fiobs.Expr.Fix(selfName, _, cp.fiobs.Expr.Merge(_, _)) =>
            assert(selfName.matches("\\$mutual_self_[0-9a-f]{8,}"))
          case other => fail(s"unexpected mutual-recursion initializer: $other")
        }
        assertEquals(
          module.elaboratedModule.header.termSignatures.keySet.map(_.name),
          Set("even", "odd", "main")
        )
        assertEquals(
          Cp.evaluate(source),
          Result.Ok(Value.Primitive(PrimitiveValue.Boolean(true)))
        )
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("every definition in a mutual recursion component requires a result annotation") {
    val source =
      """
        |def even(value: Int) =
        |  if value == 0 then true else odd(value - 1);
        |def odd(value: Int): Bool =
        |  if value == 0 then false else even(value - 1);
        |def main: Bool = odd(9);
        |""".stripMargin

    assertEquals(
      Cp.compile(source),
      Result.Err(CpCompilationError.Elaboration(
        testNamespace,
        CpElaborationError.RecursiveDeclarationRequiresType("even")
      ))
    )
  }

  test("mutual-reference rewriting preserves local term shadowing") {
    val source =
      """
        |def first(value: Int): Int =
        |  if value == 0 then ((second: Int) => second)(42)
        |  else second(value - 1);
        |
        |def unrelated: Int = 1;
        |
        |def second(value: Int): Int =
        |  if value == 0 then 0 else first(value - 1);
        |
        |def main: Int = first(0);
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("signature applications expand before Fiobs type translation") {
    val source =
      """
        |type ValueSig<Value> = { value: Value; };
        |def read(record: ValueSig<Int>): Int = record.value;
        |def main: Int = read({ value = 5; });
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(5)))
    )
  }

  test("zero-sort type declarations expand as named aliases") {
    val source =
      """
        |type Point = { x: Int; y: Int; };
        |def sum(point: Point): Int = point.x + point.y;
        |def main: Int = sum({ x = 20; y = 22; });
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("ordinary type binders shadow zero-sort type declarations") {
    val source =
      """
        |type Element = Int;
        |def identity[Element](value: Element): Element = value;
        |def main: Bool = identity[Bool](true);
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Boolean(true)))
    )
  }

  test("ordinary disjoint polymorphism survives CP elaboration") {
    val source =
      """
        |def identity[Element](value: Element): Element = value;
        |def main: Int = identity[Int](3);
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(3)))
    )
  }

  test("term application uses Fiobs applicative distribution") {
    val source =
      """
        |def increment(value: Int): Int = value + 1;
        |def isPositive(value: Int): Bool = value > 0;
        |def main: Int = ((increment ,, isPositive)(41) : Int);
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("type application uses Fiobs applicative distribution") {
    val source =
      """
        |def integerConstant = [type Element] => 42;
        |def booleanConstant = Λ[type Element] => true;
        |def main: Int = ((integerConstant ,, booleanConstant)[Unit] : Int);
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("type abstraction checking respects alpha-equivalence") {
    val source =
      """
        |def main: Int =
        |  ((Λ Actual . λ(value: Actual) . value)
        |    : ∀ [type Expected] -> Expected -> Expected)[Int](42);
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("a parentless trait elaborates directly to a callable function") {
    val source =
      """
        |def component: Trait[{ value: Int }] =
        |  trait implements { value: Int } => {
        |    value = 42;
        |  };
        |def main: Int = (new component).value;
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("recursive self passes through lazy trait application as a fixpoint") {
    val source =
      """
        |def component: Trait[{ value: Int }] =
        |  trait implements { value: Int } => {
        |    value = 7;
        |  };
        |def main: Int = (new component).value;
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(7)))
    )
  }

  test("an impl declaration normalizes to an ordinary trait declaration") {
    val source =
      """
        |impl component from Top = {
        |  value = 42;
        |};
        |def main: Int = (new component).value;
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("inherited traits bind super and retain both provided interfaces") {
    val source =
      """
        |def base: Trait[{ base: Int }] =
        |  trait implements { base: Int } => {
        |    base = 20;
        |  };
        |def component: Trait[{ base: Int } & { child: Int }] =
        |  trait implements { base: Int } & { child: Int } inherits base => {
        |    child = 22;
        |  };
        |def main: Int = (new component).base + (new component).child;
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("merged traits receive one shared self value") {
    val source =
      """
        |def left = trait implements { left: Int } => {
        |  left = 19;
        |};
        |def right = trait implements { right: Int } => {
        |  right = 23;
        |};
        |def main: Int =
        |  let combined = new (left ,, right) in
        |  combined.left + combined.right;
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("intersections of constructor traits compose before instantiation") {
    val source =
      """
        |type ExpressionSig<Expression> = { Literal: Int -> Expression; };
        |type Left = { left: Int; };
        |type Right = { right: Int; };
        |
        |def leftFamily = trait implements ExpressionSig<Left> => {
        |  (Literal value).left = value;
        |};
        |
        |def rightFamily = trait implements ExpressionSig<Right> => {
        |  (Literal value).right = value;
        |};
        |
        |def family = new (leftFamily ,, rightFamily);
        |def literal = new family.Literal(21);
        |def main: Int = literal.left + literal.right;
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("intersections of constructor traits compose before forwarding") {
    val source =
      """
        |type ExpressionSig<Expression> = { Literal: Int -> Expression; };
        |type Left = { left: Int; };
        |type Right = { right: Int; };
        |
        |def leftFamily = trait implements ExpressionSig<Left> => {
        |  (Literal value).left = value;
        |};
        |
        |def rightFamily = trait implements ExpressionSig<Right> => {
        |  (Literal value).right = value;
        |};
        |
        |def family = new (leftFamily ,, rightFamily);
        |def literal = family.Literal(21) ^ top;
        |def main: Int = literal.left + literal.right;
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("a constructor method without a self clause retains the enclosing trait self") {
    val source =
      """
        |type ExpressionSig<Expression> = { Literal: Int -> Expression; };
        |type Eval = { eval: Int; };
        |type Doubled = { doubled: Eval; };
        |
        |def evaluate = trait implements ExpressionSig<Eval> => {
        |  (Literal value).eval = value;
        |};
        |
        |def double = trait [self: ExpressionSig<Eval>] implements ExpressionSig<Doubled> => {
        |  (Literal value).doubled = new self.Literal(value * 2);
        |};
        |
        |def evaluatedFamily = new evaluate;
        |def doublingFamily = double ^ evaluatedFamily;
        |def literal = new doublingFamily.Literal(21);
        |def main: Int = literal.doubled.eval;
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("a trait can retain an abstract self without enumerating record fields") {
    val source =
      """
        |type Added = { added: Int; };
        |
        |def addField[Base * Added](value: Int) = trait [self: Base] implements Added => {
        |  added = value;
        |};
        |
        |def main = top;
        |""".stripMargin

    Cp.compile(source) match {
      case Result.Ok(_) => ()
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("open evaluates its receiver once and binds its fields") {
    val source = "def main: Int = open { value = 42; } in value;"

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("lazy recursive self can observe an inherited field") {
    val source =
      """
        |def base: Trait[{ value: Int }] =
        |  trait implements { value: Int } => {
        |    value = 21;
        |  };
        |def component: Trait[{ value: Int }, { value: Int } & { doubled: Int }] =
        |  trait [self: { value: Int }]
        |    implements { value: Int } & { doubled: Int }
        |    inherits base => {
        |      doubled = value + value;
        |    };
        |def main: Int = (new component).doubled;
        |""".stripMargin

    assertEquals(
      Cp.evaluate(source),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }
}
