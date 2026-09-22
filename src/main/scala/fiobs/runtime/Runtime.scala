package cp.fiobs.runtime

import cp.fiobs.*
import cp.naming.Identifier
import cp.primitive.{BinaryOperator, PrimitiveOperationError, PrimitiveValue}
import cp.util.Result

import scala.util.hashing.MurmurHash3

/** Decorated runtime syntax from Section 7 of the Fiobs specification. */
enum RuntimeTerm {
  case Variable(index: Int)
  case Global(identifier: Identifier)
  case Literal(value: PrimitiveValue)
  case Top
  case Lambda(parameterType: Type, body: RuntimeTerm, resultType: Type)
  case Fix(annotatedType: Type, body: RuntimeTerm)
  case Fold(recursiveType: Type, body: RuntimeTerm)
  case Unfold(term: RuntimeTerm)
  case Application(function: RuntimeTerm, argument: RuntimeTerm)
  case Merge(left: RuntimeTerm, right: RuntimeTerm)
  case Cast(term: RuntimeTerm, targetType: Type)
  case TypeLambda(disjointBound: Type, body: RuntimeTerm, resultType: Type)
  case TypeApplication(function: RuntimeTerm, argumentType: Type)
  case Record(label: String, field: RuntimeTerm, fieldType: Type)
  case Projection(record: RuntimeTerm, label: String)
  case Binary(operator: BinaryOperator, left: RuntimeTerm, right: RuntimeTerm)
  case If(condition: RuntimeTerm, whenTrue: RuntimeTerm, whenFalse: RuntimeTerm)

  // Cast construction shares syntax. Structural hash lookup must traverse each
  // immutable node once rather than expand that shared graph into a tree.
  private lazy val structuralHash: Int = MurmurHash3.productHash(this)

  final override def hashCode(): Int = structuralHash

  /**
   * The least ambient term-binding depth containing every free term index.
   * A variable i requires i + 1 bindings; Lambda and Fix discharge one binding.
   * Type binders do not discharge term bindings. Zero therefore means term-closed.
   *
   * The immutable syntax may share large closed subtrees between cast wrappers.
   * Caching this intrinsic quantity lets binding operations preserve those
   * subtrees without repeatedly traversing or rebuilding them.
   */
  private[runtime] lazy val requiredTermDepth: Long = this match {
    case RuntimeTerm.Variable(index) => index.toLong + 1
    case RuntimeTerm.Global(_) | RuntimeTerm.Literal(_) | RuntimeTerm.Top => 0
    case RuntimeTerm.Lambda(_, body, _) => math.max(0, body.requiredTermDepth - 1)
    case RuntimeTerm.Fix(_, body) => math.max(0, body.requiredTermDepth - 1)
    case RuntimeTerm.Fold(_, body) => body.requiredTermDepth
    case RuntimeTerm.Unfold(inner) => inner.requiredTermDepth
    case RuntimeTerm.Application(function, argument) =>
      math.max(function.requiredTermDepth, argument.requiredTermDepth)
    case RuntimeTerm.Merge(left, right) => math.max(left.requiredTermDepth, right.requiredTermDepth)
    case RuntimeTerm.Cast(inner, _) => inner.requiredTermDepth
    case RuntimeTerm.TypeLambda(_, body, _) => body.requiredTermDepth
    case RuntimeTerm.TypeApplication(function, _) => function.requiredTermDepth
    case RuntimeTerm.Record(_, field, _) => field.requiredTermDepth
    case RuntimeTerm.Projection(record, _) => record.requiredTermDepth
    case RuntimeTerm.Binary(_, left, right) => math.max(left.requiredTermDepth, right.requiredTermDepth)
    case RuntimeTerm.If(condition, whenTrue, whenFalse) =>
      math.max(condition.requiredTermDepth, math.max(whenTrue.requiredTermDepth, whenFalse.requiredTermDepth))
  }

  /** The corresponding ambient type-binding depth, including every stored interface. */
  private[runtime] lazy val requiredTypeDepth: Long = this match {
    case RuntimeTerm.Variable(_) | RuntimeTerm.Global(_) | RuntimeTerm.Literal(_) | RuntimeTerm.Top => 0
    case RuntimeTerm.Lambda(parameterType, body, resultType) =>
      math.max(parameterType.requiredTypeDepth, math.max(body.requiredTypeDepth, resultType.requiredTypeDepth))
    case RuntimeTerm.Fix(annotatedType, body) => math.max(annotatedType.requiredTypeDepth, body.requiredTypeDepth)
    case RuntimeTerm.Fold(recursiveType, body) => math.max(recursiveType.requiredTypeDepth, body.requiredTypeDepth)
    case RuntimeTerm.Unfold(inner) => inner.requiredTypeDepth
    case RuntimeTerm.Application(function, argument) =>
      math.max(function.requiredTypeDepth, argument.requiredTypeDepth)
    case RuntimeTerm.Merge(left, right) => math.max(left.requiredTypeDepth, right.requiredTypeDepth)
    case RuntimeTerm.Cast(inner, targetType) => math.max(inner.requiredTypeDepth, targetType.requiredTypeDepth)
    case RuntimeTerm.TypeLambda(disjointBound, body, resultType) =>
      math.max(disjointBound.requiredTypeDepth, math.max(body.requiredTypeDepth, resultType.requiredTypeDepth) - 1)
    case RuntimeTerm.TypeApplication(function, argumentType) =>
      math.max(function.requiredTypeDepth, argumentType.requiredTypeDepth)
    case RuntimeTerm.Record(_, field, fieldType) => math.max(field.requiredTypeDepth, fieldType.requiredTypeDepth)
    case RuntimeTerm.Projection(record, _) => record.requiredTypeDepth
    case RuntimeTerm.Binary(_, left, right) => math.max(left.requiredTypeDepth, right.requiredTypeDepth)
    case RuntimeTerm.If(condition, whenTrue, whenFalse) =>
      math.max(condition.requiredTypeDepth, math.max(whenTrue.requiredTypeDepth, whenFalse.requiredTypeDepth))
  }

  def isReady: Boolean = this match {
    // ───────────── Ready-Primitive
    // ◉ʳ literal
    //
    // ───────── Ready-Top
    // ◉ʳ top
    //
    // ───────────────────────── Ready-Lam
    // ◉ʳ ⟨λx. r⟩^{A ⇾ B}
    //
    // ───────────────────────────────── Ready-TLam
    // ◉ʳ ⟨Λ(α ∗ A). r⟩^{∀(α ∗ A).B}
    case RuntimeTerm.Literal(_) | RuntimeTerm.Top | RuntimeTerm.Lambda(_, _, _) |
         RuntimeTerm.TypeLambda(_, _, _) => true

    // ◉ʳ r₁    ◉ʳ r₂
    // ───────────────────── Ready-Merge
    // ◉ʳ (r₁ ,, r₂)
    case RuntimeTerm.Merge(left, right) => left.isReady && right.isReady

    // ───────────────────────── Ready-Rcd
    // ◉ʳ ⟨{ℓ = r}⟩^{ℓ : A}
    case RuntimeTerm.Record(_, _, _) => true

    // ───────────────── Ready-Fold
    // ◉ʳ ⟨fold r⟩ᴿ
    case RuntimeTerm.Fold(_, _) => true
    case _ => false
  }

  def toValue: Result[Value, EvaluationError] = this match {
    case RuntimeTerm.Literal(value) => Result.Ok(Value.Primitive(value))
    case RuntimeTerm.Top => Result.Ok(Value.Top)
    case RuntimeTerm.Lambda(parameterType, body, resultType) =>
      Result.Ok(Value.Lambda(parameterType, body, resultType))
    case RuntimeTerm.TypeLambda(disjointBound, body, resultType) =>
      Result.Ok(Value.TypeLambda(disjointBound, body, resultType))
    case RuntimeTerm.Merge(left, right) =>
      for {
        leftValue <- left.toValue
        rightValue <- right.toValue
      } yield Value.Merge(leftValue, rightValue)
    case RuntimeTerm.Record(label, field, fieldType) =>
      Result.Ok(Value.Record(label, RecordField.Suspended(field), fieldType))
    case RuntimeTerm.Fold(recursiveType, body) => Result.Ok(Value.Fold(recursiveType, body))
    case _ => Result.Err(EvaluationError.InvalidNormalForm(this))
  }
}

enum RecordField {
  case Suspended(term: RuntimeTerm)
}

/** Semantic results keep primitive data in PrimitiveValue instead of flattening it. */
enum Value {
  /** The payload is suspended, just like a record field. */
  case Fold(recursiveType: Type, body: RuntimeTerm)
  case Primitive(value: PrimitiveValue)
  case Top
  case Lambda(parameterType: Type, body: RuntimeTerm, resultType: Type)
  case Merge(left: Value, right: Value)
  case TypeLambda(disjointBound: Type, body: RuntimeTerm, resultType: Type)
  case Record(label: String, field: RecordField, fieldType: Type)
}

/** A final Fiobs value whose lazy record fields and folded payloads have been recursively evaluated. */
enum FullyEvaluatedValue {
  case Fold(recursiveType: Type, body: FullyEvaluatedValue)
  case Primitive(value: PrimitiveValue)
  case Top
  case Lambda(parameterType: Type, body: RuntimeTerm, resultType: Type)
  case Merge(left: FullyEvaluatedValue, right: FullyEvaluatedValue)
  case TypeLambda(disjointBound: Type, body: RuntimeTerm, resultType: Type)
  case Record(label: String, field: FullyEvaluatedValue, fieldType: Type)
}

enum EvaluationError {
  case Stuck(term: RuntimeTerm)
  case UnknownGlobal(identifier: Identifier)
  case PrimitiveFailure(error: PrimitiveOperationError)
  case InvalidNormalForm(term: RuntimeTerm)
}

/** Immutable runtime definitions addressed by complete module-level identities. */
final case class GlobalEnvironment(definitions: Map[Identifier, RuntimeTerm]) {
  def lookup(identifier: Identifier): Option[RuntimeTerm] = definitions.get(identifier)

  def contains(identifier: Identifier): Boolean = definitions.contains(identifier)
}

object GlobalEnvironment {
  val empty: GlobalEnvironment = GlobalEnvironment(Map.empty)
}
