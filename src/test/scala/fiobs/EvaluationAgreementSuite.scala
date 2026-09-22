package cp.fiobs

import cp.fiobs.eval.LazyEvaluation
import cp.fiobs.runtime.{EvaluationError, GlobalEnvironment, RuntimeTerm, Value}
import cp.naming.Namespace
import cp.primitive.{BinaryOperator, PrimitiveOperationError, PrimitiveValue}
import cp.util.Result

import scala.annotation.tailrec

class EvaluationAgreementSuite extends munit.FunSuite {
  private def integer(value: Int): Term = Term.Literal(PrimitiveValue.Integer(value))
  private val divisionByZero = Term.Binary(BinaryOperator.Divide, integer(1), integer(0))
  private val failingBoolean = Term.Binary(BinaryOperator.Equal, divisionByZero, integer(0))

  test("selected merge branches remain usable on either side of a discarded error") {
    for (merged <- List(
      Term.Merge(integer(42), failingBoolean),
      Term.Merge(failingBoolean, integer(42))
    )) {
      assertAgreement(Term.Annotation(merged, Type.Integer), Result.Ok(Value.Primitive(PrimitiveValue.Integer(42))))
    }
  }

  test("a target cast retries after a merge contributor exposes its interface") {
    val identity = Term.Annotation(Term.Lambda(Term.Variable(0)), Type.Arrow(Type.Integer, Type.Integer))
    val pending = Term.Application(identity, integer(42))
    assertAgreement(
      Term.Annotation(Term.Merge(pending, failingBoolean), Type.Integer),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("exact Top erases an error before reducing its receiver") {
    assertAgreement(Term.Annotation(divisionByZero, Type.Top), Result.Ok(Value.Top))
  }

  test("a record self-cast keeps the response cast that erases a failing Top computation") {
    val failingTop = Term.If(failingBoolean, Term.Top, Term.Top)
    assertAgreement(
      Term.Annotation(Term.Projection(Term.Record("field", failingTop), "field"), Type.Top),
      Result.Ok(Value.Top)
    )
  }

  test("a demanded error and an unselected merge retain their original error behavior") {
    val expected = Result.Err(EvaluationError.PrimitiveFailure(
      PrimitiveOperationError.DivisionByZero(BinaryOperator.Divide)
    ))
    assertAgreement(divisionByZero, expected)
    assertAgreement(Term.Merge(integer(42), failingBoolean), expected)
  }

  test("distributive casts through curried functions preserve the selected response") {
    val resultType = Type.Intersection(Type.Record("value", Type.Integer), Type.Record("flag", Type.Boolean))
    val function = Term.Annotation(
      Term.Lambda(Term.Lambda(Term.Merge(
        Term.Record("value", Term.Variable(0)),
        Term.Record("flag", Term.Literal(PrimitiveValue.Boolean(true)))
      ))),
      Type.Arrow(Type.Top, Type.Arrow(Type.Integer, resultType))
    )
    val result = Term.Application(Term.Application(function, Term.Top), integer(42))
    assertAgreement(
      Term.Annotation(Term.Projection(result, "value"), Type.Integer),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("a fold conversion leaves its payload cast suspended until unfolding") {
    val source = Type.Recursive(Type.Integer)
    val target = Type.Recursive(Type.Top)
    assertAgreement(
      Term.Unfold(target, Term.Annotation(Term.Fold(source, divisionByZero), target)),
      Result.Ok(Value.Top)
    )
  }

  test("each evaluation uses its own immutable global environment") {
    val identifier = Namespace("Agreement").identifier("answer")
    val term = RuntimeTerm.Cast(RuntimeTerm.Global(identifier), Type.Integer)
    for (answer <- List(41, 42)) {
      val primitive = PrimitiveValue.Integer(answer)
      val environment = GlobalEnvironment(Map(identifier -> RuntimeTerm.Literal(primitive)))
      assertEquals(LazyEvaluation.evaluate(term, environment), reducePublicly(term, environment))
      assertEquals(LazyEvaluation.evaluate(term, environment), Result.Ok(Value.Primitive(primitive)))
    }
  }

  private def assertAgreement(term: Term, expected: Result[Value, EvaluationError]): Unit = {
    val checked = Fiobs.infer(term) match {
      case Result.Ok(value) => value
      case Result.Err(error) => fail(s"unexpected typing failure: $error")
    }
    val reference = reducePublicly(checked.runtimeTerm)
    assertEquals(reference, expected)
    assertEquals(LazyEvaluation.evaluate(checked.runtimeTerm), reference)
  }

  private def reducePublicly(
    term: RuntimeTerm,
    environment: GlobalEnvironment = GlobalEnvironment.empty
  ): Result[Value, EvaluationError] = {
    @tailrec
    def loop(current: RuntimeTerm, remaining: Int): Result[Value, EvaluationError] = {
      if (current.isReady) current.toValue
      else if (remaining == 0) fail("finite comparison fixture exceeded its reduction budget")
      else LazyEvaluation.step(current, environment) match {
        case Result.Ok(next) => loop(next, remaining - 1)
        case Result.Err(error) => Result.Err(error)
      }
    }
    loop(term, 10000)
  }
}
