package cp.fiobs

import cp.fiobs.eval.LazyEvaluation
import cp.fiobs.runtime.{FullyEvaluatedValue, GlobalEnvironment, RecordField, RuntimeTerm, Value}
import cp.fiobs.typing.{Disjointness, TypeError}
import cp.naming.Namespace
import cp.primitive.{BinaryOperator, PrimitiveOperationError, PrimitiveValue}
import cp.util.Result

/** Behavioral coverage for the surface-to-runtime Fiobs pipeline. */
class FiobsSuite extends munit.FunSuite {
  private val integerOne = Expr.Literal(PrimitiveValue.Integer(1))

  test("named term variables lower to de Bruijn indices with shadowing") {
    val expression = Expr.Lambda("value", Expr.Lambda("value", Expr.Variable("value")))

    assertEquals(
      expression.toTerm,
      Result.Ok(Term.Lambda(Term.Lambda(Term.Variable(0))))
    )
  }

  test("unbound named variables produce a structured elaboration error") {
    val result = Expr.Variable("missing").toTerm

    assertEquals(
      result,
      Result.Err(elaboration.ElaborationError.UnboundTermVariable("missing", Nil))
    )
  }

  test("global variables retain complete identifiers through lowering and evaluation") {
    val identifier = Namespace("Library").identifier("answer")
    val expression = Expr.Global(identifier)

    assertEquals(expression.toTerm, Result.Ok(Term.Global(identifier)))
    Fiobs.compile(expression, Map(identifier -> Type.Integer)) match {
      case Result.Ok(program) =>
        val environment = GlobalEnvironment(Map(
          identifier -> RuntimeTerm.Literal(PrimitiveValue.Integer(42))
        ))
        assertEquals(
          LazyEvaluation.evaluate(program.runtimeTerm, environment),
          Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
        )
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("a global without a type signature is a structured typing error") {
    val identifier = Namespace("Library").identifier("missing")

    assertEquals(
      Fiobs.compile(Expr.Global(identifier)),
      Result.Err(CompilationError.Typing(TypeError.UnboundGlobal(identifier)))
    )
  }

  test("primitive overload errors preserve the signature, operand, and underlying failure") {
    val missing = Namespace("Library").identifier("missing")
    val one = Term.Literal(PrimitiveValue.Integer(1))
    List(
      Term.Binary(BinaryOperator.Add, Term.Global(missing), one),
      Term.Binary(BinaryOperator.Add, one, Term.Global(missing))
    ).foreach { term =>
      Fiobs.infer(term) match {
        case Result.Err(TypeError.NoPrimitiveSignature(BinaryOperator.Add, _, candidates)) =>
          assert(candidates.nonEmpty)
          assert(candidates.exists(_.cause == TypeError.UnboundGlobal(missing)))
          assertEquals(candidates.map(_.signature), BinaryOperator.Add.signatures)
        case other => fail(s"expected structured candidate failures, received: $other")
      }
    }
  }

  test("primitive operators own typing signatures and evaluation") {
    val expressions = List(
      Expr.Binary(
        BinaryOperator.Add,
        Expr.Literal(PrimitiveValue.Integer(20)),
        Expr.Literal(PrimitiveValue.Integer(22))
      ) -> Value.Primitive(PrimitiveValue.Integer(42)),
      Expr.Binary(
        BinaryOperator.And,
        Expr.Literal(PrimitiveValue.Boolean(true)),
        Expr.Literal(PrimitiveValue.Boolean(false))
      ) -> Value.Primitive(PrimitiveValue.Boolean(false)),
      Expr.Binary(
        BinaryOperator.Concatenate,
        Expr.Literal(PrimitiveValue.Text("fio")),
        Expr.Literal(PrimitiveValue.Text("bs"))
      ) -> Value.Primitive(PrimitiveValue.Text("fiobs"))
    )

    expressions.foreach { case (expression, expectedValue) =>
      assertEquals(Fiobs.evaluate(expression), Result.Ok(expectedValue))
    }
  }

  test("merge typing uses contextual disjointness") {
    val accepted = Expr.Merge(
      Expr.Literal(PrimitiveValue.Integer(1)),
      Expr.Literal(PrimitiveValue.Boolean(true))
    )
    val rejected = Expr.Merge(
      Expr.Literal(PrimitiveValue.Integer(1)),
      Expr.Literal(PrimitiveValue.Integer(2))
    )

    assertEquals(
      Fiobs.compile(accepted).map(_.programType),
      Result.Ok(Type.Intersection(Type.Integer, Type.Boolean))
    )
    Fiobs.compile(rejected) match {
      case Result.Err(CompilationError.Typing(TypeError.TypesAreNotDisjoint(Type.Integer, Type.Integer))) => ()
      case other => fail(s"expected a disjointness error, found $other")
    }
  }

  test("BCD applicative distribution exposes all function contributors") {
    val functionIntersection = Type.Intersection(
      Type.Arrow(Type.Integer, Type.Integer),
      Type.Arrow(Type.Integer, Type.Boolean)
    )

    assertEquals(
      functionIntersection.applicativeView(ApplicableForm.Arrow),
      Some(Type.Arrow(
        Type.Intersection(Type.Integer, Type.Integer),
        Type.Intersection(Type.Integer, Type.Boolean)
      ))
    )
  }

  test("distributed function merge broadcasts one argument to both functions") {
    val integerFunction = Expr.Annotation(
      Expr.Lambda("argument", Expr.Literal(PrimitiveValue.Integer(1))),
      SurfaceType.Arrow(SurfaceType.Integer, SurfaceType.Integer)
    )
    val booleanFunction = Expr.Annotation(
      Expr.Lambda("argument", Expr.Literal(PrimitiveValue.Boolean(true))),
      SurfaceType.Arrow(SurfaceType.Integer, SurfaceType.Boolean)
    )
    val application = Expr.Application(
      Expr.Merge(integerFunction, booleanFunction),
      Expr.Literal(PrimitiveValue.Integer(0))
    )
    val expected = Value.Merge(
      Value.Primitive(PrimitiveValue.Integer(1)),
      Value.Primitive(PrimitiveValue.Boolean(true))
    )

    assertEquals(Fiobs.evaluate(application), Result.Ok(expected))
  }

  test("distributed universal merge broadcasts one type argument") {
    val firstUniversal = Expr.Annotation(
      Expr.TypeLambda("Element", SurfaceType.Top, Expr.Literal(PrimitiveValue.Integer(1))),
      SurfaceType.ForAll("Element", SurfaceType.Top, SurfaceType.Integer)
    )
    val secondUniversal = Expr.Annotation(
      Expr.TypeLambda("Element", SurfaceType.Top, Expr.Literal(PrimitiveValue.Boolean(true))),
      SurfaceType.ForAll("Element", SurfaceType.Top, SurfaceType.Boolean)
    )
    val typeApplication = Expr.TypeApplication(
      Expr.Merge(firstUniversal, secondUniversal),
      SurfaceType.Integer
    )
    val expected = Value.Merge(
      Value.Primitive(PrimitiveValue.Integer(1)),
      Value.Primitive(PrimitiveValue.Boolean(true))
    )

    assertEquals(Fiobs.evaluate(typeApplication), Result.Ok(expected))
  }

  test("same-label record distribution merges field observations") {
    val recordMerge = Expr.Merge(
      Expr.Record("field", Expr.Literal(PrimitiveValue.Integer(1))),
      Expr.Record("field", Expr.Literal(PrimitiveValue.Boolean(true)))
    )
    val fieldObservation = Expr.Annotation(
      Expr.Projection(recordMerge, "field"),
      SurfaceType.Intersection(SurfaceType.Integer, SurfaceType.Boolean)
    )
    val expected = Value.Merge(
      Value.Primitive(PrimitiveValue.Integer(1)),
      Value.Primitive(PrimitiveValue.Boolean(true))
    )

    assertEquals(Fiobs.evaluate(fieldObservation), Result.Ok(expected))
  }

  test("term application works through checked lambda syntax") {
    val identity = Expr.Annotation(
      Expr.Lambda("value", Expr.Variable("value")),
      SurfaceType.Arrow(SurfaceType.Integer, SurfaceType.Integer)
    )
    val application = Expr.Application(identity, Expr.Literal(PrimitiveValue.Integer(42)))
    val expected = Value.Primitive(PrimitiveValue.Integer(42))

    assertEquals(Fiobs.evaluate(application), Result.Ok(expected))
  }

  test("type abstraction and type application preserve de Bruijn type binding") {
    val identityType = SurfaceType.ForAll(
      "Element",
      SurfaceType.Top,
      SurfaceType.Arrow(
        SurfaceType.Variable("Element"),
        SurfaceType.Variable("Element")
      )
    )
    val polymorphicIdentity = Expr.Annotation(
      Expr.TypeLambda(
        "Element",
        SurfaceType.Top,
        Expr.Lambda("value", Expr.Variable("value"))
      ),
      identityType
    )
    val application = Expr.Application(
      Expr.TypeApplication(polymorphicIdentity, SurfaceType.Integer),
      Expr.Literal(PrimitiveValue.Integer(9))
    )
    val expected = Value.Primitive(PrimitiveValue.Integer(9))

    assertEquals(Fiobs.evaluate(application), Result.Ok(expected))
  }

  test("type application enforces the paper's disjointness premise") {
    val universal = Expr.Annotation(
      Expr.TypeLambda("Element", SurfaceType.Integer, integerOne),
      SurfaceType.ForAll("Element", SurfaceType.Integer, SurfaceType.Integer)
    )
    val invalidApplication = Expr.TypeApplication(universal, SurfaceType.Integer)

    Fiobs.compile(invalidApplication) match {
      case Result.Err(CompilationError.Typing(TypeError.TypeArgumentViolatesBound(
            Type.Integer,
            Type.Integer
          ))) => ()
      case other => fail(s"expected T-TApp disjointness rejection, found $other")
    }
  }

  test("projection is observation-directed under lazy evaluation") {
    val projection = Expr.Annotation(
      Expr.Projection(Expr.Record("answer", Expr.Literal(PrimitiveValue.Integer(42))), "answer"),
      SurfaceType.Integer
    )
    val expected = Value.Primitive(PrimitiveValue.Integer(42))

    assertEquals(Fiobs.evaluate(projection), Result.Ok(expected))
  }

  test("lazy casting can skip a divergent unrelated merge contributor") {
    val recordType = SurfaceType.Record("field", SurfaceType.Integer)
    val divergentRecord = Expr.Fix("loop", recordType, Expr.Variable("loop"))
    val observation = Expr.Annotation(
      Expr.Merge(integerOne, divergentRecord),
      SurfaceType.Integer
    )

    assertEquals(
      Fiobs.evaluate(observation),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(1)))
    )
  }

  test("disjointness treats its operands as symmetric peers") {
    val relation = Disjointness(TypeContext.empty)

    assert(relation.relates(Type.Integer, Type.Boolean))
    assert(relation.relates(Type.Boolean, Type.Integer))
    assert(!relation.relates(Type.Integer, Type.Integer))
  }

  test("subtyping includes variance, exact top, bottom, and positive splitting") {
    assert(Type.Arrow(Type.Top, Type.Integer).isSubtypeOf(Type.Arrow(Type.Integer, Type.Top)))
    assert(!Type.Arrow(Type.Integer, Type.Integer).isSubtypeOf(Type.Arrow(Type.Top, Type.Integer)))
    assert(Type.Bottom.isSubtypeOf(Type.Record("field", Type.Integer)))
    assert(Type.Integer.isSubtypeOf(Type.Intersection(Type.Integer, Type.Top)))
    assert(!Type.Top.isSubtypeOf(Type.Arrow(Type.Integer, Type.Top)))
  }

  test("route silence distinguishes exact top from exact bottom") {
    assert(Type.Top.isSilent())
    assert(Type.Arrow(Type.Integer, Type.Top).isSilent())
    assert(!Type.Bottom.isSilent())
    assert(!Type.Arrow(Type.Integer, Type.Bottom).isSilent())
  }

  test("top is the intersection identity for primitive and structured interfaces") {
    List(
      Type.Integer,
      Type.Top,
      Type.Bottom,
      Type.Arrow(Type.Integer, Type.Top),
      Type.ForAll(Type.Bottom, Type.Variable(0)),
      Type.Record("field", Type.Integer)
    ).foreach { inputType =>
      List(Type.Intersection(inputType, Type.Top), Type.Intersection(Type.Top, inputType)).foreach { withTop =>
        assert(inputType.isSubtypeOf(withTop))
        assert(withTop.isSubtypeOf(inputType))
      }
      assert(Disjointness(TypeContext.empty).relates(inputType, Type.Top))
      assert(Disjointness(TypeContext.empty).relates(Type.Top, inputType))
    }
  }

  test("bottom bounds establish route silence while top bounds remain unrestricted") {
    val silentContext = TypeContext.empty.extend(Type.Bottom)
    val unrestrictedContext = TypeContext.empty.extend(Type.Top)
    assert(Type.Variable(0).isSilent(silentContext))
    assert(!Type.Variable(0).isSilent(unrestrictedContext))
    assert(Disjointness(silentContext).relates(Type.Variable(0), Type.Integer))
    assert(!Disjointness(unrestrictedContext).relates(Type.Variable(0), Type.Integer))
    assert(!Type.Top.isSubtypeOf(Type.Arrow(Type.Integer, Type.Top)))
    assert(!Type.Top.isSubtypeOf(Type.Record("field", Type.Top)))
    assert(!Type.Top.isSubtypeOf(Type.ForAll(Type.Top, Type.Top)))
  }

  test("repeated higher-order intersection casts preserve captured arguments") {
    val functionType = SurfaceType.Arrow(
      SurfaceType.Integer,
      SurfaceType.Arrow(SurfaceType.Integer, SurfaceType.Intersection(SurfaceType.Integer, SurfaceType.Top))
    )
    val function = Expr.Annotation(
      Expr.Lambda("left", Expr.Lambda("right", Expr.Binary(
        BinaryOperator.Add, Expr.Variable("left"), Expr.Variable("right")
      ))),
      functionType
    )
    List(0, 3, 6).foreach { repetitions =>
      val castFunction = (0 until repetitions).foldLeft(function: Expr) { (term, _) =>
        Expr.Annotation(term, functionType)
      }
      val result = Expr.Annotation(
        Expr.Application(
          Expr.Application(castFunction, Expr.Literal(PrimitiveValue.Integer(20))),
          Expr.Literal(PrimitiveValue.Integer(22))
        ),
        SurfaceType.Integer
      )
      assertEquals(Fiobs.evaluate(result), Result.Ok(Value.Primitive(PrimitiveValue.Integer(42))))
    }
  }

  test("primitive failures remain structured in pure evaluation logic") {
    val divisionByZero = Expr.Binary(
      BinaryOperator.Divide,
      Expr.Literal(PrimitiveValue.Integer(1)),
      Expr.Literal(PrimitiveValue.Integer(0))
    )

    Fiobs.evaluate(divisionByZero) match {
      case Result.Err(ProgramError.Evaluation(runtime.EvaluationError.PrimitiveFailure(
            PrimitiveOperationError.DivisionByZero(BinaryOperator.Divide)
          ))) => ()
      case other => fail(s"expected a structured division-by-zero error, found $other")
    }
  }

  test("lazy records suspend their fields") {
    val divergentField = Expr.Fix("loop", SurfaceType.Integer, Expr.Variable("loop"))
    val record = Expr.Record("field", divergentField)

    Fiobs.evaluate(record) match {
      case Result.Ok(Value.Record("field", RecordField.Suspended(_), Type.Integer)) => ()
      case other => fail(s"expected a lazy record with a suspended field, found $other")
    }
  }

  test("fully evaluated values recursively force lazy record fields") {
    val nestedRecord = Expr.Record("outer", Expr.Record("inner", integerOne))

    Fiobs.compile(nestedRecord) match {
      case Result.Ok(program) =>
        assertEquals(
          LazyEvaluation.evaluateFully(program.runtimeTerm),
          Result.Ok(FullyEvaluatedValue.Record(
            "outer",
            FullyEvaluatedValue.Record(
              "inner",
              FullyEvaluatedValue.Primitive(PrimitiveValue.Integer(1)),
              Type.Integer
            ),
            Type.Record("inner", Type.Integer)
          ))
        )
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("call-by-name beta can discard a divergent argument") {
    val constantFunction = Expr.Annotation(
      Expr.Lambda("ignored", integerOne),
      SurfaceType.Arrow(SurfaceType.Integer, SurfaceType.Integer)
    )
    val divergentArgument = Expr.Fix("loop", SurfaceType.Integer, Expr.Variable("loop"))
    val application = Expr.Application(constantFunction, divergentArgument)

    assertEquals(
      Fiobs.evaluate(application),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(1)))
    )
  }

}
