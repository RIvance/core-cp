package cp.fitrie

import cp.primitive.{PrimitiveType, PrimitiveValue}
import cp.util.Result

enum FiTrieMergeError {
  case ConflictingTermination(
    route: List[RouteKey],
    primitiveType: PrimitiveType,
    leftPayload: PrimitiveValue,
    rightPayload: PrimitiveValue
  )

  private[fitrie] def beneath(routeKey: RouteKey): FiTrieMergeError = this match {
    case ConflictingTermination(route, primitiveType, leftPayload, rightPayload) =>
      ConflictingTermination(routeKey :: route, primitiveType, leftPayload, rightPayload)
  }
}

private[fitrie] object FiTrieAlgebra {
  def merge(left: FiTrie, right: FiTrie): Result[FiTrie, FiTrieMergeError] = {
    /*
     * t = { C₁ ; Φ₁ ; Θ₁ }    u = { C₂ ; Φ₂ ; Θ₂ }
     * ∀ι ∈ dom(Θ₁) ∩ dom(Θ₂). Θ₁(ι) = Θ₂(ι)
     * dom(Φ) = dom(Φ₁) ∪ dom(Φ₂)
     * ∀κ ∈ dom(Φ₁) − dom(Φ₂). Φ(κ) = Φ₁(κ)
     * ∀κ ∈ dom(Φ₂) − dom(Φ₁). Φ(κ) = Φ₂(κ)
     * ∀κ ∈ dom(Φ₁) ∩ dom(Φ₂). Φ₁(κ) ⊕ Φ₂(κ) = Φ(κ)
     * ───────────────────────────────────────────────────────── Merge-Node
     * t ⊕ u = { C₁ ∪ C₂ ; Φ ; Θ₁ ∪ Θ₂ }
     */
    mergeTerminations(left.terminationPayloads, right.terminationPayloads).flatMap { terminations =>
      mergeRoutes(left.routeContinuations, right.routeContinuations).map { routes =>
        FiTrie.node(
          left.responseComputations ++ right.responseComputations,
          routes,
          terminations
        )
      }
    }
  }

  def mergeAll(tries: IterableOnce[FiTrie]): Result[FiTrie, FiTrieMergeError] = {
    tries.iterator.foldLeft(Result.Ok(FiTrie.empty): Result[FiTrie, FiTrieMergeError]) {
      case (accumulated, trie) => accumulated.flatMap(merge(_, trie))
    }
  }

  def filter(trie: FiTrie, selectedRootKeys: RootKeySet): FiTrie = {
    /*
     * t = { C ; Φ ; Θ }
     * Θ′ = { ι ↦ a ∈ Θ | ι ∈ K }
     * Φ′ = { κ ↦ u ∈ Φ | κ ∈ K }
     * C′ = { { c ; · ; · } ▷ K | c ∈ C }
     * ─────────────────────────────────── Filter-Node
     * t ▷ K = { C′ ; Φ′ ; Θ′ }
     */
    val retainedRoutes = trie.routeContinuations.filter { case (routeKey, _) =>
      selectedRootKeys.contains(routeKey.rootKey)
    }
    val suspendedResponses = trie.responseComputations.map { responseComputation =>
      val nestedResponse = FiTrie.response(
        FiTrieBinding.shiftNodeReferences(responseComputation, 1, 0)
      )
      ResponseComputation.Filter(nestedResponse, RootKeyExpression.concrete(selectedRootKeys))
    }
    FiTrie.node(
      suspendedResponses,
      retainedRoutes,
      trie.terminationPayloads.restrict(selectedRootKeys)
    )
  }

  def index(trie: FiTrie, requests: RequestSet): Result[FiTrie, FiTrieMergeError] = {
    /*
     * t = { C ; Φ ; Θ }
     * app[v] ∈ Q    appₓ ↦ w ∈ Φ    w[x ↦ v] = w′
     * tapp[𝒜] ∈ Q    tappα ↦ w ∈ Φ    w[α ↦ 𝒜] = w′
     * projℓ ∈ Q selects projℓ continuations
     * ⨁ { w′ | a request in Q selects and supplies a continuation in Φ } = u
     * v = { { c ; · ; · } ◁ Q | c ∈ C ; · ; · }
     * u ⊕ v = w
     * ───────────────────────────────────────────────────────────── Index-FiTrie
     * t ◁ Q = w
     */
    val selectedContinuations = requests.requests.iterator.flatMap { request =>
      trie.routeContinuations.get(request.routeKey).map { continuation =>
        request match {
          /*
           * app[u] ∈ Q    appₓ ↦ t ∈ Φ    t[x ↦ u] = t′
           * ───────────────────────────────────────── Index-App
           * Φ ; Q selects t′
           */
          case Request.Application(argument) =>
            continuation.substituteTermVariable(TermVariableIndex(0), argument)

          // tapp[𝒜] ∈ Q    tappα ↦ t ∈ Φ    t[α ↦ 𝒜] = t′
          // ───────────────────────────────────────────── Index-TApp-Paths
          // Φ ; Q selects t′
          case Request.TypeApplication(pathInterface) => Result.Ok(
            continuation.substitutePathVariable(PathVariableIndex(0), pathInterface)
          )

          // projℓ ∈ Q    projℓ ↦ t ∈ Φ
          // ───────────────────────────── Index-Proj
          // Φ ; Q selects t
          case Request.Projection(_) => Result.Ok(continuation)
        }
      }
    }
    mergeResults(selectedContinuations).flatMap { selected =>
      val forwardedResponses = trie.responseComputations.map { responseComputation =>
        val nestedResponse = FiTrie.response(
          FiTrieBinding.shiftNodeReferences(responseComputation, 1, 0)
        )
        ResponseComputation.Index(nestedResponse, requests)
      }
      merge(selected, FiTrie.node(responseComputations = forwardedResponses))
    }
  }

  private def mergeResults(
    tries: Iterator[Result[FiTrie, FiTrieMergeError]]
  ): Result[FiTrie, FiTrieMergeError] = {
    tries.foldLeft(Result.Ok(FiTrie.empty): Result[FiTrie, FiTrieMergeError]) {
      case (accumulated, trie) =>
        accumulated.flatMap(current => trie.flatMap(merge(current, _)))
    }
  }

  private def mergeRoutes(
    left: Map[RouteKey, FiTrie],
    right: Map[RouteKey, FiTrie]
  ): Result[Map[RouteKey, FiTrie], FiTrieMergeError] = {
    (left.keySet ++ right.keySet).foldLeft(
      Result.Ok(Map.empty[RouteKey, FiTrie]): Result[Map[RouteKey, FiTrie], FiTrieMergeError]
    ) { (accumulated, routeKey) =>
      accumulated.flatMap { routes =>
        (left.get(routeKey), right.get(routeKey)) match {
          case (Some(leftContinuation), Some(rightContinuation)) =>
            merge(leftContinuation, rightContinuation)
              .mapError(_.beneath(routeKey))
              .map(continuation => routes.updated(routeKey, continuation))
          case (Some(continuation), None) =>
            Result.Ok(routes.updated(routeKey, continuation))
          case (None, Some(continuation)) =>
            Result.Ok(routes.updated(routeKey, continuation))
          case (None, None) =>
            throw new IllegalStateException("a unioned route key was absent from both route maps")
        }
      }
    }
  }

  private def mergeTerminations(
    left: TerminationPayloads,
    right: TerminationPayloads
  ): Result[TerminationPayloads, FiTrieMergeError] = {
    val conflict = left.entries.iterator.collectFirst {
      case (primitiveType, leftPayload)
          if right.get(primitiveType).exists(_ != leftPayload) =>
        FiTrieMergeError.ConflictingTermination(
          Nil,
          primitiveType,
          leftPayload,
          right.get(primitiveType).get
        )
    }
    conflict match {
      case Some(error) => Result.Err(error)
      case None => Result.Ok(TerminationPayloads.fromEntries(left.entries ++ right.entries))
    }
  }
}
