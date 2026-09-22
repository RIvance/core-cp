package cp.fitrie

import cp.util.Result

private[fitrie] object FiTrieBinding {
  def shiftTermVariables(trie: FiTrie, by: Int, cutoff: Int): FiTrie = {
    require(cutoff >= 0, "the term-variable cutoff cannot be negative")
    shiftTrieTermVariables(trie, by, cutoff)
  }

  def shiftNodeReferences(trie: FiTrie, by: Int, cutoff: Int): FiTrie = {
    require(cutoff >= 0, "the node-reference cutoff cannot be negative")
    shiftTrieNodeReferences(trie, by, cutoff)
  }

  def shiftPathVariables(trie: FiTrie, by: Int, cutoff: Int): FiTrie = {
    require(cutoff >= 0, "the path-variable cutoff cannot be negative")
    shiftTriePathVariables(trie, by, cutoff)
  }

  def shiftNodeReferences(
    responseComputation: ResponseComputation,
    by: Int,
    cutoff: Int
  ): ResponseComputation = {
    require(cutoff >= 0, "the node-reference cutoff cannot be negative")
    shiftResponseNodeReferences(responseComputation, by, cutoff)
  }

  def substituteTermVariable(
    trie: FiTrie,
    index: TermVariableIndex,
    replacement: FiTrie
  ): Result[FiTrie, FiTrieMergeError] = {
    substituteTrieTermVariable(trie, index.value, 0, replacement)
  }

  def substitutePathVariable(
    trie: FiTrie,
    index: PathVariableIndex,
    replacement: ObservationPathInterface
  ): FiTrie = {
    substituteTriePathVariable(trie, index, replacement)
  }

  def tieFixedPoint(
    trie: FiTrie,
    index: TermVariableIndex,
    selectedRootKeys: RootKeyExpression
  ): FiTrie = {
    tieTrieFixedPoint(trie, index.value, 0, 0, selectedRootKeys)
  }

  def isWellScoped(
    trie: FiTrie,
    termVariableDepth: Int,
    nodeReferenceDepth: Int,
    pathVariableDepth: Int
  ): Boolean = {
    /*
     * ∀c ∈ C. d + 1 ⊢c c    ∀(κ ↦ t) ∈ Φ. d + 1 ⊢ t
     * ───────────────────────────────────────────────── Scope-Node
     * d ⊢ { C ; Φ ; Θ }
     *
     * The Scala judgment additionally tracks anonymous application binders
     * because local term variables use De Bruijn indices.
     */
    val bodyNodeReferenceDepth = nodeReferenceDepth + 1
    trie.responseComputations.forall(
      isResponseWellScoped(_, termVariableDepth, bodyNodeReferenceDepth, pathVariableDepth)
    ) && trie.routeContinuations.forall { case (routeKey, continuation) =>
      isWellScoped(
        continuation,
        termVariableDepth + routeKey.introducedTermVariables,
        bodyNodeReferenceDepth,
        pathVariableDepth + routeKey.introducedPathVariables
      )
    }
  }

  private def shiftTrieTermVariables(trie: FiTrie, by: Int, cutoff: Int): FiTrie = {
    FiTrie.node(
      trie.responseComputations.map(shiftResponseTermVariables(_, by, cutoff)),
      trie.routeContinuations.map { case (routeKey, continuation) =>
        routeKey -> shiftTrieTermVariables(
          continuation,
          by,
          cutoff + routeKey.introducedTermVariables
        )
      },
      trie.terminationPayloads
    )
  }

  private def shiftResponseTermVariables(
    responseComputation: ResponseComputation,
    by: Int,
    cutoff: Int
  ): ResponseComputation = responseComputation match {
    case ResponseComputation.LocalVariable(index) if index.value >= cutoff =>
      ResponseComputation.LocalVariable(TermVariableIndex(shiftedIndex(index.value, by)))
    case ResponseComputation.LocalVariable(_) | ResponseComputation.Global(_) |
         ResponseComputation.StructuralReference(_) => responseComputation
    case ResponseComputation.Index(receiver, requests) =>
      ResponseComputation.Index(
        shiftTrieTermVariables(receiver, by, cutoff),
        RequestSet(requests.requests.map(shiftRequestTermVariables(_, by, cutoff)))
      )
    case ResponseComputation.Filter(receiver, selectedRootKeys) =>
      ResponseComputation.Filter(
        shiftTrieTermVariables(receiver, by, cutoff),
        selectedRootKeys
      )
    case ResponseComputation.PrimitiveOperation(operator, left, right) =>
      ResponseComputation.PrimitiveOperation(
        operator,
        shiftTrieTermVariables(left, by, cutoff),
        shiftTrieTermVariables(right, by, cutoff)
      )
    case ResponseComputation.Conditional(condition, whenTrue, whenFalse) =>
      ResponseComputation.Conditional(
        shiftTrieTermVariables(condition, by, cutoff),
        shiftTrieTermVariables(whenTrue, by, cutoff),
        shiftTrieTermVariables(whenFalse, by, cutoff)
      )
  }

  private def shiftRequestTermVariables(request: Request, by: Int, cutoff: Int): Request = request match {
    case Request.Application(argument) =>
      Request.Application(shiftTrieTermVariables(argument, by, cutoff))
    case Request.TypeApplication(_) | Request.Projection(_) | Request.Unfold => request
  }

  private def shiftTrieNodeReferences(trie: FiTrie, by: Int, cutoff: Int): FiTrie = {
    val bodyCutoff = cutoff + 1
    FiTrie.node(
      trie.responseComputations.map(shiftResponseNodeReferences(_, by, bodyCutoff)),
      trie.routeContinuations.map { case (routeKey, continuation) =>
        routeKey -> shiftTrieNodeReferences(continuation, by, bodyCutoff)
      },
      trie.terminationPayloads
    )
  }

  private def shiftResponseNodeReferences(
    responseComputation: ResponseComputation,
    by: Int,
    cutoff: Int
  ): ResponseComputation = responseComputation match {
    case ResponseComputation.StructuralReference(index) if index.value >= cutoff =>
      ResponseComputation.StructuralReference(NodeReferenceIndex(shiftedIndex(index.value, by)))
    case ResponseComputation.LocalVariable(_) | ResponseComputation.Global(_) |
         ResponseComputation.StructuralReference(_) => responseComputation
    case ResponseComputation.Index(receiver, requests) =>
      ResponseComputation.Index(
        shiftTrieNodeReferences(receiver, by, cutoff),
        RequestSet(requests.requests.map(shiftRequestNodeReferences(_, by, cutoff)))
      )
    case ResponseComputation.Filter(receiver, selectedRootKeys) =>
      ResponseComputation.Filter(
        shiftTrieNodeReferences(receiver, by, cutoff),
        selectedRootKeys
      )
    case ResponseComputation.PrimitiveOperation(operator, left, right) =>
      ResponseComputation.PrimitiveOperation(
        operator,
        shiftTrieNodeReferences(left, by, cutoff),
        shiftTrieNodeReferences(right, by, cutoff)
      )
    case ResponseComputation.Conditional(condition, whenTrue, whenFalse) =>
      ResponseComputation.Conditional(
        shiftTrieNodeReferences(condition, by, cutoff),
        shiftTrieNodeReferences(whenTrue, by, cutoff),
        shiftTrieNodeReferences(whenFalse, by, cutoff)
      )
  }

  private def shiftRequestNodeReferences(request: Request, by: Int, cutoff: Int): Request = request match {
    case Request.Application(argument) =>
      Request.Application(shiftTrieNodeReferences(argument, by, cutoff))
    case Request.TypeApplication(_) | Request.Projection(_) | Request.Unfold => request
  }

  private def shiftTriePathVariables(trie: FiTrie, by: Int, cutoff: Int): FiTrie = {
    FiTrie.node(
      trie.responseComputations.map(shiftResponsePathVariables(_, by, cutoff)),
      trie.routeContinuations.map { case (routeKey, continuation) =>
        routeKey -> shiftTriePathVariables(
          continuation,
          by,
          cutoff + routeKey.introducedPathVariables
        )
      },
      trie.terminationPayloads
    )
  }

  private def shiftResponsePathVariables(
    responseComputation: ResponseComputation,
    by: Int,
    cutoff: Int
  ): ResponseComputation = responseComputation match {
    case ResponseComputation.LocalVariable(_) | ResponseComputation.Global(_) |
         ResponseComputation.StructuralReference(_) => responseComputation
    case ResponseComputation.Index(receiver, requests) =>
      ResponseComputation.Index(
        shiftTriePathVariables(receiver, by, cutoff),
        RequestSet(requests.requests.map(shiftRequestPathVariables(_, by, cutoff)))
      )
    case ResponseComputation.Filter(receiver, selectedRootKeys) =>
      ResponseComputation.Filter(
        shiftTriePathVariables(receiver, by, cutoff),
        selectedRootKeys.shiftPathVariables(by, cutoff)
      )
    case ResponseComputation.PrimitiveOperation(operator, left, right) =>
      ResponseComputation.PrimitiveOperation(
        operator,
        shiftTriePathVariables(left, by, cutoff),
        shiftTriePathVariables(right, by, cutoff)
      )
    case ResponseComputation.Conditional(condition, whenTrue, whenFalse) =>
      ResponseComputation.Conditional(
        shiftTriePathVariables(condition, by, cutoff),
        shiftTriePathVariables(whenTrue, by, cutoff),
        shiftTriePathVariables(whenFalse, by, cutoff)
      )
  }

  private def shiftRequestPathVariables(request: Request, by: Int, cutoff: Int): Request = {
    request match {
      case Request.Application(argument) =>
        Request.Application(shiftTriePathVariables(argument, by, cutoff))
      case Request.TypeApplication(pathInterface) =>
        Request.TypeApplication(pathInterface.shiftPathVariables(by, cutoff))
      case Request.Projection(_) | Request.Unfold => request
    }
  }

  private def substituteTriePathVariable(
    trie: FiTrie,
    index: PathVariableIndex,
    replacement: ObservationPathInterface
  ): FiTrie = {
    FiTrie.node(
      trie.responseComputations.map(substituteResponsePathVariable(_, index, replacement)),
      trie.routeContinuations.map { case (routeKey, continuation) =>
        val introducedVariables = routeKey.introducedPathVariables
        routeKey -> substituteTriePathVariable(
          continuation,
          PathVariableIndex(index.value + introducedVariables),
          replacement.shiftPathVariables(introducedVariables)
        )
      },
      trie.terminationPayloads
    )
  }

  private def substituteResponsePathVariable(
    responseComputation: ResponseComputation,
    index: PathVariableIndex,
    replacement: ObservationPathInterface
  ): ResponseComputation = responseComputation match {
    case ResponseComputation.LocalVariable(_) | ResponseComputation.Global(_) |
         ResponseComputation.StructuralReference(_) => responseComputation
    case ResponseComputation.Index(receiver, requests) =>
      ResponseComputation.Index(
        substituteTriePathVariable(receiver, index, replacement),
        RequestSet(requests.requests.map(substituteRequestPathVariable(_, index, replacement)))
      )
    case ResponseComputation.Filter(receiver, selectedRootKeys) =>
      ResponseComputation.Filter(
        substituteTriePathVariable(receiver, index, replacement),
        selectedRootKeys.substitutePathVariable(index, replacement)
      )
    case ResponseComputation.PrimitiveOperation(operator, left, right) =>
      ResponseComputation.PrimitiveOperation(
        operator,
        substituteTriePathVariable(left, index, replacement),
        substituteTriePathVariable(right, index, replacement)
      )
    case ResponseComputation.Conditional(condition, whenTrue, whenFalse) =>
      ResponseComputation.Conditional(
        substituteTriePathVariable(condition, index, replacement),
        substituteTriePathVariable(whenTrue, index, replacement),
        substituteTriePathVariable(whenFalse, index, replacement)
      )
  }

  private def substituteRequestPathVariable(
    request: Request,
    index: PathVariableIndex,
    replacement: ObservationPathInterface
  ): Request = request match {
    case Request.Application(argument) =>
      Request.Application(substituteTriePathVariable(argument, index, replacement))
    case Request.TypeApplication(pathInterface) => Request.TypeApplication(
      pathInterface.substitutePathVariable(index, replacement)
    )
    case Request.Projection(_) | Request.Unfold => request
  }

  private def substituteTrieTermVariable(
    trie: FiTrie,
    targetIndex: Int,
    binderDepth: Int,
    replacement: FiTrie
  ): Result[FiTrie, FiTrieMergeError] = {
    substituteRoutes(trie.routeContinuations, targetIndex, binderDepth, replacement).flatMap { routes =>
      val knownStructure = FiTrie.node(
        routeContinuations = routes,
        terminationPayloads = trie.terminationPayloads
      )
      val responseResults = trie.responseComputations.iterator.map(
        substituteResponseTermVariable(_, targetIndex, binderDepth, replacement)
      )
      mergeSubstitutionResults(knownStructure, responseResults)
    }
  }

  private def tieTrieFixedPoint(
    trie: FiTrie,
    fixedPointIndex: Int,
    termVariableDepth: Int,
    nodeReferenceDepth: Int,
    selectedRootKeys: RootKeyExpression
  ): FiTrie = {
    /*
     * C′ = { c′ | c ∈ C, Ξ ; 𝒦 ; x ; d + 1 ⊢c c ties c′ }
     * Φ′ = { κ ↦ t′ | κ ↦ t ∈ Φ, Ξ ; 𝒦 ; x ; d + 1 ⊢Φ κ ↦ t ties κ ↦ t′ }
     * ─────────────────────────────────────────────────────────────────── Tie-Node
     * Ξ ; 𝒦 ; x ; d ⊢ { C ; Φ ; Θ } ties { C′ ; Φ′ ; Θ }
     */
    val bodyNodeReferenceDepth = nodeReferenceDepth + 1
    FiTrie.node(
      trie.responseComputations.map(tieResponseFixedPoint(
        _,
        fixedPointIndex,
        termVariableDepth,
        bodyNodeReferenceDepth,
        selectedRootKeys
      )),
      trie.routeContinuations.map { case (routeKey, continuation) =>
        val introducedPathVariables = routeKey.introducedPathVariables
        routeKey -> tieTrieFixedPoint(
          continuation,
          fixedPointIndex,
          termVariableDepth + routeKey.introducedTermVariables,
          bodyNodeReferenceDepth,
          selectedRootKeys.shiftPathVariables(introducedPathVariables)
        )
      },
      trie.terminationPayloads
    )
  }

  private def tieResponseFixedPoint(
    responseComputation: ResponseComputation,
    fixedPointIndex: Int,
    termVariableDepth: Int,
    nodeReferenceDepth: Int,
    selectedRootKeys: RootKeyExpression
  ): ResponseComputation = responseComputation match {
    /*
     * Ξ ⊢ {ref d ; · ; ·} ▶[𝒜] u
     * ───────────────────────────────── Tie-Hit-Paths
     * Ξ ; 𝒜 ; x ; d ⊢c x ↓ u
     */
    case ResponseComputation.LocalVariable(index)
        if index.value == fixedPointIndex + termVariableDepth =>
      ResponseComputation.Filter(
        FiTrie.response(ResponseComputation.StructuralReference(
          NodeReferenceIndex(nodeReferenceDepth)
        )),
        selectedRootKeys
      )

    case ResponseComputation.LocalVariable(index)
        if index.value > fixedPointIndex + termVariableDepth =>
      ResponseComputation.LocalVariable(TermVariableIndex(index.value - 1))

    // y ≠ x
    // ───────────────────── Tie-Miss
    // K ; x ; d ⊢c y ties y
    case ResponseComputation.LocalVariable(_) => responseComputation

    // ───────────────────────────────────── Tie-Global
    // K ; x ; d ⊢c global g ties global g
    case ResponseComputation.Global(_) => responseComputation

    // ─────────────────────────── Tie-Ref
    // K ; x ; d ⊢c ref k ties ref k
    case ResponseComputation.StructuralReference(_) => responseComputation

    /*
     * K ; x ; d ⊢ t ties t′
     * Q′ = { app[u′] | app[u] ∈ Q, K ; x ; d ⊢ u ties u′ }
     *    ∪ { tapp[] | tapp[] ∈ Q }
     *    ∪ { projℓ[] | projℓ[] ∈ Q }
     * ───────────────────────────────────────────────────────── Tie-Index
     * K ; x ; d ⊢c t ◁ Q ties t′ ◁ Q′
    */
    case ResponseComputation.Index(receiver, requests) =>
      ResponseComputation.Index(
        tieTrieFixedPoint(
          receiver,
          fixedPointIndex,
          termVariableDepth,
          nodeReferenceDepth,
          selectedRootKeys
        ),
        RequestSet(requests.requests.map {
          case Request.Application(argument) => Request.Application(tieTrieFixedPoint(
            argument,
            fixedPointIndex,
            termVariableDepth,
            nodeReferenceDepth,
            selectedRootKeys
          ))
          case request @ (Request.TypeApplication(_) | Request.Projection(_) | Request.Unfold) => request
        })
      )

    // K ; x ; d ⊢ t ties t′
    // ─────────────────────────────────── Tie-Filter
    // K ; x ; d ⊢c t ▷ K′ ties t′ ▷ K′
    case ResponseComputation.Filter(receiver, retainedRootKeys) =>
      ResponseComputation.Filter(
        tieTrieFixedPoint(
          receiver,
          fixedPointIndex,
          termVariableDepth,
          nodeReferenceDepth,
          selectedRootKeys
        ),
        retainedRootKeys
      )

    /*
     * K ; x ; d ⊢ t₁ ties t₁′    K ; x ; d ⊢ t₂ ties t₂′
     * ───────────────────────────────────────────────────── Tie-Primitive
     * K ; x ; d ⊢c operator(t₁, t₂) ties operator(t₁′, t₂′)
    */
    case ResponseComputation.PrimitiveOperation(operator, left, right) =>
      ResponseComputation.PrimitiveOperation(
        operator,
        tieTrieFixedPoint(
          left,
          fixedPointIndex,
          termVariableDepth,
          nodeReferenceDepth,
          selectedRootKeys
        ),
        tieTrieFixedPoint(
          right,
          fixedPointIndex,
          termVariableDepth,
          nodeReferenceDepth,
          selectedRootKeys
        )
      )

    /*
     * K ; x ; d ⊢ t₀ ties t₀′    K ; x ; d ⊢ t₁ ties t₁′
     * K ; x ; d ⊢ t₂ ties t₂′
     * ───────────────────────────────────────────────────── Tie-Conditional
     * K ; x ; d ⊢c if t₀ then t₁ else t₂ ties if t₀′ then t₁′ else t₂′
    */
    case ResponseComputation.Conditional(condition, whenTrue, whenFalse) =>
      ResponseComputation.Conditional(
        tieTrieFixedPoint(
          condition,
          fixedPointIndex,
          termVariableDepth,
          nodeReferenceDepth,
          selectedRootKeys
        ),
        tieTrieFixedPoint(
          whenTrue,
          fixedPointIndex,
          termVariableDepth,
          nodeReferenceDepth,
          selectedRootKeys
        ),
        tieTrieFixedPoint(
          whenFalse,
          fixedPointIndex,
          termVariableDepth,
          nodeReferenceDepth,
          selectedRootKeys
        )
      )
  }

  private def substituteResponseTermVariable(
    responseComputation: ResponseComputation,
    targetIndex: Int,
    binderDepth: Int,
    replacement: FiTrie
  ): Result[FiTrie, FiTrieMergeError] = responseComputation match {
    // ─────────────── Sub-Hit
    // x[x ↦ u] = u
    case ResponseComputation.LocalVariable(variable)
        if variable.value == targetIndex + binderDepth =>
      Result.Ok(replacement.shiftTermVariables(binderDepth))

    // y ≠ x
    // ───────────────────────── Sub-Miss
    // y[x ↦ u] = { y ; · ; · }
    case ResponseComputation.LocalVariable(variable)
        if variable.value < targetIndex + binderDepth =>
      Result.Ok(FiTrie.response(responseComputation))
    case ResponseComputation.LocalVariable(variable) =>
      Result.Ok(FiTrie.response(ResponseComputation.LocalVariable(
        TermVariableIndex(variable.value - 1)
      )))

    // ───────────────────────────────────────────── Sub-Global
    // (global g)[x ↦ u] = { global g ; · ; · }
    case ResponseComputation.Global(_) => Result.Ok(FiTrie.response(responseComputation))

    // ───────────────────────────── Sub-Ref
    // (ref k)[x ↦ u] = { ref k ; · ; · }
    case ResponseComputation.StructuralReference(_) =>
      Result.Ok(FiTrie.response(responseComputation))

    /*
     * t[x ↦ u] = t′
     * Q′ = { app[v′] | app[v] ∈ Q, v[x ↦ u] = v′ }
     *    ∪ { tapp[] | tapp[] ∈ Q }
     *    ∪ { projℓ[] | projℓ[] ∈ Q }
     * ─────────────────────────────────────────────────── Sub-Index
     * (t ◁ Q)[x ↦ u] = { t′ ◁ Q′ ; · ; · }
     */
    case ResponseComputation.Index(receiver, requests) =>
      substituteTrieTermVariable(receiver, targetIndex, binderDepth, replacement).flatMap { substitutedReceiver =>
        substituteRequests(requests, targetIndex, binderDepth, replacement).map { substitutedRequests =>
          FiTrie.response(ResponseComputation.Index(substitutedReceiver, substitutedRequests))
        }
      }

    // t[x ↦ u] = t′
    // ─────────────────────────────────────── Sub-Filter
    // (t ▷ K)[x ↦ u] = { t′ ▷ K ; · ; · }
    case ResponseComputation.Filter(receiver, selectedRootKeys) =>
      substituteTrieTermVariable(receiver, targetIndex, binderDepth, replacement)
        .map(substitutedReceiver =>
          FiTrie.response(ResponseComputation.Filter(substitutedReceiver, selectedRootKeys))
        )

    /*
     * t₁[x ↦ u] = t₁′    t₂[x ↦ u] = t₂′
     * ───────────────────────────────────── Sub-Primitive
     * operator(t₁, t₂)[x ↦ u] = { operator(t₁′, t₂′) ; · ; · }
     */
    case ResponseComputation.PrimitiveOperation(operator, left, right) =>
      for {
        substitutedLeft <- substituteTrieTermVariable(left, targetIndex, binderDepth, replacement)
        substitutedRight <- substituteTrieTermVariable(right, targetIndex, binderDepth, replacement)
      } yield FiTrie.response(ResponseComputation.PrimitiveOperation(
        operator,
        substitutedLeft,
        substitutedRight
      ))

    /*
     * t₀[x ↦ u] = t₀′    t₁[x ↦ u] = t₁′    t₂[x ↦ u] = t₂′
     * ─────────────────────────────────────────────────────── Sub-Conditional
     * (if t₀ then t₁ else t₂)[x ↦ u]
     *   = { if t₀′ then t₁′ else t₂′ ; · ; · }
     */
    case ResponseComputation.Conditional(condition, whenTrue, whenFalse) =>
      for {
        substitutedCondition <- substituteTrieTermVariable(
          condition,
          targetIndex,
          binderDepth,
          replacement
        )
        substitutedTrue <- substituteTrieTermVariable(whenTrue, targetIndex, binderDepth, replacement)
        substitutedFalse <- substituteTrieTermVariable(whenFalse, targetIndex, binderDepth, replacement)
      } yield FiTrie.response(ResponseComputation.Conditional(
        substitutedCondition,
        substitutedTrue,
        substitutedFalse
      ))
  }

  private def substituteRoutes(
    routes: Map[RouteKey, FiTrie],
    targetIndex: Int,
    binderDepth: Int,
    replacement: FiTrie
  ): Result[Map[RouteKey, FiTrie], FiTrieMergeError] = {
    routes.foldLeft(
      Result.Ok(Map.empty[RouteKey, FiTrie]): Result[Map[RouteKey, FiTrie], FiTrieMergeError]
    ) { case (accumulated, (routeKey, continuation)) =>
      accumulated.flatMap { substitutedRoutes =>
        substituteTrieTermVariable(
          continuation,
          targetIndex,
          binderDepth + routeKey.introducedTermVariables,
          replacement
        ).map(substitutedRoutes.updated(routeKey, _))
      }
    }
  }

  private def substituteRequests(
    requests: RequestSet,
    targetIndex: Int,
    binderDepth: Int,
    replacement: FiTrie
  ): Result[RequestSet, FiTrieMergeError] = {
    requests.requests.foldLeft(
      Result.Ok(Set.empty[Request]): Result[Set[Request], FiTrieMergeError]
    ) { (accumulated, request) =>
      accumulated.flatMap { substitutedRequests =>
        request match {
          case Request.Application(argument) =>
            substituteTrieTermVariable(argument, targetIndex, binderDepth, replacement)
              .map(substitutedArgument =>
                substitutedRequests + Request.Application(substitutedArgument)
              )
          case Request.TypeApplication(_) | Request.Projection(_) | Request.Unfold =>
            Result.Ok(substitutedRequests + request)
        }
      }
    }.map(RequestSet(_))
  }

  private def mergeSubstitutionResults(
    knownStructure: FiTrie,
    responseResults: Iterator[Result[FiTrie, FiTrieMergeError]]
  ): Result[FiTrie, FiTrieMergeError] = {
    responseResults.foldLeft(
      Result.Ok(knownStructure): Result[FiTrie, FiTrieMergeError]
    ) {
      case (accumulated, responseResult) =>
        accumulated.flatMap(current => responseResult.flatMap(FiTrie.merge(current, _)))
    }
  }

  private def isResponseWellScoped(
    responseComputation: ResponseComputation,
    termVariableDepth: Int,
    nodeReferenceDepth: Int,
    pathVariableDepth: Int
  ): Boolean = responseComputation match {
    // i < |Γ|
    // ───────────── Scope-Var
    // Γ ; d ⊢c i
    case ResponseComputation.LocalVariable(index) => index.value < termVariableDepth

    // ───────────────────── Scope-Global
    // Γ ; d ⊢c global g
    case ResponseComputation.Global(_) => true

    // k < d
    // ───────────── Scope-Ref
    // d ⊢c ref k
    case ResponseComputation.StructuralReference(index) => index.value < nodeReferenceDepth

    /*
     * d ⊢ t    ∀app[u] ∈ Q. d ⊢ u
     * ───────────────────────────── Scope-Index
     * d ⊢c t ◁ Q
    */
    case ResponseComputation.Index(receiver, requests) =>
      isWellScoped(receiver, termVariableDepth, nodeReferenceDepth, pathVariableDepth) &&
        requests.requests.forall {
          case Request.Application(argument) =>
            isWellScoped(argument, termVariableDepth, nodeReferenceDepth, pathVariableDepth)
          case Request.TypeApplication(pathInterface) =>
            pathInterface.isWellScoped(pathVariableDepth)
          case Request.Projection(_) | Request.Unfold => true
        }
    // d ⊢ t
    // ───────────── Scope-Filter
    // d ⊢c t ▷ K
    case ResponseComputation.Filter(receiver, selectedRootKeys) =>
      isWellScoped(receiver, termVariableDepth, nodeReferenceDepth, pathVariableDepth) &&
        selectedRootKeys.isWellScoped(pathVariableDepth)

    // Γ ; d ⊢ t₁    Γ ; d ⊢ t₂
    // ────────────────────────── Scope-Primitive
    // Γ ; d ⊢c operator(t₁, t₂)
    case ResponseComputation.PrimitiveOperation(_, left, right) =>
      isWellScoped(left, termVariableDepth, nodeReferenceDepth, pathVariableDepth) &&
        isWellScoped(right, termVariableDepth, nodeReferenceDepth, pathVariableDepth)

    // Γ ; d ⊢ t₀    Γ ; d ⊢ t₁    Γ ; d ⊢ t₂
    // ─────────────────────────────────────── Scope-Conditional
    // Γ ; d ⊢c if t₀ then t₁ else t₂
    case ResponseComputation.Conditional(condition, whenTrue, whenFalse) =>
      isWellScoped(condition, termVariableDepth, nodeReferenceDepth, pathVariableDepth) &&
        isWellScoped(whenTrue, termVariableDepth, nodeReferenceDepth, pathVariableDepth) &&
        isWellScoped(whenFalse, termVariableDepth, nodeReferenceDepth, pathVariableDepth)
  }

  private def shiftedIndex(index: Int, by: Int): Int = {
    val shifted = index + by
    require(shifted >= 0, s"de Bruijn shift would create a negative index: $index + $by")
    shifted
  }
}
