package cp.fiobs.typing

import cp.fiobs.*

/** Contextual Fiobs subtyping, with a finite derivation for each successful comparison. */
final class Subtyping private (context: TypeContext) {
  import Subtyping.*

  def relates(sourceType: Type, targetType: Type): Boolean =
    scopedDerivation(sourceType, targetType).nonEmpty

  def derivation(sourceType: Type, targetType: Type): Option[SubtypeDerivation] =
    scopedDerivation(sourceType, targetType).map(_.readback)

  private def scopedDerivation(sourceType: Type, targetType: Type): Option[ScopedDerivation] =
    search(ScopedType(sourceType, Nil), ScopedType(targetType, Nil), 0, Nil)

  private def search(
    source: ScopedType,
    target: ScopedType,
    depth: Int,
    universalScope: List[BinderIdentity]
  ): Option[ScopedDerivation] = {
    def accepted(rule: SubtypeRule[ScopedDerivation]): Option[ScopedDerivation] =
      Some(ScopedDerivation(source, target, universalScope, rule))

    if (source == target) {
      // ─────────── S-Refl
      // Δ ⊢ A <: A
      accepted(SubtypeRule.Identity)
    } else {
      (source.head, target.head) match {
        // ─────────── S-Top
        // Δ ⊢ A <: ⊤
        case (_, Head.Structural(Type.Top)) => accepted(SubtypeRule.Top)

        // ─────────── S-Bot
        // Δ ⊢ ⊥ <: A
        case (Head.Structural(Type.Bottom), _) => accepted(SubtypeRule.Bottom)

        // ─────────── S-Var
        // Δ ⊢ α <: α
        case (Head.FreeVariable(first), Head.FreeVariable(second)) if first == second =>
          accepted(SubtypeRule.Identity)
        case (Head.RigidVariable(first), Head.RigidVariable(second)) if first == second =>
          accepted(SubtypeRule.Identity)

        // ─────────── S-Primitive
        // Δ ⊢ p <: p
        case (Head.Structural(Type.Primitive(first)), Head.Structural(Type.Primitive(second)))
            if first == second => accepted(SubtypeRule.Identity)

        // Δ ⊢ A <: B
        // ───────────────── S-Label
        // Δ ⊢ Aᵅ <: Bᵅ
        //
        // The payload premise validates the backedge. Its evidence is not emitted
        // again: the enclosing S-Rec owns the conversion for this nominal identity.
        case (Head.Labeled(first), Head.Labeled(second)) if first.identity == second.identity =>
          val payloadScope = if (universalScope.contains(first.identity)) {
            universalScope
          } else {
            first.identity :: universalScope
          }
          search(first.payload, second.payload, depth, payloadScope).flatMap { _ =>
            if (first.direction == second.direction) accepted(SubtypeRule.Identity)
            else accepted(SubtypeRule.RecursiveReference(first.identity.conversion, first.direction))
          }

        case (Head.Structural(Type.Intersection(leftType, rightType)), _) =>
          // Δ ⊢ A <: C
          // ────────────── S-AndL
          // Δ ⊢ A & B <: C
          //
          // Δ ⊢ B <: C
          // ────────────── S-AndR
          // Δ ⊢ A & B <: C
          search(source.withType(leftType), target, depth, universalScope)
            .flatMap(selected => accepted(SubtypeRule.SelectLeft(selected)))
            .orElse(search(source.withType(rightType), target, depth, universalScope)
              .flatMap(selected => accepted(SubtypeRule.SelectRight(selected))))
            .orElse(searchTargetSplit(source, target, depth, universalScope))

        // Δ ⊢ C <: A    Δ ⊢ B <: D
        // ───────────────────────── S-Arr
        // Δ ⊢ A ⇾ B <: C ⇾ D
        case (
          Head.Structural(Type.Arrow(sourceParameter, sourceResult)),
          Head.Structural(Type.Arrow(targetParameter, targetResult))
        ) => for {
          parameter <- search(target.withType(targetParameter), source.withType(sourceParameter), depth, universalScope)
          result <- search(source.withType(sourceResult), target.withType(targetResult), depth, universalScope)
          proof <- accepted(SubtypeRule.Arrow(parameter, result))
        } yield proof

        // Δ ⊢ C <: A    Δ, α ∗ C ⊢ B <: D
        // ────────────────────────────────── S-All
        // Δ ⊢ ∀(α ∗ A). B <: ∀(α ∗ C). D
        case (
          Head.Structural(Type.ForAll(sourceBound, sourceBody)),
          Head.Structural(Type.ForAll(targetBound, targetBody))
        ) =>
          val identity = BinderIdentity(depth)
          val variable = Binding.Rigid(identity)
          for {
            bound <- search(target.withType(targetBound), source.withType(sourceBound), depth, universalScope)
            body <- search(
              source.open(sourceBody, variable),
              target.open(targetBody, variable),
              depth + 1,
              identity :: universalScope
            )
            proof <- accepted(SubtypeRule.Universal(bound, body))
          } yield proof

        // Δ, α ⊢ A[α ↦ Aᵅ] <: B[α ↦ Bᵅ]
        // ───────────────────────────────── S-Rec
        // Δ ⊢ μα. A <: μα. B
        //
        // Aᵅ retains a rigid α in its payload. Reverse conversion evidence is
        // constructed only when a contravariant nominal reference requires it.
        case (Head.Structural(Type.Recursive(sourceBody)), Head.Structural(Type.Recursive(targetBody))) =>
          val identity = BinderIdentity(depth)
          val sourceLabel = Binding.Labeled(
            identity,
            source.open(sourceBody, Binding.Rigid(identity)),
            source,
            ConversionDirection.Forward
          )
          val targetLabel = Binding.Labeled(
            identity,
            target.open(targetBody, Binding.Rigid(identity)),
            target,
            ConversionDirection.Reverse
          )
          val forwardSource = source.open(sourceBody, sourceLabel)
          val forwardTarget = target.open(targetBody, targetLabel)
          search(forwardSource, forwardTarget, depth + 1, universalScope).map { forwardBody =>
            // The forward nominal premise entails A <: B; a reverse S-Label
            // premise provides B <: A. Substitution of equivalent labeled types
            // therefore yields the reverse nominal premise (TOPLAS 2022, Lemma 63).
            //
            // Δ, α ⊢ A <: B    Δ, α ⊢ B <: A
            // ─────────────────────────────── Recursive equivalence (admissible)
            // Δ ⊢ μ α. B <: μ α. A
            val reverseBody = if (forwardBody.usesReverse(identity.conversion)) {
              Some(search(forwardTarget, forwardSource, depth + 1, universalScope).getOrElse {
                throw new IllegalStateException("validated reverse nominal reference lacks conversion evidence")
              })
            } else None
            ScopedDerivation(
              source,
              target,
              universalScope,
              SubtypeRule.Recursive(identity.conversion, forwardBody, reverseBody)
            )
          }

        // Δ ⊢ A <: B
        // ───────────────────── S-Rcd
        // Δ ⊢ {ℓ : A} <: {ℓ : B}
        case (
          Head.Structural(Type.Record(sourceLabel, sourceField)),
          Head.Structural(Type.Record(targetLabel, targetField))
        ) if sourceLabel == targetLabel =>
          search(source.withType(sourceField), target.withType(targetField), depth, universalScope)
            .flatMap(field => accepted(SubtypeRule.Record(field)))

        case _ => searchTargetSplit(source, target, depth, universalScope)
      }
    }
  }

  private def searchTargetSplit(
    source: ScopedType,
    target: ScopedType,
    depth: Int,
    universalScope: List[BinderIdentity]
  ): Option[ScopedDerivation] = {
    // D ⤇ B ‖ C    Δ ⊢ A <: B    Δ ⊢ A <: C
    // ─────────────────────────────────────── S-Split
    // Δ ⊢ A <: D
    for {
      split <- target.inputType.split
      first <- search(source, target.withType(split.first), depth, universalScope)
      second <- search(source, target.withType(split.second), depth, universalScope)
    } yield ScopedDerivation(source, target, universalScope, SubtypeRule.Split(first, second))
  }
}

object Subtyping {
  def apply(context: TypeContext): Subtyping = new Subtyping(context)

  /** Identities are lexical depths within one comparison; sibling searches do not share substitutions. */
  private final case class BinderIdentity(depth: Int) {
    def conversion: RecursiveConversionId = RecursiveConversionId(depth)
  }

  private enum Binding {
    case Rigid(identity: BinderIdentity)
    case Labeled(
      identity: BinderIdentity,
      payload: ScopedType,
      recursiveType: ScopedType,
      direction: ConversionDirection
    )
  }

  private enum Head {
    case Structural(inputType: Type)
    case FreeVariable(index: Int)
    case RigidVariable(identity: BinderIdentity)
    case Labeled(binding: Binding.Labeled)
  }

  /** A finite substitution environment keeps nominal evidence out of the public type representation. */
  private final case class ScopedType(inputType: Type, substitutions: List[Binding]) {
    def withType(replacement: Type): ScopedType = copy(inputType = replacement)

    def open(body: Type, binding: Binding): ScopedType = ScopedType(body, binding :: substitutions)

    def head: Head = inputType match {
      case Type.Variable(index) => substitutions.lift(index) match {
        case Some(Binding.Rigid(identity)) => Head.RigidVariable(identity)
        case Some(binding: Binding.Labeled) => Head.Labeled(binding)
        case None => Head.FreeVariable(index - substitutions.length)
      }
      case _ => Head.Structural(inputType)
    }

    /** Replace nominal labels by their μ types, retaining only the live universal binders. */
    def readback(universalScope: List[BinderIdentity]): Type = {
      def visit(current: Type, localDepth: Int): Type = current match {
        case Type.Primitive(_) | Type.Top | Type.Bottom => current
        case Type.Variable(index) if index < localDepth => current
        case Type.Variable(index) => substitutions.lift(index - localDepth) match {
          case Some(Binding.Rigid(identity)) =>
            val position = universalScope.indexOf(identity)
            require(position >= 0, "nominal readback encountered an unbound rigid variable")
            Type.Variable(position + localDepth)
          case Some(Binding.Labeled(_, _, recursiveType, _)) =>
            recursiveType.readback(universalScope).shiftTypeVariables(localDepth)
          case None => Type.Variable(index - substitutions.length + universalScope.length)
        }
        case Type.Arrow(parameter, result) => Type.Arrow(visit(parameter, localDepth), visit(result, localDepth))
        case Type.Intersection(first, second) => Type.Intersection(visit(first, localDepth), visit(second, localDepth))
        case Type.ForAll(bound, body) => Type.ForAll(visit(bound, localDepth), visit(body, localDepth + 1))
        case Type.Recursive(body) => Type.Recursive(visit(body, localDepth + 1))
        case Type.Record(label, field) => Type.Record(label, visit(field, localDepth))
      }
      visit(inputType, 0)
    }
  }

  private final case class ScopedDerivation(
    source: ScopedType,
    target: ScopedType,
    universalScope: List[BinderIdentity],
    rule: SubtypeRule[ScopedDerivation]
  ) {
    def usesReverse(identity: RecursiveConversionId): Boolean = rule match {
      case SubtypeRule.RecursiveReference(found, ConversionDirection.Reverse) => found == identity
      case _ => rule.children.exists(_.usesReverse(identity))
    }

    def readback: SubtypeDerivation = SubtypeDerivation(
      source.readback(universalScope),
      target.readback(universalScope),
      rule.mapChildren(_.readback)
    )
  }
}
