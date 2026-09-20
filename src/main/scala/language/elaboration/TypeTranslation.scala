package cp.language.elaboration

import cp.fiobs.{Type as FiobsType}
import cp.language.typing.Type

/** Total erasure of expanded CP types into the Fiobs type language. */
object TypeTranslation {
  // ⟦p⟧ = p                     ⟦A ⇾ B⟧ = ⟦A⟧ ⇾ ⟦B⟧
  // ⟦⊤⟧ = ⊤                     ⟦A ∧ B⟧ = ⟦A⟧ ∧ ⟦B⟧
  // ⟦⊥⟧ = ⊥                     ⟦{ℓ : A}⟧ = {ℓ : ⟦A⟧}
  // ⟦α⟧ = α                     ⟦∀(α ∗ A). B⟧ = ∀(α ∗ ⟦A⟧). ⟦B⟧
  // ⟦Trait[A, B]⟧ = ⟦A⟧ ⇾ ⟦B⟧
  def toFiobs(inputType: Type): FiobsType = inputType match {
    case Type.Primitive(kind) => FiobsType.Primitive(kind)
    case Type.Variable(index) => FiobsType.Variable(index)
    case Type.Top => FiobsType.Top
    case Type.Bottom => FiobsType.Bottom
    case Type.Arrow(parameter, result) => FiobsType.Arrow(toFiobs(parameter), toFiobs(result))
    case Type.ForAll(bound, body) => FiobsType.ForAll(toFiobs(bound), toFiobs(body))
    case Type.Intersection(left, right) => FiobsType.Intersection(toFiobs(left), toFiobs(right))
    case Type.Record(label, fieldType) => FiobsType.Record(label, toFiobs(fieldType))
    case Type.Trait(required, provided) => FiobsType.Arrow(toFiobs(required), toFiobs(provided))
  }
}
