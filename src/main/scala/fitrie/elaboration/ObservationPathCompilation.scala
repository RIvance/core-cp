package cp.fitrie.elaboration

import cp.fiobs.Type
import cp.fitrie.{ObservationPathInterface, PathVariableIndex, RecursivePathVariableIndex, RouteKey}
import cp.naming.FieldLabel

/** Implements the static path-interface compilation judgment `Δ ⊢ A ⇛ₚ 𝒜`. */
private[fitrie] object ObservationPathCompilation {
  private enum PathBinder {
    case Polymorphic, Recursive
  }

  def compile(inputType: Type): ObservationPathInterface = compile(inputType, Nil)

  private def compile(inputType: Type, scope: List[PathBinder]): ObservationPathInterface = inputType match {
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
    case Type.Variable(index) => scope.lift(index) match {
      case Some(PathBinder.Recursive) => ObservationPathInterface.RecursiveVariable(
        RecursivePathVariableIndex(scope.take(index).count(_ == PathBinder.Recursive))
      )
      case _ => ObservationPathInterface.variable(
        PathVariableIndex(index - scope.take(index).count(_ == PathBinder.Recursive))
      )
    }

    /*
     * Δ ⊢ A ⇛ₚ 𝒜    Δ ⊢ B ⇛ₚ ℬ
     * ─────────────────────────── Paths-And
     * Δ ⊢ A & B ⇛ₚ 𝒜 ∪ε ℬ
     */
    case Type.Intersection(leftType, rightType) =>
      compile(leftType, scope).prefixGroupedUnion(compile(rightType, scope))

    /*
     * Δ ⊢ B ⇛ₚ ℬ
     * ───────────────────── Paths-Arrow
     * Δ ⊢ A → B ⇛ₚ app · ℬ
     */
    case Type.Arrow(_, resultType) => compile(resultType, scope).prepend(RouteKey.Application)

    /*
     * Δ, α ∗ A ⊢ B ⇛ₚ ℬ
     * ─────────────────────────────── Paths-All
     * Δ ⊢ ∀(α ∗ A).B ⇛ₚ tapp · ℬ
     */
    case Type.ForAll(_, bodyType) =>
      compile(bodyType, PathBinder.Polymorphic :: scope).prepend(RouteKey.TypeApplication)

    // Δ, α ; β ⊢ A ⇛ₚ 𝒜
    // ─────────────────────────────────── Paths-Rec
    // Δ ⊢ μ α. A ⇛ₚ μ β. unfold · 𝒜
    // Source indices share a scope; recursive and polymorphic path indices do not.
    case Type.Recursive(bodyType) =>
      ObservationPathInterface.Recursive(compile(bodyType, PathBinder.Recursive :: scope))

    /*
     * Δ ⊢ A ⇛ₚ 𝒜
     * ───────────────────────── Paths-Record
     * Δ ⊢ {ℓ : A} ⇛ₚ projℓ · 𝒜
     */
    case Type.Record(label, fieldType) =>
      compile(fieldType, scope).prepend(RouteKey.Projection(FieldLabel(label)))
  }
}
