package cp.fiobs.typing

import cp.fiobs.*

/** Decides contextual disjointness, with finite possible-route certificates for recursive interfaces. */
final class Disjointness private(context: TypeContext) {
  def relates(leftType: Type, rightType: Type): Boolean =
    search(leftType, rightType, context)

  private def search(
    leftType: Type,
    rightType: Type,
    currentContext: TypeContext
  ): Boolean = {
    val routeAnalysis = RouteAnalysis(currentContext)

    // Δ ⊢ A ∅ᵒ
    // ───────────── D-SilentL
    // Δ ⊢ A ∗ B
    //
    // Δ ⊢ B ∅ᵒ
    // ───────────── D-SilentR
    // Δ ⊢ A ∗ B
    if (routeAnalysis.isSilent(leftType) || routeAnalysis.isSilent(rightType)) {
      true
    } else {
      leftType match {
        // α ∗ A ∈ Δ    Δ ⊢ A <:ᵒ B
        // ───────────────────────── D-VarL
        // Δ ⊢ α ∗ B
        case Type.Variable(index) =>
          currentContext.lookup(index).exists(routeAnalysis.isRouteSubtype(_, rightType))
        case _ => rightType match {
          // α ∗ A ∈ Δ    Δ ⊢ A <:ᵒ B
          // ───────────────────────── D-VarR
          // Δ ⊢ B ∗ α
          case Type.Variable(index) =>
            currentContext.lookup(index).exists(routeAnalysis.isRouteSubtype(_, leftType))
          case _ => leftType.split match {
            // A ⤇ A₁ ‖ A₂    Δ ⊢ A₁ ∗ B    Δ ⊢ A₂ ∗ B
            // ─────────────────────────────────────── D-SplitL
            // Δ ⊢ A ∗ B
            case Some(typeSplit) =>
              search(typeSplit.first, rightType, currentContext) &&
                search(typeSplit.second, rightType, currentContext)
            case None => rightType.split match {
              // B ⤇ B₁ ‖ B₂    Δ ⊢ A ∗ B₁    Δ ⊢ A ∗ B₂
              // ─────────────────────────────────────── D-SplitR
              // Δ ⊢ A ∗ B
              case Some(typeSplit) =>
                search(leftType, typeSplit.first, currentContext) &&
                  search(leftType, typeSplit.second, currentContext)
              case None => constructorsAreDisjoint(leftType, rightType, currentContext)
            }
          }
        }
      }
    }
  }

  private def constructorsAreDisjoint(
    leftType: Type,
    rightType: Type,
    currentContext: TypeContext
  ): Boolean = (leftType, rightType) match {
    // p ≠ q
    // ─────────────── D-PrimitiveNe
    // Δ ⊢ p ∗ q
    case (Type.Primitive(leftKind), Type.Primitive(rightKind)) => leftKind != rightKind

    // ─────────────────── D-PrimitiveArr
    // Δ ⊢ p ∗ (A ⇾ B)
    //
    // ───────────────────────────── D-PrimitiveAll
    // Δ ⊢ p ∗ ∀(α ∗ A). B
    //
    // ────────────────────── D-PrimitiveRcd
    // Δ ⊢ p ∗ {ℓ : A}
    case (Type.Primitive(_), Type.Arrow(_, _) | Type.ForAll(_, _) | Type.Record(_, _)) => true

    // ─────────────────── D-ArrPrimitive
    // Δ ⊢ (A ⇾ B) ∗ p
    //
    // ───────────────────────────── D-AllPrimitive
    // Δ ⊢ ∀(α ∗ A). B ∗ p
    //
    // ────────────────────── D-RcdPrimitive
    // Δ ⊢ {ℓ : A} ∗ p
    case (Type.Arrow(_, _) | Type.ForAll(_, _) | Type.Record(_, _), Type.Primitive(_)) => true

    // Δ ⊢ B₁ ∗ B₂
    // ───────────────────────── D-Arr
    // Δ ⊢ (A₁ ⇾ B₁) ∗ (A₂ ⇾ B₂)
    case (Type.Arrow(_, leftResult), Type.Arrow(_, rightResult)) =>
      search(leftResult, rightResult, currentContext)

    // Δ, α ∗ (A₁ & A₂) ⊢ B₁ ∗ B₂
    // ───────────────────────────────────────────── D-All
    // Δ ⊢ ∀(α ∗ A₁). B₁ ∗ ∀(α ∗ A₂). B₂
    case (Type.ForAll(leftBound, leftBody), Type.ForAll(rightBound, rightBody)) =>
      search(
        leftBody,
        rightBody,
        currentContext.extend(Type.Intersection(leftBound, rightBound))
      )

    // M(μα. A) ∩ M(μβ. B) = ∅
    // ─────────────────────────── D-Rec
    // Δ ⊢ μα. A ∗ μβ. B
    // M is the finite may-route language defined in PossibleRoutes, not a TopLike test.
    case (left @ Type.Recursive(_), right @ Type.Recursive(_)) => PossibleRoutes.areDisjoint(left, right)

    // H ∈ {p, A ⇾ B, ∀(α ∗ A). B, {ℓ : A}}
    // ───────────────────────────────────────── D-RecHead
    // Δ ⊢ μα. C ∗ H
    //
    // H ∈ {p, A ⇾ B, ∀(α ∗ A). B, {ℓ : A}}
    // ───────────────────────────────────────── D-HeadRec
    // Δ ⊢ H ∗ μα. C
    case (Type.Recursive(_), Type.Primitive(_) | Type.Arrow(_, _) | Type.ForAll(_, _) | Type.Record(_, _)) => true
    case (Type.Primitive(_) | Type.Arrow(_, _) | Type.ForAll(_, _) | Type.Record(_, _), Type.Recursive(_)) => true

    // ℓ₁ ≠ ℓ₂
    // ───────────────────── D-RcdNe
    // Δ ⊢ {ℓ₁ : A} ∗ {ℓ₂ : B}
    //
    // Δ ⊢ A ∗ B
    // ───────────────────── D-Rcd
    // Δ ⊢ {ℓ : A} ∗ {ℓ : B}
    case (Type.Record(leftLabel, leftField), Type.Record(rightLabel, rightField)) =>
      leftLabel != rightLabel || search(leftField, rightField, currentContext)

    // ───────────────────────────── D-ArrAll
    // Δ ⊢ (A ⇾ B) ∗ ∀(α ∗ C). D
    //
    // ────────────────────── D-ArrRcd
    // Δ ⊢ (A ⇾ B) ∗ {ℓ : C}
    case (Type.Arrow(_, _), Type.ForAll(_, _) | Type.Record(_, _)) => true

    // ───────────────────────────── D-AllArr
    // Δ ⊢ ∀(α ∗ A). B ∗ (C ⇾ D)
    //
    // ─────────────────────────── D-AllRcd
    // Δ ⊢ ∀(α ∗ A). B ∗ {ℓ : C}
    case (Type.ForAll(_, _), Type.Arrow(_, _) | Type.Record(_, _)) => true

    // ────────────────────── D-RcdArr
    // Δ ⊢ {ℓ : C} ∗ (A ⇾ B)
    //
    // ─────────────────────────── D-RcdAll
    // Δ ⊢ {ℓ : C} ∗ ∀(α ∗ A). B
    case (Type.Record(_, _), Type.Arrow(_, _) | Type.ForAll(_, _)) => true
    case _ => false
  }
}

object Disjointness {
  def apply(context: TypeContext): Disjointness = new Disjointness(context)
}
