package cp.fitrie

import cp.fiobs.{CheckedProgram, Fiobs, Term, Type}
import cp.fiobs.eval.LazyEvaluation
import cp.fiobs.runtime.Value
import cp.fitrie.evaluation.Evaluation
import cp.primitive.{BinaryOperator, PrimitiveType, PrimitiveValue}
import cp.util.Result

class RecursiveCoercionSuite extends munit.FunSuite {
  private def integer(value: Int): Term = Term.Literal(PrimitiveValue.Integer(value))

  private def fields(entries: (String, Term)*): Term = {
    entries.map { case (label, value) => Term.Record(label, value) }.reduce(Term.Merge(_, _))
  }

  private def interface(entries: (String, Type)*): Type = {
    entries.map { case (label, value) => Type.Record(label, value) }.reduce(Type.Intersection(_, _))
  }

  private def select(record: Term, label: String, fieldType: Type): Term = {
    Term.Annotation(Term.Projection(record, label), fieldType)
  }

  private def assertAnswer(term: Term, expected: Int = 42): Unit = {
    val checked = expectSuccess(Fiobs.check(term, Type.Integer))
    assertEquals(
      LazyEvaluation.evaluate(checked.runtimeTerm),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(expected)))
    )
    assertTargetAnswer(checked, expected)
  }

  private def assertTargetAnswer(checked: CheckedProgram, expected: Int): Unit = {
    val compiled = expectSuccess(FiTrieCompiler.compile(checked))
    assert(compiled.targetTrie.isWellScoped())
    assertEquals(
      Evaluation.observeTermination(compiled.targetTrie, PrimitiveType.Integer),
      Result.Ok(PrimitiveValue.Integer(expected))
    )
  }

  test("recursive width coercion converts each successive receiver") {
    val wide = Type.Recursive(interface(
      "value" -> Type.Integer,
      "extra" -> Type.Boolean,
      "next" -> Type.Variable(0)
    ))
    val narrow = Type.Recursive(interface("value" -> Type.Integer, "next" -> Type.Variable(0)))
    val producer = Term.Fix(Type.Arrow(Type.Integer, wide), Term.Lambda(Term.Fold(wide, fields(
      "value" -> Term.Variable(0),
      "extra" -> Term.Literal(PrimitiveValue.Boolean(true)),
      "next" -> Term.Application(Term.Variable(1), Term.Binary(BinaryOperator.Add, Term.Variable(0), integer(1)))
    ))))
    val first = Term.Annotation(Term.Application(producer, integer(40)), narrow)
    val second = select(Term.Unfold(narrow, first), "next", narrow)
    val third = select(Term.Unfold(narrow, second), "next", narrow)
    assertAnswer(select(Term.Unfold(narrow, third), "value", Type.Integer))
  }

  test("recursive coercions remain specialized under a surrounding universal") {
    val wide = Type.Recursive(interface(
      "value" -> Type.Variable(1),
      "extra" -> Type.Boolean,
      "next" -> Type.Variable(0)
    ))
    val narrow = Type.Recursive(interface("value" -> Type.Variable(1), "next" -> Type.Variable(0)))
    val producer = Term.Annotation(
      Term.TypeLambda(Type.Top, Term.Lambda(Term.Annotation(
        Term.Fix(wide, Term.Fold(wide, fields(
          "value" -> Term.Variable(1),
          "extra" -> Term.Literal(PrimitiveValue.Boolean(true)),
          "next" -> Term.Variable(0)
        ))),
        narrow
      ))),
      Type.ForAll(Type.Top, Type.Arrow(Type.Variable(0), narrow))
    )
    val concrete = narrow.substituteType(0, Type.Integer)
    val first = Term.Application(Term.TypeApplication(producer, Type.Integer), integer(42))
    val later = select(Term.Unfold(concrete, first), "next", concrete)
    assertAnswer(select(Term.Unfold(concrete, later), "value", Type.Integer))
  }

  test("recursive coercion follows BCD distribution inside a folded body") {
    val source = Type.Recursive(Type.Intersection(
      Type.Record("field", Type.Record("next", Type.Variable(0))),
      Type.Record("field", Type.Record("value", Type.Integer))
    ))
    val target = Type.Recursive(Type.Record("field", interface(
      "next" -> Type.Variable(0),
      "value" -> Type.Integer
    )))
    val value = Term.Fix(source, Term.Fold(source, Term.Merge(
      Term.Record("field", Term.Record("next", Term.Variable(0))),
      Term.Record("field", Term.Record("value", integer(42)))
    )))
    val converted = Term.Annotation(value, target)
    val fieldType = interface("next" -> target, "value" -> Type.Integer)
    val firstField = select(Term.Unfold(target, converted), "field", fieldType)
    val next = select(firstField, "next", target)
    val nextField = select(Term.Unfold(target, next), "field", fieldType)
    assertAnswer(select(nextField, "value", Type.Integer))
  }

  test("negative recursive positions use the reverse conversion without losing the forward knot") {
    assertAnswer(negativeRecursiveProgram)
  }

  test("target recursive converters preserve both directions through repeated applications") {
    assertTargetAnswer(expectSuccess(Fiobs.check(negativeRecursiveProgram, Type.Integer)), 42)
  }

  test("target reverse recursive converters preserve fresh universal arguments") {
    val sourceResult = Type.Intersection(
      Type.Record("result", Type.Integer),
      Type.Record("result", Type.Variable(0))
    )
    val targetResult = Type.Record("result", Type.Intersection(Type.Integer, Type.Variable(0)))
    def recursive(result: Type): Type = Type.Recursive(Type.ForAll(Type.Integer, Type.Arrow(
      Type.Variable(1),
      Type.Arrow(Type.Integer, Type.Arrow(Type.Variable(0), result))
    )))
    val source = recursive(sourceResult)
    val target = recursive(targetResult)
    val base = Term.Merge(Term.Record("result", integer(42)), Term.Record("result", Term.Variable(0)))
    val recur = Term.Application(Term.Application(Term.Application(
      Term.TypeApplication(Term.Unfold(source, Term.Variable(2)), Type.Variable(0)),
      Term.Variable(3)
    ), Term.Binary(BinaryOperator.Subtract, Term.Variable(1), integer(1))), Term.Variable(0))
    val body = Term.TypeLambda(Type.Integer, Term.Lambda(Term.Lambda(Term.Lambda(Term.If(
      Term.Binary(BinaryOperator.Equal, Term.Variable(1), integer(0)),
      base,
      recur
    )))))
    val converted = Term.Annotation(Term.Fix(source, Term.Fold(source, body)), target)
    val applied = Term.Application(Term.Application(Term.Application(
      Term.TypeApplication(Term.Unfold(target, converted), Type.Boolean),
      converted
    ), integer(2)), Term.Literal(PrimitiveValue.Boolean(true)))
    val observed = Term.If(select(applied, "result", Type.Boolean), integer(42), integer(0))
    assertTargetAnswer(expectSuccess(Fiobs.check(observed, Type.Integer)), 42)
  }

  private def negativeRecursiveProgram: Term = {
    val sourceResult = Type.Intersection(Type.Record("result", Type.Integer), Type.Record("result", Type.Boolean))
    val targetResult = Type.Record("result", Type.Intersection(Type.Integer, Type.Boolean))
    val source = Type.Recursive(Type.Arrow(Type.Variable(0), Type.Arrow(Type.Integer, sourceResult)))
    val target = Type.Recursive(Type.Arrow(Type.Variable(0), Type.Arrow(Type.Integer, targetResult)))
    val base = Term.Merge(
      Term.Record("result", integer(42)),
      Term.Record("result", Term.Literal(PrimitiveValue.Boolean(true)))
    )
    val body = Term.Lambda(Term.Lambda(Term.If(
      Term.Binary(BinaryOperator.Equal, Term.Variable(0), integer(0)),
      base,
      Term.Application(
        Term.Application(Term.Unfold(source, Term.Variable(1)), Term.Variable(2)),
        Term.Binary(BinaryOperator.Subtract, Term.Variable(0), integer(1))
      )
    )))
    val converted = Term.Annotation(Term.Fix(source, Term.Fold(source, body)), target)
    val applied = Term.Application(Term.Application(Term.Unfold(target, converted), converted), integer(2))
    select(applied, "result", Type.Integer)
  }

  private def expectSuccess[T, E](result: Result[T, E]): T = result match {
    case Result.Ok(value) => value
    case Result.Err(error) => fail(s"unexpected error: $error")
  }
}
