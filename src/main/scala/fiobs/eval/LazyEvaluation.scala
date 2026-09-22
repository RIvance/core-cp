package cp.fiobs.eval

import cp.fiobs.Type
import cp.fiobs.runtime.*
import cp.fiobs.runtime.RuntimeBinding.*
import cp.primitive.PrimitiveValue
import cp.util.Result

import scala.annotation.tailrec
import scala.collection.mutable

/** Deterministic evaluation under the lazy Fiobs reduction rules. */
object LazyEvaluation {
  def evaluate(
    term: RuntimeTerm,
    globalEnvironment: GlobalEnvironment = GlobalEnvironment.empty
  ): Result[Value, EvaluationError] = new EvaluationSession(globalEnvironment).evaluate(term)

  /** Evaluates a term and recursively forces its suspended fields and folded payloads. */
  def evaluateFully(
    term: RuntimeTerm,
    globalEnvironment: GlobalEnvironment = GlobalEnvironment.empty
  ): Result[FullyEvaluatedValue, EvaluationError] = new EvaluationSession(globalEnvironment).evaluateFully(term)

  def step(
    term: RuntimeTerm,
    globalEnvironment: GlobalEnvironment = GlobalEnvironment.empty
  ): Result[RuntimeTerm, EvaluationError] = new EvaluationSession(globalEnvironment).step(term)
}

private final class EvaluationSession(globalEnvironment: GlobalEnvironment) {
  // A completed demand is determined by its source syntax, target type, and this
  // immutable environment. The cache never evaluates a receiver ahead of demand,
  // and stores no in-progress result. It lives only for this evaluation, including
  // any requested forcing of suspended fields; returned values do not retain it.
  private val completedCasts = mutable.HashMap.empty[(RuntimeTerm, Type), Result[RuntimeTerm, EvaluationError]]

  /** The public reduction relation takes exactly one principal step and its congruence steps. */
  def step(term: RuntimeTerm): Result[RuntimeTerm, EvaluationError] = reduce(term, step)

  private def advance(term: RuntimeTerm): Result[RuntimeTerm, EvaluationError] = term match {
    case RuntimeTerm.Cast(receiver, targetType) => resolveCast(receiver, targetType)
    case _ => reduce(term, advance)
  }

  // rᵢ ⇏[A] means no target cast exists.
  //
  // r₀ ↪ r₁ ↪ ⋯ ↪ rₙ    ∀ i < n. rᵢ ⇏[A]    rₙ ⟶[A] v
  // ───────────────────────────────────────────────────── Demand-Cast
  // (r₀ : A) ↪⁺ v
  //
  // Target casting inspects introductions and merges. Until this demand succeeds,
  // its outer Cast blocks every enclosing non-Top cast from inspecting the receiver.
  // Resolving it before rebuilding the enclosing context therefore groups existing
  // steps. It never evaluates a merge to readiness before trying its requested cast.
  private def resolveCast(receiver: RuntimeTerm, targetType: Type): Result[RuntimeTerm, EvaluationError] = {
    completedCasts.getOrElseUpdate((receiver, targetType), resolveUncachedCast(receiver, targetType))
  }

  @tailrec
  private def resolveUncachedCast(receiver: RuntimeTerm, targetType: Type): Result[RuntimeTerm, EvaluationError] = {
    TargetCasting.cast(receiver, targetType) match {
      case Some(result) => Result.Ok(result)
      case None => advance(receiver) match {
        case Result.Ok(nextReceiver) => resolveUncachedCast(nextReceiver, targetType)
        case Result.Err(error) => Result.Err(error)
      }
    }
  }

  @tailrec
  def evaluate(term: RuntimeTerm): Result[Value, EvaluationError] = {
    if (term.isReady) {
      // ───────── Steps-Refl
      // r ↪⋆ r
      term.toValue
    } else {
      advance(term) match {
        // r₁ ↪⁺ r₂    r₂ ↪⋆ r₃
        // ────────────────────── Steps-Compose
        // r₁ ↪⋆ r₃
        case Result.Ok(nextTerm) => evaluate(nextTerm)
        case Result.Err(error) => Result.Err(error)
      }
    }
  }

  /** Evaluates a term and recursively forces its suspended fields and folded payloads. */
  def evaluateFully(term: RuntimeTerm): Result[FullyEvaluatedValue, EvaluationError] = {
    evaluate(term).flatMap(force(_))
  }

  private def force(value: Value): Result[FullyEvaluatedValue, EvaluationError] = value match {
    case Value.Primitive(primitive) => Result.Ok(FullyEvaluatedValue.Primitive(primitive))
    case Value.Top => Result.Ok(FullyEvaluatedValue.Top)
    case Value.Fold(recursiveType, body) =>
      evaluateFully(body).map(FullyEvaluatedValue.Fold(recursiveType, _))
    case Value.Lambda(parameterType, body, resultType) =>
      Result.Ok(FullyEvaluatedValue.Lambda(parameterType, body, resultType))
    case Value.Merge(left, right) =>
      for {
        evaluatedLeft <- force(left)
        evaluatedRight <- force(right)
      } yield FullyEvaluatedValue.Merge(evaluatedLeft, evaluatedRight)
    case Value.TypeLambda(disjointBound, body, resultType) =>
      Result.Ok(FullyEvaluatedValue.TypeLambda(disjointBound, body, resultType))
    case Value.Record(label, RecordField.Suspended(field), fieldType) =>
      evaluate(field)
        .flatMap(force(_))
        .map(FullyEvaluatedValue.Record(label, _, fieldType))
  }

  private def reduce(
    term: RuntimeTerm,
    descend: RuntimeTerm => Result[RuntimeTerm, EvaluationError]
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
        case None => descend(receiver).map(RuntimeTerm.Cast(_, targetType))
      }

    // ───────────────────────────────────────── Step-Fix
    // fix(x : A). r ↪ r[x ↦ fix(x : A). r]
    case fixpoint @ RuntimeTerm.Fix(_, body) => Result.Ok(body.substituteTerm(0, fixpoint))

    // ───────────────────────────── Step-Unfold-Fold
    // unfold ⟨fold r⟩ᴿ ↪ r
    case RuntimeTerm.Unfold(RuntimeTerm.Fold(_, body)) => Result.Ok(body)

    // r ↪ r′
    // ───────────────────── Step-Unfold
    // unfold r ↪ unfold r′
    case RuntimeTerm.Unfold(inner) => descend(inner).map(RuntimeTerm.Unfold(_))

    // r₁ ↪ r₁′
    // ───────────────── Step-MergeL
    // r₁ ,, r₂ ↪ r₁′ ,, r₂
    //
    // ◉ʳ r₁    r₂ ↪ r₂′
    // ───────────────────── Step-MergeR
    // r₁ ,, r₂ ↪ r₁ ,, r₂′
    case RuntimeTerm.Merge(left, right) if !left.isReady =>
      descend(left).map(RuntimeTerm.Merge(_, right))
    case RuntimeTerm.Merge(left, right) if !right.isReady =>
      descend(right).map(RuntimeTerm.Merge(left, _))

    // ───────────────────────────────────── Step-Beta
    // ⟨λx. r⟩^{A ⇾ B} r₂ ↪ r[x ↦ r₂]
    case RuntimeTerm.Application(RuntimeTerm.Lambda(_, body, _), argument) =>
      Result.Ok(body.substituteTerm(0, argument))

    // r₁ ↪ r₁′
    // ───────────────────── Step-AppL
    // r₁ r₂ ↪ r₁′ r₂
    case RuntimeTerm.Application(function, argument) =>
      descend(function).map(RuntimeTerm.Application(_, argument))

    // ───────────────────────────────────────────── Step-TBeta
    // ⟨Λ(α ∗ A). r⟩^{∀(α ∗ A).B}[C] ↪ r[α ↦ C]
    case RuntimeTerm.TypeApplication(RuntimeTerm.TypeLambda(_, body, _), argumentType) =>
      Result.Ok(body.substituteType(0, argumentType))

    // r ↪ r′
    // ───────────────── Step-TApp
    // r[C] ↪ r′[C]
    case RuntimeTerm.TypeApplication(function, argumentType) =>
      descend(function).map(RuntimeTerm.TypeApplication(_, argumentType))

    // ───────────────────────────────── Step-Proj-Rcd
    // ⟨{ℓ = r}⟩^{ℓ : A}.ℓ ↪ r
    case RuntimeTerm.Projection(RuntimeTerm.Record(recordLabel, field, _), requestedLabel)
        if recordLabel == requestedLabel => Result.Ok(field)

    // r ↪ r′
    // ───────────────── Step-Proj
    // r.ℓ ↪ r′.ℓ
    case RuntimeTerm.Projection(record, label) =>
      descend(record).map(RuntimeTerm.Projection(_, label))

    // r₁ ↪ r₁′
    // ───────────────────────────────────────── Step-PrimitiveL
    // r₁ operator r₂ ↪ r₁′ operator r₂
    case RuntimeTerm.Binary(operator, left, right) if !left.isReady =>
      descend(left).map(RuntimeTerm.Binary(operator, _, right))

    // ◉ʳ r₁    r₂ ↪ r₂′
    // ───────────────────────────────────────── Step-PrimitiveR
    // r₁ operator r₂ ↪ r₁ operator r₂′
    case RuntimeTerm.Binary(operator, left, right) if !right.isReady =>
      descend(right).map(RuntimeTerm.Binary(operator, left, _))

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
      descend(condition).map(RuntimeTerm.If(_, whenTrue, whenFalse))

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
