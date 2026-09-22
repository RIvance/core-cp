package cp.fiobs

import cp.fiobs.eval.LazyEvaluation
import cp.fiobs.runtime.{EvaluationError, FullyEvaluatedValue, Value}
import cp.fiobs.typing.TypeError
import cp.primitive.{BinaryOperator, PrimitiveOperationError, PrimitiveValue}
import cp.util.Result

class RecursiveEvaluationSuite extends munit.FunSuite {
  private val integerBox = SurfaceType.Recursive("X", SurfaceType.Integer)
  private val divisionByZero = Expr.Binary(
    BinaryOperator.Divide,
    Expr.Literal(PrimitiveValue.Integer(1)),
    Expr.Literal(PrimitiveValue.Integer(0))
  )

  test("a fold is a value without evaluating its payload") {
    Fiobs.evaluate(Expr.Fold(integerBox, divisionByZero)) match {
      case Result.Ok(Value.Fold(Type.Recursive(Type.Integer), _)) => ()
      case other => fail(s"expected a suspended fold, received $other")
    }
  }

  test("unfolding exposes an error in the suspended payload") {
    assertEquals(
      Fiobs.evaluate(Expr.Unfold(integerBox, Expr.Fold(integerBox, divisionByZero))),
      Result.Err(ProgramError.Evaluation(EvaluationError.PrimitiveFailure(
        PrimitiveOperationError.DivisionByZero(BinaryOperator.Divide)
      )))
    )
  }

  test("fold checks a lambda against the unfolded arrow interface") {
    val functionBox = SurfaceType.Recursive("X", SurfaceType.Arrow(SurfaceType.Integer, SurfaceType.Integer))
    val expression = Expr.Application(
      Expr.Unfold(functionBox, Expr.Fold(functionBox, Expr.Lambda("x", Expr.Variable("x")))),
      Expr.Literal(PrimitiveValue.Integer(42))
    )
    assertEquals(Fiobs.evaluate(expression), Result.Ok(Value.Primitive(PrimitiveValue.Integer(42))))
  }

  test("casting a fold to an empty recursive interface keeps its fold boundary") {
    val emptyBox = SurfaceType.Recursive("X", SurfaceType.Top)
    val expression = Expr.Annotation(Expr.Fold(integerBox, divisionByZero), emptyBox)
    Fiobs.evaluate(expression) match {
      case Result.Ok(Value.Fold(Type.Recursive(Type.Top), _)) => ()
      case other => fail(s"expected an empty folded interface, received $other")
    }
    assertEquals(Fiobs.evaluate(Expr.Unfold(emptyBox, expression)), Result.Ok(Value.Top))
  }

  test("fully evaluating a fold recursively evaluates its finite payload") {
    val inner = SurfaceType.Recursive("Y", SurfaceType.Integer)
    val outer = SurfaceType.Recursive("X", inner)
    val expression = Expr.Fold(outer, Expr.Fold(inner, Expr.Literal(PrimitiveValue.Integer(42))))
    val program = expectSuccess(Fiobs.compile(expression))
    assertEquals(
      LazyEvaluation.evaluateFully(program.runtimeTerm),
      Result.Ok(FullyEvaluatedValue.Fold(
        Type.Recursive(Type.Recursive(Type.Integer)),
        FullyEvaluatedValue.Fold(
          Type.Recursive(Type.Integer),
          FullyEvaluatedValue.Primitive(PrimitiveValue.Integer(42))
        )
      ))
    )
  }

  test("fully evaluating a fold propagates payload errors") {
    val program = expectSuccess(Fiobs.compile(Expr.Fold(integerBox, divisionByZero)))
    assertEquals(
      LazyEvaluation.evaluateFully(program.runtimeTerm),
      Result.Err(EvaluationError.PrimitiveFailure(PrimitiveOperationError.DivisionByZero(BinaryOperator.Divide)))
    )
  }

  test("a record self-cast preserves the suspended response cast to Top") {
    val failingTop = Expr.If(
      Expr.Binary(BinaryOperator.Equal, divisionByZero, Expr.Literal(PrimitiveValue.Integer(0))),
      Expr.Top,
      Expr.Top
    )
    val expression = Expr.Annotation(
      Expr.Projection(Expr.Record("field", failingTop), "field"),
      SurfaceType.Top
    )
    assertEquals(Fiobs.evaluate(expression), Result.Ok(Value.Top))
  }

  test("fold and unfold reject malformed recursive annotations at the typing boundary") {
    val value = Term.Literal(PrimitiveValue.Integer(42))
    assertEquals(Fiobs.infer(Term.Fold(Type.Integer, value)), Result.Err(TypeError.ExpectedRecursiveType(Type.Integer)))
    assertEquals(Fiobs.infer(Term.Unfold(Type.Top, value)), Result.Err(TypeError.ExpectedRecursiveType(Type.Top)))
    val malformed = Type.Recursive(Type.Variable(1))
    assertEquals(Fiobs.infer(Term.Fold(malformed, value)), Result.Err(TypeError.IllFormedType(malformed)))
  }

  private def expectSuccess[A, E](result: Result[A, E]): A = result match {
    case Result.Ok(value) => value
    case Result.Err(error) => fail(s"unexpected failure: $error")
  }
}
