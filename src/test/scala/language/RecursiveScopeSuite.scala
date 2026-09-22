package cp.language

import cp.fiobs.{Type as CoreType}
import cp.fiobs.runtime.Value
import cp.language.compilation.{CpSourceFile, SourcePath}
import cp.language.elaboration.CpElaborationError
import cp.language.typing.Type
import cp.naming.Namespace
import cp.primitive.PrimitiveValue
import cp.util.Result

class RecursiveScopeSuite extends munit.FunSuite {
  private val namespace = Namespace("Scope")

  test("an imported recursive alias stays closed beneath a same-named universal binder") {
    val library = sourceFile("Types.cp", """
      module Types;
      type Node = μ Node. { value: Int; next: Node };
    """)
    val application = sourceFile("Scope.cp", """
      import Types::Node;
      def keep[Node](value: Types::Node): Types::Node = value;
      def node: Node = fold[Node] { value = 42; next = node };
      def main = (unfold[Node] (unfold[Node] keep[Bool](node)).next).value;
    """)
    val program = expectSuccess(Cp.compileModules(List(application, library)))
    val recursiveType = CoreType.Recursive(CoreType.Intersection(
      CoreType.Record("value", CoreType.Integer),
      CoreType.Record("next", CoreType.Variable(0))
    ))

    assertEquals(
      program.modules(namespace).definitions(namespace.identifier("keep")).programType,
      CoreType.ForAll(CoreType.Top, CoreType.Arrow(recursiveType, recursiveType))
    )
    assertEquals(
      Cp.evaluate(List(application, library), namespace),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("signature sort substitution crosses a recursive binder without changing its self reference") {
    val source = """
      type Stream<Element> = μ Self. { read: Element; consume: Element -> Int; next: Self };
      def identity(value: Stream<Bool % Int>): Stream<Bool % Int> = value;
    """
    val recursiveType = CoreType.Recursive(CoreType.Intersection(
      CoreType.Intersection(
        CoreType.Record("read", CoreType.Integer),
        CoreType.Record("consume", CoreType.Arrow(
          CoreType.Intersection(CoreType.Boolean, CoreType.Integer), CoreType.Integer
        ))
      ),
      CoreType.Record("next", CoreType.Variable(0))
    ))

    assertDefinitionType(source, "identity", CoreType.Arrow(recursiveType, recursiveType))
  }

  test("a recursive binder shadows a signature sort of the same name") {
    val parsed = expectSuccess(Cp.parse("""
      type Member<Element> = { next: Element };
      type Wrapped<Element> = μ Element. Member<Element>;
      def identity(value: Wrapped<Int>): μ Other. { next: Other } = value;
    """))
    val elaborated = expectSuccess(Cp.elaborate(parsed, namespace))
    val recursiveType = Type.Recursive(Type.Record("next", Type.Variable(0)))

    assertEquals(
      elaborated.globalTermTypes(namespace.identifier("identity")),
      Type.Arrow(recursiveType, recursiveType)
    )
  }

  test("sorts and universal variables keep their distinct identities under two recursive binders") {
    val source = """
      type Family<Sort> = μ Outer. forall (A * Sort). μ Inner. {
        root: Outer; argument: A; next: Inner; result: Sort
      };
      def identity(value: Family<Bool>): Family<Bool> = value;
    """
    val fields = CoreType.Intersection(
      CoreType.Intersection(
        CoreType.Intersection(
          CoreType.Record("root", CoreType.Variable(2)),
          CoreType.Record("argument", CoreType.Variable(1))
        ),
        CoreType.Record("next", CoreType.Variable(0))
      ),
      CoreType.Record("result", CoreType.Boolean)
    )
    val recursiveType = CoreType.Recursive(CoreType.ForAll(CoreType.Boolean, CoreType.Recursive(fields)))

    assertDefinitionType(source, "identity", CoreType.Arrow(recursiveType, recursiveType))
  }

  test("an inner universal binder shadows both the recursive binder and the signature sort") {
    val source = """
      type Wrapped<A> = μ A. forall A. A -> A;
      def identity(value: Wrapped<Bool>): Wrapped<Int> = value;
    """
    val recursiveType = CoreType.Recursive(CoreType.ForAll(
      CoreType.Top, CoreType.Arrow(CoreType.Variable(0), CoreType.Variable(0))
    ))

    assertDefinitionType(source, "identity", CoreType.Arrow(recursiveType, recursiveType))
  }

  test("a recursive binder cannot capture an enclosing polymorphic term argument") {
    val source = "def bad[A](value: A): μ A. A = fold[μ A. A] value;"

    Cp.compile(sourceFile("Scope.cp", source)) match {
      case Result.Err(CpCompilationError.Elaboration(_, error)) => error.underlying match {
        case CpElaborationError.TypeMismatch(_, actualType, expectedType) =>
          assertEquals(actualType, Type.Variable(0))
          assertEquals(expectedType, Type.Recursive(Type.Variable(0)))
        case other => fail(s"expected a type mismatch, found $other")
      }
      case other => fail(s"expected rejection of the captured variable, found $other")
    }
  }

  test("fold and unfold consume the complete application and projection spine") {
    List("fold", "unfold").foreach { operator =>
      List("make(41)", "make 41", "source.value", "source.make[Int](41).value").foreach { operand =>
        assertEquals(
          expectSuccess(Cp.parse(s"def main = $operator[R] $operand;")),
          expectSuccess(Cp.parse(s"def main = $operator[R] ($operand);"))
        )
      }
    }
  }

  test("parentheses distinguish using an unfolded result from using its operand") {
    List(".value", "(42)", "[Int](42)").foreach { suffix =>
      assertNotEquals(
        expectSuccess(Cp.parse(s"def main = (unfold[R] source)$suffix;")),
        expectSuccess(Cp.parse(s"def main = unfold[R] source$suffix;"))
      )
    }
    assertEquals(
      expectSuccess(Cp.parse("def main = unfold[R] fold[R] 42;")),
      expectSuccess(Cp.parse("def main = unfold[R] (fold[R] 42);"))
    )
    assertEquals(
      expectSuccess(Cp.parse("def main = unfold[R] source + 1;")),
      expectSuccess(Cp.parse("def main = (unfold[R] source) + 1;"))
    )
  }

  private def assertDefinitionType(source: String, name: String, expected: CoreType): Unit = {
    val compiled = expectSuccess(Cp.compile(sourceFile("Scope.cp", source)))
    assertEquals(compiled.definitions(namespace.identifier(name)).programType, expected)
  }

  private def sourceFile(path: String, source: String): CpSourceFile = CpSourceFile(SourcePath(path), source)

  private def expectSuccess[A, E](result: Result[A, E]): A = result match {
    case Result.Ok(value) => value
    case Result.Err(error) => fail(s"unexpected failure: $error")
  }
}
