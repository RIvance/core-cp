package cp.fiobs.runtime

import cp.fiobs.*
import cp.naming.Identifier
import cp.primitive.{BinaryOperator, PrimitiveOperationError, PrimitiveValue}
import cp.util.Result

/** Decorated runtime syntax from Section 7 of the Fiobs specification. */
enum RuntimeTerm {
  case Variable(index: Int)
  case Global(identifier: Identifier)
  case Literal(value: PrimitiveValue)
  case Top
  case Lambda(parameterType: Type, body: RuntimeTerm, resultType: Type)
  case Fix(annotatedType: Type, body: RuntimeTerm)
  case Application(function: RuntimeTerm, argument: RuntimeTerm)
  case Merge(left: RuntimeTerm, right: RuntimeTerm)
  case Cast(term: RuntimeTerm, targetType: Type)
  case TypeLambda(disjointBound: Type, body: RuntimeTerm, resultType: Type)
  case TypeApplication(function: RuntimeTerm, argumentType: Type)
  case Record(label: String, field: RuntimeTerm, fieldType: Type)
  case Projection(record: RuntimeTerm, label: String)
  case Binary(operator: BinaryOperator, left: RuntimeTerm, right: RuntimeTerm)
  case If(condition: RuntimeTerm, whenTrue: RuntimeTerm, whenFalse: RuntimeTerm)

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
    case _ => Result.Err(EvaluationError.InvalidNormalForm(this))
  }
}

enum RecordField {
  case Suspended(term: RuntimeTerm)
}

/** Semantic results keep primitive data in PrimitiveValue instead of flattening it. */
enum Value {
  case Primitive(value: PrimitiveValue)
  case Top
  case Lambda(parameterType: Type, body: RuntimeTerm, resultType: Type)
  case Merge(left: Value, right: Value)
  case TypeLambda(disjointBound: Type, body: RuntimeTerm, resultType: Type)
  case Record(label: String, field: RecordField, fieldType: Type)
}

/** A final Fiobs value whose lazy record fields have been recursively evaluated. */
enum FullyEvaluatedValue {
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
