package cp.fitrie.elaboration

import cp.fiobs.{Type, TypeContext}
import cp.fitrie.*
import cp.naming.FieldLabel
import cp.util.Result

enum CoercionError {
  case NotSubtype(sourceType: Type, targetType: Type)
  case Merge(error: FiTrieMergeError)
}

private[fitrie] object Coercion {
  def coerce(
    trie: FiTrie,
    sourceType: Type,
    targetType: Type,
    context: TypeContext
  ): Result[FiTrie, CoercionError] = {
    if (!sourceType.isSubtypeOf(targetType, context)) {
      Result.Err(CoercionError.NotSubtype(sourceType, targetType))
    } else if (targetType == Type.Top) {
      // ───────────────────────────────── Coe-Top
      // Δ ⊢ t ↦ᴬ<:⊤ { t ▷ ∅ ; · ; · }
      Result.Ok(suspendedFilter(trie, RootKeyExpression.concrete(RootKeySet.empty)))
    } else if (sourceType == Type.Bottom) {
      /*
       * Δ ⊢ A ⇛ₖ 𝒦
       * ───────────────────────────────── Coe-Bot
       * Δ ⊢ t ↦⊥<:ᴬ {t ▷ 𝒦 ; · ; ·}
       */
      Result.Ok(suspendedFilter(trie, RootKeyCompilation.compile(targetType)))
    } else if (sourceType == targetType) {
      sourceType match {
        /*
         * Δ ⊢ a ⇛ₖ 𝒦
         * ───────────────────────────────── Coe-Atomic
         * Δ ⊢ t ↦ᵃ<:ᵃ {t ▷ 𝒦 ; · ; ·}
         */
        case Type.Primitive(_) | Type.Variable(_) =>
          Result.Ok(suspendedFilter(trie, RootKeyCompilation.compile(sourceType)))

        // A ≠ a    A ≠ ⊤    A ≠ ⊥
        // ──────────────────────── Coe-Refl-Structured
        // Δ ⊢ t ↦ᴬ<:ᴬ t
        case _ => Result.Ok(trie)
      }
    } else {
      coerceNontrivial(trie, sourceType, targetType, context)
    }
  }

  private def coerceNontrivial(
    trie: FiTrie,
    sourceType: Type,
    targetType: Type,
    context: TypeContext
  ): Result[FiTrie, CoercionError] = sourceType match {
    case intersection @ Type.Intersection(firstType, secondType) =>
      coerceIntersectionSource(
        trie,
        intersection,
        firstType,
        secondType,
        targetType,
        context
      )
    case Type.Arrow(sourceParameter, sourceResult) => targetType match {
      case Type.Arrow(targetParameter, targetResult) =>
        coerceArrow(
          trie,
          sourceParameter,
          sourceResult,
          targetParameter,
          targetResult,
          context
        )
      case _ => coerceTargetSplit(trie, sourceType, targetType, context)
    }
    case Type.ForAll(sourceBound, sourceBody) => targetType match {
      case Type.ForAll(targetBound, targetBody) =>
        coerceUniversal(trie, sourceBound, sourceBody, targetBound, targetBody, context)
      case _ => coerceTargetSplit(trie, sourceType, targetType, context)
    }
    case Type.Record(sourceLabel, sourceFieldType) => targetType match {
      case Type.Record(targetLabel, targetFieldType) if sourceLabel == targetLabel =>
        coerceRecord(trie, sourceLabel, sourceFieldType, targetFieldType, context)
      case _ => coerceTargetSplit(trie, sourceType, targetType, context)
    }
    case _ => coerceTargetSplit(trie, sourceType, targetType, context)
  }

  private def coerceArrow(
    trie: FiTrie,
    sourceParameter: Type,
    sourceResult: Type,
    targetParameter: Type,
    targetResult: Type,
    context: TypeContext
  ): Result[FiTrie, CoercionError] = {
    /*
     * x ∉ fv(t)    Δ ⊢ {x ; · ; ·} ↦ᶜ<:ᴬ u₋
     * Δ ⊢ {t ◁ ⟨app[u₋]⟩ ; · ; ·} ↦ᴮ<:ᴰ u₊
     * ───────────────────────────────────────────── Coe-Arr
     * Δ ⊢ t ↦ᴬ⇾ᴮ<:ᶜ⇾ᴰ {t ▷ ∅ ; appₓ ↦ u₊ ; ·}
     */
    val argument = FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(0)))
    coerce(argument, targetParameter, sourceParameter, context).flatMap { coercedArgument =>
      val shiftedReceiver = trie.shiftTermVariables(1)
      val application = suspendedIndex(
        shiftedReceiver,
        RequestSet.one(Request.Application(coercedArgument))
      )
      coerce(application, sourceResult, targetResult, context).map { coercedResult =>
        FiTrie.node(
          responseComputations = suspendedEmptyFilter(trie),
          routeContinuations = Map(RouteKey.Application -> coercedResult)
        )
      }
    }
  }

  private def coerceUniversal(
    trie: FiTrie,
    sourceBound: Type,
    sourceBody: Type,
    targetBound: Type,
    targetBody: Type,
    context: TypeContext
  ): Result[FiTrie, CoercionError] = {
    /*
     * Δ ⊢ C <: A
     * Δ, α ∗ C ⊢ {t ◁ ⟨tapp[α]⟩ ; · ; ·} ↦ᴮ<:ᴰ u
     * ─────────────────────────────────────────────────── Coe-All-Paths
     * Δ ⊢ t ↦∀(α∗A).B<:∀(α∗C).D {t ▷ ∅ ; tappα ↦ u ; ·}
     */
    if (!targetBound.isSubtypeOf(sourceBound, context)) {
      Result.Err(CoercionError.NotSubtype(
        Type.ForAll(sourceBound, sourceBody),
        Type.ForAll(targetBound, targetBody)
      ))
    } else {
      val application = suspendedIndex(
        trie.shiftPathVariables(1),
        RequestSet.one(Request.TypeApplication(
          ObservationPathInterface.variable(PathVariableIndex(0))
        ))
      )
      coerce(application, sourceBody, targetBody, context.extend(targetBound)).map { coercedBody =>
        FiTrie.node(
          responseComputations = suspendedEmptyFilter(trie),
          routeContinuations = Map(RouteKey.TypeApplication -> coercedBody)
        )
      }
    }
  }

  private def coerceRecord(
    trie: FiTrie,
    label: String,
    sourceFieldType: Type,
    targetFieldType: Type,
    context: TypeContext
  ): Result[FiTrie, CoercionError] = {
    /*
     * Δ ⊢ { t ◁ ⟨projℓ⟩ ; · ; · } ↦ᴬ<:ᴮ u
     * ───────────────────────────────────────── Coe-Rcd
     * Δ ⊢ t ↦{ℓ: A}<:{ℓ: B} { t ▷ ∅ ; projℓ ↦ u ; · }
     */
    val fieldLabel = FieldLabel(label)
    val projection = suspendedIndex(trie, RequestSet.one(Request.Projection(fieldLabel)))
    coerce(projection, sourceFieldType, targetFieldType, context).map { coercedField =>
      FiTrie.node(
        responseComputations = suspendedEmptyFilter(trie),
        routeContinuations = Map(RouteKey.Projection(fieldLabel) -> coercedField)
      )
    }
  }

  private def coerceIntersectionSource(
    trie: FiTrie,
    intersection: Type.Intersection,
    firstType: Type,
    secondType: Type,
    targetType: Type,
    context: TypeContext
  ): Result[FiTrie, CoercionError] = {
    val firstRootKeys = RootKeyCompilation.compile(firstType)
    val secondRootKeys = RootKeyCompilation.compile(secondType)
    val rootKeysAreDisjoint = areDisjoint(firstRootKeys, secondRootKeys)
    val firstIsSubtype = firstType.isSubtypeOf(targetType, context)
    val secondIsSubtype = secondType.isSubtypeOf(targetType, context)

    if (rootKeysAreDisjoint && firstIsSubtype) {
      coerceSelectedFirstComponent(trie, firstType, secondType, targetType, context)
    } else if (rootKeysAreDisjoint && secondIsSubtype) {
      coerceSelectedSecondComponent(trie, firstType, secondType, targetType, context)
    } else {
      targetType.split match {
        case Some(_) => coerceTargetSplit(trie, intersection, targetType, context)
        case None if firstIsSubtype =>
          coerceSelectedFirstComponent(trie, firstType, secondType, targetType, context)
        case None if secondIsSubtype =>
          coerceSelectedSecondComponent(trie, firstType, secondType, targetType, context)
        case None => Result.Err(CoercionError.NotSubtype(intersection, targetType))
      }
    }
  }

  private def coerceSelectedFirstComponent(
    trie: FiTrie,
    firstType: Type,
    secondType: Type,
    targetType: Type,
    context: TypeContext
  ): Result[FiTrie, CoercionError] = {
    /*
     * Δ ⊢ t ↦ A✓ | B tₐ    Δ ⊢ tₐ ↦ᴬ<:ᴰ u
     * ─────────────────────────────────────── Coe-AndL
     * Δ ⊢ t ↦ᴬ&ᴮ<:ᴰ u
     */
    coerce(
      IntersectionSelection.selectFirstComponent(trie, firstType),
      firstType,
      targetType,
      context
    )
  }

  private def coerceSelectedSecondComponent(
    trie: FiTrie,
    firstType: Type,
    secondType: Type,
    targetType: Type,
    context: TypeContext
  ): Result[FiTrie, CoercionError] = {
    /*
     * Δ ⊢ t ↦ A | B✓ tᵦ    Δ ⊢ tᵦ ↦ᴮ<:ᴰ u
     * ─────────────────────────────────────── Coe-AndR
     * Δ ⊢ t ↦ᴬ&ᴮ<:ᴰ u
     */
    coerce(
      IntersectionSelection.selectSecondComponent(trie, secondType),
      secondType,
      targetType,
      context
    )
  }

  private def coerceTargetSplit(
    trie: FiTrie,
    sourceType: Type,
    targetType: Type,
    context: TypeContext
  ): Result[FiTrie, CoercionError] = targetType.split match {
    /*
     * D ⤇ B ‖ C
     * Δ ⊢ t ↦ᴬ<:ᴮ u₁    Δ ⊢ t ↦ᴬ<:ᶜ u₂
     * u₁ ⊕ u₂ = u
     * ───────────────────────────────────── Coe-Split
     * Δ ⊢ t ↦ᴬ<:ᴰ u
     */
    case Some(targetSplit) =>
      for {
        first <- coerce(trie, sourceType, targetSplit.first, context)
        second <- coerce(trie, sourceType, targetSplit.second, context)
        merged <- FiTrie.merge(first, second).mapError(CoercionError.Merge(_))
      } yield merged
    case None => Result.Err(CoercionError.NotSubtype(sourceType, targetType))
  }

  private def suspendedFilter(trie: FiTrie, selectedRootKeys: RootKeyExpression): FiTrie = {
    FiTrie.response(ResponseComputation.Filter(trie, selectedRootKeys))
  }

  private def areDisjoint(
    firstRootKeys: RootKeyExpression,
    secondRootKeys: RootKeyExpression
  ): Boolean = (firstRootKeys.normalize, secondRootKeys.normalize) match {
    case (Some(firstConcreteKeys), Some(secondConcreteKeys)) =>
      firstConcreteKeys.isDisjointFrom(secondConcreteKeys)
    case _ => false
  }

  private def suspendedEmptyFilter(trie: FiTrie): Set[ResponseComputation] = {
    Set(ResponseComputation.Filter(
      trie,
      RootKeyExpression.concrete(RootKeySet.empty)
    ))
  }

  private def suspendedIndex(trie: FiTrie, requests: RequestSet): FiTrie = {
    FiTrie.response(ResponseComputation.Index(trie, requests))
  }
}
