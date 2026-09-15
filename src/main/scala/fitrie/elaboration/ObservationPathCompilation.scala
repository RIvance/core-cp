package cp.fitrie.elaboration

import cp.fiobs.Type
import cp.fitrie.{ObservationPathInterface, PathVariableIndex, RouteKey}
import cp.naming.FieldLabel

/** Implements the static path-interface compilation judgment `Δ ⊢ A ⇛ₚ 𝒜`. */
private[fitrie] object ObservationPathCompilation {
  def compile(inputType: Type): ObservationPathInterface = inputType match {
    // ───────────────────── Paths-Primitive
    // Δ ⊢ p ⇛ₚ ⟨p⟩
    case Type.Primitive(primitiveType) => ObservationPathInterface.termination(primitiveType)

    // ───────────────── Paths-Top
    // Δ ⊢ ⊤ ⇛ₚ ⟨ε⟩
    case Type.Top => ObservationPathInterface.exactTop

    // ─────────────────── Paths-Bottom
    // Δ ⊢ ⊥ ⇛ₚ ⟨div⟩
    case Type.Bottom => ObservationPathInterface.Divergence

    // α ∗ A ∈ Δ
    // ─────────────── Paths-Variable
    // Δ ⊢ α ⇛ₚ α
    case Type.Variable(index) => ObservationPathInterface.variable(PathVariableIndex(index))

    /*
     * Δ ⊢ A ⇛ₚ 𝒜    Δ ⊢ B ⇛ₚ ℬ
     * ─────────────────────────── Paths-And
     * Δ ⊢ A & B ⇛ₚ 𝒜 ∪ε ℬ
     */
    case Type.Intersection(leftType, rightType) =>
      compile(leftType).prefixGroupedUnion(compile(rightType))

    /*
     * Δ ⊢ B ⇛ₚ ℬ
     * ───────────────────── Paths-Arrow
     * Δ ⊢ A → B ⇛ₚ app · ℬ
     */
    case Type.Arrow(_, resultType) => compile(resultType).prepend(RouteKey.Application)

    /*
     * Δ, α ∗ A ⊢ B ⇛ₚ ℬ
     * ─────────────────────────────── Paths-All
     * Δ ⊢ ∀(α ∗ A).B ⇛ₚ tapp · ℬ
     */
    case Type.ForAll(_, bodyType) => compile(bodyType).prepend(RouteKey.TypeApplication)

    /*
     * Δ ⊢ A ⇛ₚ 𝒜
     * ───────────────────────── Paths-Record
     * Δ ⊢ {ℓ : A} ⇛ₚ projℓ · 𝒜
     */
    case Type.Record(label, fieldType) =>
      compile(fieldType).prepend(RouteKey.Projection(FieldLabel(label)))
  }
}
