package cp.fitrie.elaboration

import cp.fiobs.{Type, TypeContext}
import cp.fiobs.typing.{ConversionDirection, RecursiveConversionId, SubtypeDerivation, SubtypeRule, Subtyping}
import cp.fitrie.*
import cp.naming.FieldLabel
import cp.util.Result

enum CoercionError {
  case NotSubtype(sourceType: Type, targetType: Type)
  case Merge(error: FiTrieMergeError)
}

/** Compiles finite subtyping evidence, including its lexical recursive conversion references. */
private[fitrie] object Coercion {
  def coerce(
    trie: FiTrie,
    sourceType: Type,
    targetType: Type,
    context: TypeContext
  ): Result[FiTrie, CoercionError] = {
    Subtyping(context).derivation(sourceType, targetType) match {
      case Some(derivation) => compile(trie, derivation, ConversionScope.empty)
      case None => Result.Err(CoercionError.NotSubtype(sourceType, targetType))
    }
  }

  private def compile(
    trie: FiTrie,
    derivation: SubtypeDerivation,
    scope: ConversionScope
  ): Result[FiTrie, CoercionError] = derivation.rule match {
    case SubtypeRule.Identity => derivation.targetType match {
      // Δ ⊢ A ⇛ₖ 𝒦    A ∈ {p, α, ⊤, ⊥}
      // ───────────────────────────────── Coe-Refl-Atomic
      // Δ ⊢ t ↦ᴬ<:ᴬ {t ▷ 𝒦 ; · ; ·}
      case Type.Primitive(_) | Type.Variable(_) | Type.Top | Type.Bottom =>
        Result.Ok(suspendedFilter(trie, RootKeyCompilation.compile(derivation.targetType)))

      // A ∉ {p, α, ⊤, ⊥}
      // ───────────────── Coe-Refl-Structured
      // Δ ⊢ t ↦ᴬ<:ᴬ t
      case _ => Result.Ok(trie)
    }

    // ───────────────────────────────── Coe-Top
    // Δ ⊢ t ↦ᴬ<:⊤ {t ▷ ∅ ; · ; ·}
    case SubtypeRule.Top => Result.Ok(suspendedFilter(trie, RootKeyExpression.concrete(RootKeySet.empty)))

    // Δ ⊢ A ⇛ₖ 𝒦
    // ───────────────────────────────── Coe-Bot
    // Δ ⊢ t ↦⊥<:ᴬ {t ▷ 𝒦 ; · ; ·}
    case SubtypeRule.Bottom => Result.Ok(suspendedFilter(trie, RootKeyCompilation.compile(derivation.targetType)))

    /*
     * x ∉ fv(t)    Δ ⊢ {x ; · ; ·} ↦ᶜ<:ᴬ u₋
     * Δ ⊢ {t ◁ ⟨app[u₋]⟩ ; · ; ·} ↦ᴮ<:ᴰ u₊
     * ───────────────────────────────────────────── Coe-Arr
     * Δ ⊢ t ↦ᴬ⇾ᴮ<:ᶜ⇾ᴰ {t ▷ ∅ ; appₓ ↦ u₊ ; ·}
     */
    case SubtypeRule.Arrow(parameter, result) =>
      val innerScope = scope.withTermBinders(1)
      for {
        argument <- compile(localVariable(0), parameter, innerScope)
        applied = suspendedIndex(trie.shiftTermVariables(1), Request.Application(argument))
        coercedResult <- compile(applied, result, innerScope)
      } yield guardedRoute(trie, RouteKey.Application, coercedResult)

    /*
     * Δ ⊢ C <: A
     * Δ, α ∗ C ⊢ {t ◁ ⟨tapp[α]⟩ ; · ; ·} ↦ᴮ<:ᴰ u
     * ─────────────────────────────────────────────────── Coe-All-Paths
     * Δ ⊢ t ↦∀(α∗A).B<:∀(α∗C).D {t ▷ ∅ ; tappα ↦ u ; ·}
     */
    case SubtypeRule.Universal(_, body) =>
      val applied = suspendedIndex(
        trie.shiftPathVariables(1),
        Request.TypeApplication(ObservationPathInterface.variable(PathVariableIndex(0)))
      )
      compile(applied, body, scope.withTypeBinder).map(guardedRoute(trie, RouteKey.TypeApplication, _))

    /*
     * Δ ⊢ {t ◁ ⟨projℓ⟩ ; · ; ·} ↦ᴬ<:ᴮ u
     * ───────────────────────────────────────── Coe-Rcd
     * Δ ⊢ t ↦{ℓ:A}<:{ℓ:B} {t ▷ ∅ ; projℓ ↦ u ; ·}
     */
    case SubtypeRule.Record(field) =>
      val label = derivation.targetType match {
        case Type.Record(name, _) => FieldLabel(name)
        case _ => throw new IllegalStateException("record subtyping evidence has a non-record target")
      }
      compile(suspendedIndex(trie, Request.Projection(label)), field, scope)
        .map(guardedRoute(trie, RouteKey.Projection(label), _))

    // Δ ⊢ t ↦ A✓ | B tₐ    Δ ⊢ tₐ ↦ᴬ<:ᴰ u
    // ─────────────────────────────────────── Coe-AndL
    // Δ ⊢ t ↦ᴬ∧ᴮ<:ᴰ u
    case SubtypeRule.SelectLeft(selected) => compile(
      IntersectionSelection.selectFirstComponent(trie, selected.sourceType),
      selected,
      scope
    )

    // Δ ⊢ t ↦ A | B✓ tᵦ    Δ ⊢ tᵦ ↦ᴮ<:ᴰ u
    // ─────────────────────────────────────── Coe-AndR
    // Δ ⊢ t ↦ᴬ∧ᴮ<:ᴰ u
    case SubtypeRule.SelectRight(selected) => compile(
      IntersectionSelection.selectSecondComponent(trie, selected.sourceType),
      selected,
      scope
    )

    // D ⤇ B ‖ C    Δ ⊢ t ↦ᴬ<:ᴮ u₁    Δ ⊢ t ↦ᴬ<:ᶜ u₂    u₁ ⊕ u₂ = u
    // ─────────────────────────────────────────────────────────────── Coe-Split
    // Δ ⊢ t ↦ᴬ<:ᴰ u
    case SubtypeRule.Split(first, second) =>
      for {
        left <- compile(trie, first, scope)
        right <- compile(trie, second, scope)
        merged <- FiTrie.merge(left, right).mapError(CoercionError.Merge(_))
      } yield merged

    case SubtypeRule.Recursive(identity, forwardBody, reverseBody) =>
      val definition = ConversionDefinition(identity, forwardBody, reverseBody, scope.typeDepth)
      converter(definition, ConversionDirection.Forward, scope.withDefinition(definition))
        .map(suspendedIndex(_, Request.Application(trie)))

    // C(ι, d) = c
    // ──────────────────────────────────── Coe-Rec-Reference
    // C ; Δ ⊢ t ↦ᴿ<:ˢ {c ◁ ⟨app[t]⟩ ; · ; ·}
    case SubtypeRule.RecursiveReference(identity, direction) =>
      scope.bound(identity, direction) match {
        case Some(index) => Result.Ok(suspendedIndex(localVariable(index), Request.Application(trie)))
        case None =>
          // The other orientation may first be needed in a contravariant position.
          // Its fixed point closes over the already-bound forward converter. Once
          // both are bound, every reference is an application, so compilation is finite.
          converter(scope.definition(identity), direction, scope).map(suspendedIndex(_, Request.Application(trie)))
      }
  }

  private def converter(
    definition: ConversionDefinition,
    direction: ConversionDirection,
    scope: ConversionScope
  ): Result[FiTrie, CoercionError] = {
    /*
     * R = μα.A    S = μα.B    U(R) = A[α ↦ R]    U(S) = B[α ↦ S]
     * C, (ι,d) ↦ c ; Δ ; x:R ⊢ {x ◁ ⟨unfold⟩ ; · ; ·} ↦ᵁ⁽ᴿ⁾<:ᵁ⁽ˢ⁾ u
     * f = {· ; appₓ ↦ {x ▷ ∅ ; unfold ↦ u ; ·} ; ·}
     * tie(c, ⟨app⟩, f) = f′
     * ─────────────────────────────────────────────────────────────────────── Coe-Rec
     * C ; Δ ⊢ t ↦ᴿ<:ˢ {f′ ◁ ⟨app[t]⟩ ; · ; ·}
     *
     * S-Label references the conversion function, not its current receiver.
     * Reusing a receiver would repeatedly convert the first node of a stream.
     */
    val body = definition.body(direction).shiftTypeVariables(scope.typeDepth - definition.typeDepth)
    val innerScope = scope.withTermBinders(2).withBound(definition.identity, direction, 1)
    val argument = localVariable(0)
    compile(suspendedIndex(argument, Request.Unfold), body, innerScope).map { converted =>
      val function = FiTrie.route(RouteKey.Application, guardedRoute(argument, RouteKey.Unfold, converted))
      function.tieFixedPoint(
        TermVariableIndex(0),
        RootKeyExpression.concrete(RootKeySet.one(RouteKey.Application.rootKey))
      )
    }
  }

  private def localVariable(index: Int): FiTrie = {
    FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(index)))
  }

  private def guardedRoute(receiver: FiTrie, routeKey: RouteKey, continuation: FiTrie): FiTrie = {
    FiTrie.node(
      responseComputations = Set(ResponseComputation.Filter(receiver, RootKeyExpression.concrete(RootKeySet.empty))),
      routeContinuations = Map(routeKey -> continuation)
    )
  }

  private def suspendedFilter(trie: FiTrie, selectedRootKeys: RootKeyExpression): FiTrie = {
    FiTrie.response(ResponseComputation.Filter(trie, selectedRootKeys))
  }

  private def suspendedIndex(trie: FiTrie, request: Request): FiTrie = {
    FiTrie.response(ResponseComputation.Index(trie, RequestSet.one(request)))
  }

  private final case class ConversionDefinition(
    identity: RecursiveConversionId,
    forwardBody: SubtypeDerivation,
    reverseBody: Option[SubtypeDerivation],
    typeDepth: Int
  ) {
    def body(direction: ConversionDirection): SubtypeDerivation = direction match {
      case ConversionDirection.Forward => forwardBody
      case ConversionDirection.Reverse => reverseBody.getOrElse {
        throw new IllegalStateException("recursive subtyping evidence references an absent reverse conversion")
      }
    }
  }

  private final case class BoundConversion(identity: RecursiveConversionId, direction: ConversionDirection, index: Int)

  private final case class ConversionScope(
    definitions: List[ConversionDefinition],
    bindings: List[BoundConversion],
    typeDepth: Int
  ) {
    def withTermBinders(count: Int): ConversionScope = copy(bindings = bindings.map(binding =>
      binding.copy(index = binding.index + count)
    ))

    def withTypeBinder: ConversionScope = copy(typeDepth = typeDepth + 1)

    def withDefinition(definition: ConversionDefinition): ConversionScope = copy(
      definitions = definition :: definitions,
      bindings = bindings.filterNot(_.identity == definition.identity)
    )

    def withBound(identity: RecursiveConversionId, direction: ConversionDirection, index: Int): ConversionScope = {
      copy(bindings = BoundConversion(identity, direction, index) :: bindings)
    }

    def bound(identity: RecursiveConversionId, direction: ConversionDirection): Option[Int] = {
      bindings.find(binding => binding.identity == identity && binding.direction == direction).map(_.index)
    }

    def definition(identity: RecursiveConversionId): ConversionDefinition = {
      definitions.find(_.identity == identity).getOrElse {
        throw new IllegalStateException("recursive subtyping evidence contains an unbound conversion reference")
      }
    }
  }

  private object ConversionScope {
    val empty: ConversionScope = ConversionScope(Nil, Nil, 0)
  }
}
