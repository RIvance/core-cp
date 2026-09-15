package cp.fiobs.eval

import cp.fiobs.runtime.*
import cp.fiobs.runtime.RuntimeBinding.*
import cp.primitive.PrimitiveValue
import cp.util.Result

import scala.annotation.tailrec

/** Deterministic evaluation under the lazy Fiobs reduction rules. */
object LazyEvaluation {
  @tailrec
  def evaluate(
    term: RuntimeTerm,
    globalEnvironment: GlobalEnvironment = GlobalEnvironment.empty
  ): Result[Value, EvaluationError] = {
    if (term.isReady) {
      // ───────── Steps-Refl
      // r ↪⋆ r
      term.toValue
    } else {
      step(term, globalEnvironment) match {
        // r₁ ↪ r₂    r₂ ↪⋆ r₃
        // ───────────────────── Steps-Step
        // r₁ ↪⋆ r₃
        case Result.Ok(nextTerm) => evaluate(nextTerm, globalEnvironment)
        case Result.Err(error) => Result.Err(error)
      }
    }
  }

  /** Evaluates a term and recursively forces every suspended field in the resulting value. */
  def evaluateFully(
    term: RuntimeTerm,
    globalEnvironment: GlobalEnvironment = GlobalEnvironment.empty
  ): Result[FullyEvaluatedValue, EvaluationError] = {
    evaluate(term, globalEnvironment).flatMap(force(_, globalEnvironment))
  }

  private def force(
    value: Value,
    globalEnvironment: GlobalEnvironment
  ): Result[FullyEvaluatedValue, EvaluationError] = value match {
    case Value.Primitive(primitive) => Result.Ok(FullyEvaluatedValue.Primitive(primitive))
    case Value.Top => Result.Ok(FullyEvaluatedValue.Top)
    case Value.Lambda(parameterType, body, resultType) =>
      Result.Ok(FullyEvaluatedValue.Lambda(parameterType, body, resultType))
    case Value.Merge(left, right) =>
      for {
        evaluatedLeft <- force(left, globalEnvironment)
        evaluatedRight <- force(right, globalEnvironment)
      } yield FullyEvaluatedValue.Merge(evaluatedLeft, evaluatedRight)
    case Value.TypeLambda(disjointBound, body, resultType) =>
      Result.Ok(FullyEvaluatedValue.TypeLambda(disjointBound, body, resultType))
    case Value.Record(label, RecordField.Suspended(field), fieldType) =>
      evaluate(field, globalEnvironment)
        .flatMap(force(_, globalEnvironment))
        .map(FullyEvaluatedValue.Record(label, _, fieldType))
  }

  def step(
    term: RuntimeTerm,
    globalEnvironment: GlobalEnvironment = GlobalEnvironment.empty
  ): Result[RuntimeTerm, EvaluationError] = term match {
    // G(g) = r
    // ───────────────── Step-Global
    // G ⊢ global g ↪ r
    case RuntimeTerm.Global(identifier) =>
      globalEnvironment.lookup(identifier) match {
        case Some(definition) => Result.Ok(definition)
        case None => Result.Err(EvaluationError.UnknownGlobal(identifier))
      }

    // r ⟶[B] r′
    // ───────────── Step-Cast
    // (r : B) ↪ r′
    //
    // r ↪ r′
    // ───────────────── Step-Cast-Cong
    // (r : B) ↪ (r′ : B)
    case RuntimeTerm.Cast(receiver, targetType) =>
      TargetCasting.cast(receiver, targetType) match {
        case Some(castResult) => Result.Ok(castResult)
        case None => step(receiver, globalEnvironment).map(RuntimeTerm.Cast(_, targetType))
      }

    // ───────────────────────────────────────── Step-Fix
    // fix(x : A). r ↪ r[x ↦ fix(x : A). r]
    case fixpoint @ RuntimeTerm.Fix(_, body) => Result.Ok(body.substituteTerm(0, fixpoint))

    // r₁ ↪ r₁′
    // ───────────────── Step-MergeL
    // r₁ ,, r₂ ↪ r₁′ ,, r₂
    //
    // ◉ʳ r₁    r₂ ↪ r₂′
    // ───────────────────── Step-MergeR
    // r₁ ,, r₂ ↪ r₁ ,, r₂′
    case RuntimeTerm.Merge(left, right) if !left.isReady =>
      step(left, globalEnvironment).map(RuntimeTerm.Merge(_, right))
    case RuntimeTerm.Merge(left, right) if !right.isReady =>
      step(right, globalEnvironment).map(RuntimeTerm.Merge(left, _))

    // ───────────────────────────────────── Step-Beta
    // ⟨λx. r⟩^{A ⇾ B} r₂ ↪ r[x ↦ r₂]
    case RuntimeTerm.Application(RuntimeTerm.Lambda(_, body, _), argument) =>
      Result.Ok(body.substituteTerm(0, argument))

    // r₁ ↪ r₁′
    // ───────────────────── Step-AppL
    // r₁ r₂ ↪ r₁′ r₂
    case RuntimeTerm.Application(function, argument) =>
      step(function, globalEnvironment).map(RuntimeTerm.Application(_, argument))

    // ───────────────────────────────────────────── Step-TBeta
    // ⟨Λ(α ∗ A). r⟩^{∀(α ∗ A).B}[C] ↪ r[α ↦ C]
    case RuntimeTerm.TypeApplication(RuntimeTerm.TypeLambda(_, body, _), argumentType) =>
      Result.Ok(body.substituteType(0, argumentType))

    // r ↪ r′
    // ───────────────── Step-TApp
    // r[C] ↪ r′[C]
    case RuntimeTerm.TypeApplication(function, argumentType) =>
      step(function, globalEnvironment).map(RuntimeTerm.TypeApplication(_, argumentType))

    // ───────────────────────────────── Step-Proj-Rcd
    // ⟨{ℓ = r}⟩^{ℓ : A}.ℓ ↪ r
    case RuntimeTerm.Projection(RuntimeTerm.Record(recordLabel, field, _), requestedLabel)
        if recordLabel == requestedLabel => Result.Ok(field)

    // r ↪ r′
    // ───────────────── Step-Proj
    // r.ℓ ↪ r′.ℓ
    case RuntimeTerm.Projection(record, label) =>
      step(record, globalEnvironment).map(RuntimeTerm.Projection(_, label))

    // r₁ ↪ r₁′
    // ───────────────────────────────────────── Step-PrimitiveL
    // r₁ operator r₂ ↪ r₁′ operator r₂
    case RuntimeTerm.Binary(operator, left, right) if !left.isReady =>
      step(left, globalEnvironment).map(RuntimeTerm.Binary(operator, _, right))

    // ◉ʳ r₁    r₂ ↪ r₂′
    // ───────────────────────────────────────── Step-PrimitiveR
    // r₁ operator r₂ ↪ r₁ operator r₂′
    case RuntimeTerm.Binary(operator, left, right) if !right.isReady =>
      step(right, globalEnvironment).map(RuntimeTerm.Binary(operator, left, _))

    // operator(v₁, v₂) = v
    // ───────────────────────── Step-Primitive
    // v₁ operator v₂ ↪ v
    case RuntimeTerm.Binary(operator, RuntimeTerm.Literal(left), RuntimeTerm.Literal(right)) =>
      operator(left, right) match {
        case Result.Ok(value) => Result.Ok(RuntimeTerm.Literal(value))
        case Result.Err(error) => Result.Err(EvaluationError.PrimitiveFailure(error))
      }

    // c ↪ c′
    // ───────────────────────────────────────── Step-If
    // if c then r₁ else r₂ ↪ if c′ then r₁ else r₂
    case RuntimeTerm.If(condition, whenTrue, whenFalse) if !condition.isReady =>
      step(condition, globalEnvironment).map(RuntimeTerm.If(_, whenTrue, whenFalse))

    // ───────────────────────────────── Step-If-True
    // if true then r₁ else r₂ ↪ r₁
    case RuntimeTerm.If(RuntimeTerm.Literal(PrimitiveValue.Boolean(true)), whenTrue, _) =>
      Result.Ok(whenTrue)

    // ───────────────────────────────── Step-If-False
    // if false then r₁ else r₂ ↪ r₂
    case RuntimeTerm.If(RuntimeTerm.Literal(PrimitiveValue.Boolean(false)), _, whenFalse) =>
      Result.Ok(whenFalse)

    case _ => Result.Err(EvaluationError.Stuck(term))
  }
}
