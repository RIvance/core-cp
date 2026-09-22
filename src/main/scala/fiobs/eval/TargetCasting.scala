package cp.fiobs.eval

import cp.fiobs.*
import cp.fiobs.runtime.RuntimeBinding.*
import cp.fiobs.runtime.RuntimeTerm

/** Deterministic target-directed casting used by lazy evaluation. */
private[eval] object TargetCasting {
  def cast(source: RuntimeTerm, targetType: Type): Option[RuntimeTerm] = targetType match {
    // r ⟶[B] r₁    r ⟶[C] r₂
    // ─────────────────────── Cast-And
    // r ⟶[B ∧ C] r₁ ,, r₂
    case Type.Intersection(leftType, rightType) =>
      for {
        leftResult <- cast(source, leftType)
        rightResult <- cast(source, rightType)
      } yield RuntimeTerm.Merge(leftResult, rightResult)

    // D ⤇ B ‖ C    r ⟶[A ⇾ B] f₁    r ⟶[A ⇾ C] f₂
    // ───────────────────────────────────────────── Cast-BCD-Arr
    // r ⟶[A ⇾ D] ⟨λx. ((f₁ x ,, f₂ x) : D)⟩^{A ⇾ D}
    case arrowType @ Type.Arrow(parameterType, resultType) =>
      resultType.split match {
        case Some(resultSplit) => castSplitArrow(source, parameterType, resultType, resultSplit)
        case None => castRigid(source, arrowType)
      }

    // D ⤇ B ‖ C    r ⟶[∀(α ∗ A). B] u₁    r ⟶[∀(α ∗ A). C] u₂
    // ───────────────────────────────────────────────────────── Cast-BCD-All
    // r ⟶[∀(α ∗ A). D] ⟨Λ(α ∗ A). ((u₁[α] ,, u₂[α]) : D)⟩^{∀(α ∗ A).D}
    case universalType @ Type.ForAll(disjointBound, bodyType) =>
      bodyType.split match {
        case Some(bodySplit) => castSplitUniversal(source, disjointBound, bodyType, bodySplit)
        case None => castRigid(source, universalType)
      }

    // D ⤇ B ‖ C
    // r ⟶[{ℓ : B}] ⟨{ℓ = w₁}⟩^{ℓ : B}    r ⟶[{ℓ : C}] ⟨{ℓ = w₂}⟩^{ℓ : C}
    // ───────────────────────────────────────────────────────────────── Cast-BCD-Rcd
    // r ⟶[{ℓ : D}] ⟨{ℓ = ((w₁ ,, w₂) : D)}⟩^{ℓ : D}
    case recordType @ Type.Record(label, fieldType) =>
      fieldType.split match {
        case Some(fieldSplit) => castSplitRecord(source, label, fieldType, fieldSplit)
        case None => castRigid(source, recordType)
      }

    case _ if targetType.isRigid => castRigid(source, targetType)
    case _ => None
  }

  private def castSplitArrow(
    source: RuntimeTerm,
    parameterType: Type,
    resultType: Type,
    resultSplit: TypeSplit
  ): Option[RuntimeTerm] = {
    for {
      firstFunction <- cast(source, Type.Arrow(parameterType, resultSplit.first))
      secondFunction <- cast(source, Type.Arrow(parameterType, resultSplit.second))
    } yield RuntimeTerm.Lambda(
      parameterType,
      RuntimeTerm.Cast(
        RuntimeTerm.Merge(
          RuntimeTerm.Application(firstFunction.shiftTermVariables(1), RuntimeTerm.Variable(0)),
          RuntimeTerm.Application(secondFunction.shiftTermVariables(1), RuntimeTerm.Variable(0))
        ),
        resultType
      ),
      resultType
    )
  }

  private def castSplitUniversal(
    source: RuntimeTerm,
    disjointBound: Type,
    bodyType: Type,
    bodySplit: TypeSplit
  ): Option[RuntimeTerm] = {
    for {
      firstUniversal <- cast(source, Type.ForAll(disjointBound, bodySplit.first))
      secondUniversal <- cast(source, Type.ForAll(disjointBound, bodySplit.second))
    } yield RuntimeTerm.TypeLambda(
      disjointBound,
      RuntimeTerm.Cast(
        RuntimeTerm.Merge(
          RuntimeTerm.TypeApplication(firstUniversal.shiftTypeVariables(1), Type.Variable(0)),
          RuntimeTerm.TypeApplication(secondUniversal.shiftTypeVariables(1), Type.Variable(0))
        ),
        bodyType
      ),
      bodyType
    )
  }

  private def castSplitRecord(
    source: RuntimeTerm,
    label: String,
    fieldType: Type,
    fieldSplit: TypeSplit
  ): Option[RuntimeTerm] = {
    for {
      firstRecord <- cast(source, Type.Record(label, fieldSplit.first))
      secondRecord <- cast(source, Type.Record(label, fieldSplit.second))
      firstField <- recordField(firstRecord, label)
      secondField <- recordField(secondRecord, label)
    } yield RuntimeTerm.Record(
      label,
      RuntimeTerm.Cast(RuntimeTerm.Merge(firstField, secondField), fieldType),
      fieldType
    )
  }

  private def castRigid(source: RuntimeTerm, targetType: Type): Option[RuntimeTerm] = {
    (source, targetType) match {
      // ─────────────── Cast-Top
      // r ⟶[⊤] top
      case (_, Type.Top) => Some(RuntimeTerm.Top)

      // value : p
      // ───────────────── Cast-Primitive
      // value ⟶[p] value
      case (RuntimeTerm.Literal(value), Type.Primitive(kind)) if value.primitiveType == kind => Some(source)

      // Rigid(C ⇾ D)    f = ⟨λy. r⟩^{A ⇾ B}    ∅ ⊢ C <: A    ∅ ⊢ B <: D
      // ─────────────────────────────────────────────────────────────── Cast-Arr
      // f ⟶[C ⇾ D] ⟨λx. ((f (x : A)) : D)⟩^{C ⇾ D}
      case (
          function @ RuntimeTerm.Lambda(sourceParameter, _, sourceResult),
          Type.Arrow(targetParameter, targetResult)
      ) if targetParameter.isSubtypeOf(sourceParameter) && sourceResult.isSubtypeOf(targetResult) =>
        Some(RuntimeTerm.Lambda(
          targetParameter,
          RuntimeTerm.Cast(
            RuntimeTerm.Application(
              function.shiftTermVariables(1),
              RuntimeTerm.Cast(RuntimeTerm.Variable(0), sourceParameter)
            ),
            targetResult
          ),
          targetResult
        ))

      // Rigid(∀(α ∗ C). D)    u = ⟨Λ(α ∗ A). r⟩^{∀(α ∗ A).B}
      // ∅ ⊢ C <: A    α ∗ C ⊢ B <: D
      // ───────────────────────────────────────────────── Cast-All
      // u ⟶[∀(α ∗ C). D] ⟨Λ(α ∗ C). (u[α] : D)⟩^{∀(α ∗ C).D}
      case (
          universal @ RuntimeTerm.TypeLambda(sourceBound, _, sourceResult),
          Type.ForAll(targetBound, targetResult)
      ) if targetBound.isSubtypeOf(sourceBound) &&
          sourceResult.isSubtypeOf(targetResult, TypeContext.empty.extend(targetBound)) =>
        Some(RuntimeTerm.TypeLambda(
          targetBound,
          RuntimeTerm.Cast(
            RuntimeTerm.TypeApplication(universal.shiftTypeVariables(1), Type.Variable(0)),
            targetResult
          ),
          targetResult
        ))

      // Rigid({ℓ : B})    ∅ ⊢ A <: B
      // ───────────────────────────────────────────── Cast-Rcd
      // ⟨{ℓ = r}⟩^{ℓ : A} ⟶[{ℓ : B}] ⟨{ℓ = (r : B)}⟩^{ℓ : B}
      case (
          RuntimeTerm.Record(sourceLabel, field, sourceFieldType),
          Type.Record(targetLabel, targetFieldType)
      ) if sourceLabel == targetLabel && sourceFieldType.isSubtypeOf(targetFieldType) =>
        Some(RuntimeTerm.Record(sourceLabel, RuntimeTerm.Cast(field, targetFieldType), targetFieldType))

      // R = μ α. A    S = μ β. B    ∅ ⊢ R <: S
      // ───────────────────────────────────────────────── Cast-Rec
      // ⟨fold r⟩ᴿ ⟶[S] ⟨fold (r : B[β ↦ S])⟩ˢ
      //
      // The payload cast stays suspended. Its source type is A[α ↦ R];
      // the recursive subtyping unfolding lemma justifies the cast.
      case (
          RuntimeTerm.Fold(sourceType, body),
          targetRecursive @ Type.Recursive(targetBody)
      ) if sourceType.isSubtypeOf(targetRecursive) =>
        Some(RuntimeTerm.Fold(
          targetRecursive,
          RuntimeTerm.Cast(body, targetBody.substituteType(0, targetRecursive))
        ))

      // Rigid(B)    r₁ ⟶[B] r′
      // ─────────────────────── Cast-MergeL
      // r₁ ,, r₂ ⟶[B] r′
      //
      // Rigid(B)    r₂ ⟶[B] r′
      // ─────────────────────── Cast-MergeR
      // r₁ ,, r₂ ⟶[B] r′
      case (RuntimeTerm.Merge(left, right), _) => cast(left, targetType).orElse(cast(right, targetType))

      // No rule with target ⊥.
      case _ => None
    }
  }

  private def recordField(record: RuntimeTerm, expectedLabel: String): Option[RuntimeTerm] = record match {
    case RuntimeTerm.Record(label, field, _) if label == expectedLabel => Some(field)
    case _ => None
  }
}
