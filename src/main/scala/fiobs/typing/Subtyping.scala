package cp.fiobs.typing

import cp.fiobs.*

/** The contextual Fiobs subtyping relation with the formal rule priority. */
final class Subtyping private (context: TypeContext) {
  def relates(sourceType: Type, targetType: Type): Boolean =
    search(sourceType, targetType, context)

  private def search(
    sourceType: Type,
    targetType: Type,
    currentContext: TypeContext
  ): Boolean = {
    if (sourceType == targetType) {
      // ─────────── S-Refl
      // Δ ⊢ A <: A
      true
    } else if (targetType == Type.Top) {
      // ─────────── S-Top
      // Δ ⊢ A <: ⊤
      true
    } else if (sourceType == Type.Bottom) {
      // ─────────── S-Bot
      // Δ ⊢ ⊥ <: A
      true
    } else {
      (sourceType, targetType) match {
        // Δ ⊢ C <: A    Δ ⊢ B <: D
        // ───────────────────────── S-Arr
        // Δ ⊢ A ⇾ B <: C ⇾ D
        case (Type.Arrow(sourceParameter, sourceResult), Type.Arrow(targetParameter, targetResult)) =>
          search(targetParameter, sourceParameter, currentContext) &&
          search(sourceResult, targetResult, currentContext)

        // Δ ⊢ C <: A    Δ, α ∗ C ⊢ B <: D
        // ────────────────────────────────── S-All
        // Δ ⊢ ∀(α ∗ A). B <: ∀(α ∗ C). D
        case (Type.ForAll(sourceBound, sourceBody), Type.ForAll(targetBound, targetBody)) =>
          search(targetBound, sourceBound, currentContext) &&
          search(sourceBody, targetBody, currentContext.extend(targetBound))

        // Δ ⊢ A <: B
        // ───────────────────── S-Rcd
        // Δ ⊢ {ℓ : A} <: {ℓ : B}
        case (Type.Record(sourceLabel, sourceField), Type.Record(targetLabel, targetField))
            if sourceLabel == targetLabel =>
          search(sourceField, targetField, currentContext)

        case (Type.Intersection(leftType, rightType), _) =>
          // Δ ⊢ A <: C
          // ─────────── S-AndL
          // Δ ⊢ A & B <: C
          //
          // Δ ⊢ B <: C
          // ─────────── S-AndR
          // Δ ⊢ A & B <: C
          val sourceSelection =
            search(leftType, targetType, currentContext) ||
            search(rightType, targetType, currentContext)
          sourceSelection || searchTargetSplit(sourceType, targetType, currentContext)
        case _ => searchTargetSplit(sourceType, targetType, currentContext)
      }
    }
  }

  private def searchTargetSplit(
    sourceType: Type,
    targetType: Type,
    currentContext: TypeContext
  ): Boolean = {
    // D ⤇ B ‖ C    Δ ⊢ A <: B    Δ ⊢ A <: C
    // ─────────────────────────────────────── S-Split
    // Δ ⊢ A <: D
    targetType.split.exists(targetSplit =>
      search(sourceType, targetSplit.first, currentContext) &&
      search(sourceType, targetSplit.second, currentContext)
    )
  }
}

object Subtyping {
  def apply(context: TypeContext): Subtyping = new Subtyping(context)
}
