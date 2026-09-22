package cp.fiobs.typing

import cp.fiobs.*

/** Route silence and collision refinement share one contextual analysis object. */
final class RouteAnalysis private (context: TypeContext) {
  private val subtyping = Subtyping(context)

  def isSilent(inputType: Type): Boolean = inputType match {
    // ───────────── Sil-Top
    // Δ ⊢ ⊤ ∅ᵒ
    case Type.Top => true

    // α ∗ A ∈ Δ    Δ ⊢ A <: ⊥
    // ───────────────────────── Sil-Var
    // Δ ⊢ α ∅ᵒ
    case Type.Variable(index) =>
      context.lookup(index).exists(bound => subtyping.relates(bound, Type.Bottom))

    // Δ ⊢ B ∅ᵒ
    // ───────────── Sil-Arr
    // Δ ⊢ A ⇾ B ∅ᵒ
    case Type.Arrow(_, resultType) => isSilent(resultType)

    // Δ ⊢ A ∅ᵒ    Δ ⊢ B ∅ᵒ
    // ───────────────────── Sil-And
    // Δ ⊢ A & B ∅ᵒ
    case Type.Intersection(leftType, rightType) => isSilent(leftType) && isSilent(rightType)

    // Δ, α ∗ A ⊢ B ∅ᵒ
    // ─────────────────── Sil-All
    // Δ ⊢ ∀(α ∗ A). B ∅ᵒ
    case Type.ForAll(disjointBound, bodyType) =>
      RouteAnalysis(context.extend(disjointBound)).isSilent(bodyType)

    // M(μα. A) = ∅
    // ─────────────── Sil-Rec
    // Δ ⊢ μα. A ∅ᵒ
    // M erases universal guards and treats opaque variables conservatively (see PossibleRoutes).
    case Type.Recursive(_) => PossibleRoutes(inputType).isEmpty

    // Δ ⊢ A ∅ᵒ
    // ───────────────── Sil-Rcd
    // Δ ⊢ {ℓ : A} ∅ᵒ
    case Type.Record(_, fieldType) => isSilent(fieldType)

    case Type.Primitive(_) | Type.Bottom => false
  }

  def collisionShapeOf(inputType: Type): Type = inputType match {
    case Type.Primitive(_) | Type.Top | Type.Bottom | Type.Variable(_) => inputType
    case Type.Arrow(_, resultType) => Type.Arrow(Type.Top, collisionShapeOf(resultType))
    case Type.Intersection(leftType, rightType) =>
      Type.Intersection(collisionShapeOf(leftType), collisionShapeOf(rightType))
    case Type.ForAll(disjointBound, bodyType) =>
      Type.ForAll(collisionShapeOf(disjointBound), collisionShapeOf(bodyType))
    // CShape(μα. A) = μα. CShape(A); the binder and its de Bruijn scope are preserved.
    case Type.Recursive(bodyType) => Type.Recursive(collisionShapeOf(bodyType))
    case Type.Record(label, fieldType) => Type.Record(label, collisionShapeOf(fieldType))
  }

  def isRouteSubtype(sourceType: Type, targetType: Type): Boolean = {
    // Δ ⊢ collisionShape(A) <: collisionShape(B)
    // ─────────────────────────────────────────── O-Shape
    // Δ ⊢ A <:ᵒ B
    //
    // Δ ⊢ B ∅ᵒ
    // ─────────── O-Silent
    // Δ ⊢ A <:ᵒ B
    isSilent(targetType) || subtyping.relates(collisionShapeOf(sourceType), collisionShapeOf(targetType))
  }
}

object RouteAnalysis {
  def apply(context: TypeContext): RouteAnalysis = new RouteAnalysis(context)
}
