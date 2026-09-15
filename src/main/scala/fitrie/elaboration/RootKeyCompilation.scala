package cp.fitrie.elaboration

import cp.fiobs.Type
import cp.fitrie.*
import cp.naming.FieldLabel

/** Implements the static shallow-key compilation judgment `Δ ⊢ A ⇛ₖ 𝒦`. */
private[fitrie] object RootKeyCompilation {
  def compile(inputType: Type): RootKeyExpression = inputType match {
    // ───────────────── Keys-Primitive
    // Δ ⊢ p ⇛ₖ ⟨p⟩
    case Type.Primitive(primitiveType) =>
      RootKeyExpression.concrete(RootKeySet.one(RootKey.Termination(primitiveType)))

    // ───────────── Keys-Top
    // Δ ⊢ ⊤ ⇛ₖ ∅
    case Type.Top => RootKeyExpression.concrete(RootKeySet.empty)

    // ────────────── Keys-Bottom
    // Δ ⊢ ⊥ ⇛ₖ 𝕂
    case Type.Bottom => RootKeyExpression.concrete(RootKeySet.Universal)

    // α ∗ A ∈ Δ
    // ───────────── Keys-Variable
    // Δ ⊢ α ⇛ₖ α•
    case Type.Variable(index) => RootKeyExpression.front(
      ObservationPathInterface.variable(PathVariableIndex(index))
    )

    /*
     * Δ ⊢ A ⇛ₖ 𝒦₁    Δ ⊢ B ⇛ₖ 𝒦₂
     * ──────────────────────── Keys-And
     * Δ ⊢ A & B ⇛ₖ 𝒦₁ ∪ 𝒦₂
     */
    case Type.Intersection(leftType, rightType) => RootKeyExpression.union(
      compile(leftType),
      compile(rightType)
    )

    // ─────────────── Keys-Arrow
    // Δ ⊢ A → B ⇛ₖ ⟨app⟩
    case Type.Arrow(_, _) =>
      RootKeyExpression.concrete(RootKeySet.one(RouteKey.Application.rootKey))

    // ───────────────── Keys-All
    // Δ ⊢ ∀(α ∗ A).B ⇛ₖ ⟨tapp⟩
    case Type.ForAll(_, _) =>
      RootKeyExpression.concrete(RootKeySet.one(RouteKey.TypeApplication.rootKey))

    // ──────────────── Keys-Record
    // Δ ⊢ {ℓ : A} ⇛ₖ ⟨projℓ⟩
    case Type.Record(label, _) => RootKeyExpression.concrete(
      RootKeySet.one(RouteKey.Projection(FieldLabel(label)).rootKey)
    )
  }
}
