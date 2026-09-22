package cp.language

import cp.fiobs.runtime.Value
import cp.fitrie.evaluation.{Evaluation, EvaluationError}
import cp.language.compilation.{CpFiTrieCompiler, CpSourceFile}
import cp.language.elaboration.CpElaborationError
import cp.language.evaluation.CpEvaluator
import cp.naming.Namespace
import cp.primitive.{PrimitiveType, PrimitiveValue}
import cp.util.Result

import java.nio.file.Paths

class RecursiveTypesSuite extends munit.FunSuite {
  private val namespace = Namespace("Recursive")

  test("interface sugar preserves recursive self references beneath an enclosing sort") {
    assertInteger(
      """interface Stream<Element> { value: Element; next: Stream; }
        |def repeat[A](value: A): Stream<A> = fold[Stream<A>] { value = value; next = repeat[A](value) };
        |def main = (unfold[Stream<Int>] (unfold[Stream<Int>] repeat[Int](42)).next).value;
        |""".stripMargin,
      42
    )
  }

  test("interface sugar retains explicit fold boundaries and does not introduce TopLike") {
    assertRejected("interface Box { value: Int; } def main: Box = { value = 42 };")
    assertRejected("interface Box { value: Int; } def main = (fold[Box] { value = 42 }).value;")
    assertRejected("interface Empty {} def main: Empty = top;")
    assertInteger("interface Empty {} def empty: Empty = fold[Empty] top; def main: Int = 42;", 42)
  }

  test("finite fold and unfold agree on both backends") {
    assertInteger(
      """type Box = μ X. { value: Int };
        |def main = (unfold[Box] (fold[Box] { value = 42 })).value;
        |""".stripMargin,
      42
    )
  }

  test("ASCII mu syntax has the same lexical binding as Unicode mu") {
    assertInteger(
      """type Box = mu X. Int;
        |def main = unfold[Box] (fold[Box] 42);
        |""".stripMargin,
      42
    )
  }

  test("successive observations of an infinite stream retain the changing receiver") {
    assertInteger(
      """type Stream = μ S. { head: Int; tail: S };
        |def naturals(n: Int): Stream = fold[Stream] { head = n; tail = naturals(n + 1) };
        |def main = (unfold[Stream] (unfold[Stream] naturals(40)).tail).head + 1;
        |""".stripMargin,
      42
    )
  }

  test("binary methods can use the enclosing recursive type negatively") {
    assertInteger(
      """type Object = μ Self. { value: Int; same: Self -> Bool };
        |def object(value: Int): Object = fold[Object] {
        |  value = value;
        |  same(other: Object): Bool = (unfold[Object] other).value == value;
        |};
        |def main = if (unfold[Object] object(42)).same(object(42)) then 42 else 0;
        |""".stripMargin,
      42
    )
  }

  test("recursive width subtyping converts every stream element") {
    assertInteger(
      """type Wide = μ S. { value: Int; extra: Bool; next: S };
        |type Narrow = μ S. { value: Int; next: S };
        |def naturals(n: Int): Wide = fold[Wide] { value = n; extra = true; next = naturals(n + 1) };
        |def narrow: Narrow = naturals(40);
        |def main = (unfold[Narrow] (unfold[Narrow] (unfold[Narrow] narrow).next).next).value;
        |""".stripMargin,
      42
    )
  }

  test("disjoint recursive components can share unfold and next routes") {
    assertInteger(
      """type Left = μ S. { left: Int; next: S };
        |type Right = μ S. { right: Int; next: S };
        |def left(n: Int): Left = fold[Left] { left = n; next = left(n + 1) };
        |def right(n: Int): Right = fold[Right] { right = n; next = right(n + 1) };
        |def merged = left(39) ,, right(2);
        |def main = (unfold[Left] (unfold[Left] merged).next).left + (unfold[Right] merged).right;
        |""".stripMargin,
      42
    )
  }

  test("recursive interfaces can be polymorphic type arguments") {
    assertInteger(
      """type Box = μ X. Int;
        |def identity[A](value: A): A = value;
        |def main = unfold[Box] identity[Box](fold[Box] 42);
        |""".stripMargin,
      42
    )
  }

  test("a recursive binder keeps the enclosing polymorphic type parameter") {
    assertInteger(
      """def box[A](value: A): μ X. { value: A } = fold[μ X. { value: A }] { value = value };
        |def main = (unfold[μ X. { value: Int }] box[Int](42)).value;
        |""".stripMargin,
      42
    )
  }

  test("universal binders inside a recursive body retain both scopes") {
    assertInteger(
      """type Poly = μ X. forall A. A -> A;
        |def poly: Poly = fold[Poly] ([type A] => (x: A) => x);
        |def main = (unfold[Poly] poly)[Int](42);
        |""".stripMargin,
      42
    )
  }

  test("nested recursive binders do not capture their enclosing variables") {
    assertInteger(
      """type Outer = μ X. { value: Int; nested: μ Y. { parent: X } };
        |type Inner = μ Y. { parent: Outer };
        |def outer: Outer = fold[Outer] { value = 42; nested = fold[Inner] { parent = outer } };
        |def main = (unfold[Outer] (unfold[Inner] (unfold[Outer] outer).nested).parent).value;
        |""".stripMargin,
      42
    )
  }

  test("unfolding a recursive universal retains fresh type arguments at successive nodes") {
    assertInteger(
      """type Stream = μ S. forall A. A -> { head: A; tail: S };
        |def stream: Stream = fold[Stream] ([type A] => (value: A) => { head = value; tail = stream });
        |def later: Stream = (unfold[Stream] stream)[Bool](true).tail;
        |def main = (unfold[Stream] later)[Int](42).head;
        |""".stripMargin,
      42
    )
  }

  test("trait fields can provide recursive values while retaining lazy self recursion") {
    assertInteger(
      """type Stream = μ S. { head: Int; tail: S };
        |def naturals(n: Int): Stream = fold[Stream] { head = n; tail = naturals(n + 1) };
        |def component = trait => { stream = naturals(42) };
        |def main = (unfold[Stream] (new component).stream).head;
        |""".stripMargin,
      42
    )
  }

  test("discarding a folded error preserves call by name") {
    assertInteger(
      """type Box = μ X. Int;
        |def ignore(value: Box): Int = 42;
        |def main = ignore(fold[Box] (1 / 0));
        |""".stripMargin,
      42
    )
  }

  test("folded empty interfaces remain distinct from exact Top") {
    assertInteger(
      """type Empty = μ X. Top;
        |def empty: Empty = fold[Empty] top;
        |def ignore(value: Top): Int = 42;
        |def main = ignore(unfold[Empty] empty);
        |""".stripMargin,
      42
    )
    assertRejected("type Empty = μ X. Top; def main: Empty = top;")
  }

  test("two silent recursive values can merge and unfold to Top") {
    val source = """type Empty = μ X. Top;
      |def main = unfold[Empty] (fold[Empty] top ,, fold[Empty] top);
      |""".stripMargin
    val program = expectSuccess(Cp.compileModules(List(sourceFile(source))))
    assertEquals(CpEvaluator.evaluate(program, namespace), Result.Ok(Value.Top))
    val target = expectSuccess(CpFiTrieCompiler.compile(program, namespace))
    assertEquals(
      Evaluation.observeTermination(target.entry, PrimitiveType.Integer, target.globalEnvironment),
      Result.Err(EvaluationError.MissingTermination(PrimitiveType.Integer))
    )
  }

  test("fold boundaries are required for record and recursive interfaces") {
    assertRejected("type Box = μ X. { value: Int }; def main: Box = { value = 42 };")
    assertRejected("type Box = μ X. { value: Int }; def main = (fold[Box] { value = 42 }).value;")
    assertRejected("type Box = μ X. Int; def main = unfold[Box] 42;")
    assertRejected("type Box = μ X. Int; def main = fold[Box] true;")
  }

  test("a nonrecursive fold annotation reports the owning type error") {
    List("def main = fold[Int] 42;", "def main = unfold[Int] 42;").foreach { source =>
      Cp.compile(sourceFile(source)) match {
        case Result.Err(CpCompilationError.Elaboration(_, error)) =>
          assertEquals(error.underlying, CpElaborationError.ExpectedRecursiveType(typing.Type.Integer))
        case result => fail(s"expected a recursive-type diagnostic, found $result")
      }
    }
  }

  test("negative recursive subtyping does not compare bodies only once") {
    assertRejected(
      """type Source = μ X. X -> Int;
        |type Target = μ X. X -> Top;
        |def source: Source = fold[Source] ((x: Source) => 42);
        |def main: Target = source;
        |""".stripMargin
    )
  }

  private def sourceFile(source: String): CpSourceFile = CpSourceFile(Paths.get("Recursive.cp"), source)

  private def assertInteger(source: String, expected: Int): Unit = {
    val program = expectSuccess(Cp.compileModules(List(sourceFile(source))))
    assertEquals(CpEvaluator.evaluate(program, namespace), Result.Ok(Value.Primitive(PrimitiveValue.Integer(expected))))
    val target = expectSuccess(CpFiTrieCompiler.compile(program, namespace))
    assertEquals(
      Evaluation.observeTermination(target.entry, PrimitiveType.Integer, target.globalEnvironment),
      Result.Ok(PrimitiveValue.Integer(expected))
    )
  }

  private def assertRejected(source: String): Unit = Cp.compile(sourceFile(source)) match {
    case Result.Err(_: CpCompilationError.Elaboration) => ()
    case result => fail(s"expected a source typing error, found $result")
  }

  private def expectSuccess[A, E](result: Result[A, E]): A = result match {
    case Result.Ok(value) => value
    case Result.Err(error) => fail(s"unexpected failure: $error")
  }
}
