package cp.fiobs.typing

import cp.fiobs.*

/** Canonical applicative distribution for term or type application. */
final class ApplicativeDistribution private(applicableForm: ApplicableForm) {
  def viewOf(inputType: Type): Option[Type] = (inputType, applicableForm) match {
    // ───────────── AD-Primitive
    // p ▹[κ] ⊤
    //
    // ───────── AD-Top
    // ⊤ ▹[κ] ⊤
    //
    // ───────────── AD-Rcd
    // {ℓ : A} ▹[κ] ⊤
    case (Type.Primitive(_), _) | (Type.Top, _) | (Type.Record(_, _), _) => Some(Type.Top)

    // There is no applicative-distribution rule for ⊥ or an opaque α.
    case (Type.Bottom | Type.Variable(_), _) => None

    // ───────────────── AD-Arr
    // A ⇾ B ▹ᵃ A ⇾ B
    case (arrowType@Type.Arrow(_, _), ApplicableForm.Arrow) => Some(arrowType)

    // ───────────── AD-Arr-Top
    // A ⇾ B ▹ᵘ ⊤
    case (Type.Arrow(_, _), ApplicableForm.Universal) => Some(Type.Top)

    // ───────────────────────────── AD-All
    // ∀(α ∗ A). B ▹ᵘ ∀(α ∗ A). B
    case (universalType@Type.ForAll(_, _), ApplicableForm.Universal) => Some(universalType)

    // ───────────────────────── AD-All-Top
    // ∀(α ∗ A). B ▹ᵃ ⊤
    case (Type.ForAll(_, _), ApplicableForm.Arrow) => Some(Type.Top)

    case (Type.Intersection(leftType, rightType), form) =>
      (viewOf(leftType), viewOf(rightType)) match {
        // F₁ ▹[κ] ⊤    F₂ ▹[κ] ⊤
        // ───────────────────── AD-And-Top
        // F₁ & F₂ ▹[κ] ⊤
        case (Some(Type.Top), Some(Type.Top)) => Some(Type.Top)

        // F₁ ▹ᵘ ∀(α ∗ A). B    F₂ ▹ᵘ ⊤
        // ───────────────────────────── AD-And-Left
        // F₁ & F₂ ▹ᵘ ∀(α ∗ A). B
        //
        // F₁ ▹ᵃ A ⇾ B    F₂ ▹ᵃ ⊤
        // ─────────────────────── AD-And-Arr-Left
        // F₁ & F₂ ▹ᵃ A ⇾ B
        case (Some(distributedType), Some(Type.Top)) => Some(distributedType)

        // F₁ ▹ᵘ ⊤    F₂ ▹ᵘ ∀(α ∗ A). B
        // ────────────────────────────── AD-And-Right
        // F₁ & F₂ ▹ᵘ ∀(α ∗ A). B
        //
        // F₁ ▹ᵃ ⊤    F₂ ▹ᵃ A ⇾ B
        // ──────────────────────── AD-And-Arr-Right
        // F₁ & F₂ ▹ᵃ A ⇾ B
        case (Some(Type.Top), Some(distributedType)) => Some(distributedType)

        // F₁ ▹ᵃ A₁ ⇾ B₁    F₂ ▹ᵃ A₂ ⇾ B₂
        // ───────────────────────────────── AD-And-Arr
        // F₁ & F₂ ▹ᵃ (A₁ & A₂) ⇾ (B₁ & B₂)
        case (
          Some(Type.Arrow(firstParameter, firstResult)),
          Some(Type.Arrow(secondParameter, secondResult))
        ) if form == ApplicableForm.Arrow =>
          Some(Type.Arrow(
            Type.Intersection(firstParameter, secondParameter),
            Type.Intersection(firstResult, secondResult)
          ))

        // F₁ ▹ᵘ ∀(α ∗ A₁). B₁    F₂ ▹ᵘ ∀(α ∗ A₂). B₂
        // ───────────────────────────────────────────── AD-And-All
        // F₁ & F₂ ▹ᵘ ∀(α ∗ (A₁ & A₂)). (B₁ & B₂)
        case (
          Some(Type.ForAll(firstBound, firstBody)),
          Some(Type.ForAll(secondBound, secondBody))
        ) if form == ApplicableForm.Universal =>
          Some(Type.ForAll(
            Type.Intersection(firstBound, secondBound),
            Type.Intersection(firstBody, secondBody)
          ))
        case _ => None
      }
  }
}

object ApplicativeDistribution {
  def apply(applicableForm: ApplicableForm): ApplicativeDistribution =
    new ApplicativeDistribution(applicableForm)
}
