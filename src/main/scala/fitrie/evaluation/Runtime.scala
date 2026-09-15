package cp.fitrie.evaluation

import cp.fitrie.*
import cp.naming.{FieldLabel, Identifier}
import cp.primitive.{BinaryOperator, PrimitiveType, PrimitiveValue}
import cp.util.Result

import scala.annotation.tailrec

/**
 * Evaluation closes scoped node references into immutable thunks before any
 * trie is reduced. A continuation can therefore escape its lexical parent
 * while retaining the same structural edge, without addresses, mutation, or
 * reference-rebasing terms in the target language.
 */
private[evaluation] final class RuntimeTrie(
  val responseComputations: Set[RuntimeResponseComputation],
  val routeContinuations: Map[RouteKey, RuntimeTrie],
  val terminationPayloads: TerminationPayloads
)

private[evaluation] sealed trait RuntimeResponseComputation

private[evaluation] final class RuntimeLocalVariable(val index: TermVariableIndex)
    extends RuntimeResponseComputation

private[evaluation] final class RuntimeGlobal(val identifier: Identifier)
    extends RuntimeResponseComputation

private[evaluation] final class RuntimeStructuralReference(val target: () => RuntimeTrie)
    extends RuntimeResponseComputation

private[evaluation] final class RuntimeIndex(val receiver: RuntimeTrie, val requests: RuntimeRequestSet)
    extends RuntimeResponseComputation

private[evaluation] final class RuntimeFilter(val receiver: RuntimeTrie, val selectedRootKeys: RootKeyExpression)
    extends RuntimeResponseComputation

private[evaluation] final class RuntimePrimitiveOperation(
  val operator: BinaryOperator,
  val left: RuntimeTrie,
  val right: RuntimeTrie
) extends RuntimeResponseComputation

private[evaluation] final class RuntimeConditional(
  val condition: RuntimeTrie,
  val whenTrue: RuntimeTrie,
  val whenFalse: RuntimeTrie
) extends RuntimeResponseComputation

private[evaluation] sealed trait RuntimeRequest {
  def routeKey: RouteKey
}

private[evaluation] final class RuntimeApplicationRequest(
  val argument: RuntimeTrie
) extends RuntimeRequest {
  override val routeKey: RouteKey = RouteKey.Application
}

private[evaluation] final case class RuntimeTypeApplicationRequest(
  pathInterface: ObservationPathInterface
) extends RuntimeRequest {
  override val routeKey: RouteKey = RouteKey.TypeApplication
}

private[evaluation] final case class RuntimeProjectionRequest(label: FieldLabel) extends RuntimeRequest {
  override val routeKey: RouteKey = RouteKey.Projection(label)
}

private[evaluation] final case class RuntimeRequestSet(requests: Set[RuntimeRequest])

private[evaluation] final case class RuntimeGlobalEnvironment(definitions: Map[Identifier, RuntimeTrie]) {
  def lookup(identifier: Identifier): Option[RuntimeTrie] = definitions.get(identifier)
}

private[evaluation] final case class CompiledRuntime(
  entry: RuntimeTrie,
  globalEnvironment: RuntimeGlobalEnvironment
)

private[evaluation] object RuntimeCompiler {
  def compile(
    entry: FiTrie,
    globalEnvironment: GlobalEnvironment
  ): Result[CompiledRuntime, EvaluationError] = {
    if (!entry.isWellScoped()) {
      Result.Err(EvaluationError.IllScopedTrie(entry))
    } else {
      globalEnvironment.definitions.collectFirst {
        case (identifier, definition) if !definition.isWellScoped() =>
          EvaluationError.IllScopedGlobal(identifier, definition)
      } match {
        case Some(error) => Result.Err(error)
        case None =>
          val compiledGlobals = globalEnvironment.definitions.map { case (identifier, definition) =>
            identifier -> close(definition, Nil)
          }
          Result.Ok(CompiledRuntime(
            close(entry, Nil),
            RuntimeGlobalEnvironment(compiledGlobals)
          ))
      }
    }
  }

  private def close(
    trie: FiTrie,
    ancestors: List[() => RuntimeTrie]
  ): RuntimeTrie = {
    lazy val current: RuntimeTrie = {
      val currentBinding = () => current
      val responseAncestors = currentBinding :: ancestors
      new RuntimeTrie(
        trie.responseComputations.map(closeResponse(_, responseAncestors)),
        trie.routeContinuations.map { case (routeKey, continuation) =>
          routeKey -> close(continuation, responseAncestors)
        },
        trie.terminationPayloads
      )
    }
    current
  }

  private def closeResponse(
    responseComputation: ResponseComputation,
    ancestors: List[() => RuntimeTrie]
  ): RuntimeResponseComputation = responseComputation match {
    case ResponseComputation.LocalVariable(index) => new RuntimeLocalVariable(index)
    case ResponseComputation.Global(identifier) => new RuntimeGlobal(identifier)
    case ResponseComputation.StructuralReference(index) =>
      new RuntimeStructuralReference(ancestors(index.value))
    case ResponseComputation.Index(receiver, requests) =>
      new RuntimeIndex(close(receiver, ancestors), closeRequests(requests, ancestors))
    case ResponseComputation.Filter(receiver, selectedRootKeys) =>
      new RuntimeFilter(close(receiver, ancestors), selectedRootKeys)
    case ResponseComputation.PrimitiveOperation(operator, left, right) =>
      new RuntimePrimitiveOperation(
        operator,
        close(left, ancestors),
        close(right, ancestors)
      )
    case ResponseComputation.Conditional(condition, whenTrue, whenFalse) =>
      new RuntimeConditional(
        close(condition, ancestors),
        close(whenTrue, ancestors),
        close(whenFalse, ancestors)
      )
  }

  private def closeRequests(
    requests: RequestSet,
    ancestors: List[() => RuntimeTrie]
  ): RuntimeRequestSet = RuntimeRequestSet(requests.requests.map {
    case Request.Application(argument) =>
      new RuntimeApplicationRequest(close(argument, ancestors))
    case Request.TypeApplication(pathInterface) => RuntimeTypeApplicationRequest(pathInterface)
    case Request.Projection(label) => RuntimeProjectionRequest(label)
  })
}

private object RuntimeTrie {
  val empty: RuntimeTrie = node()

  def node(
    responseComputations: Set[RuntimeResponseComputation] = Set.empty,
    routeContinuations: Map[RouteKey, RuntimeTrie] = Map.empty,
    terminationPayloads: TerminationPayloads = TerminationPayloads.empty
  ): RuntimeTrie = new RuntimeTrie(
    responseComputations,
    routeContinuations,
    terminationPayloads
  )

  def response(responseComputation: RuntimeResponseComputation): RuntimeTrie = {
    node(responseComputations = Set(responseComputation))
  }

  def termination(payload: PrimitiveValue): RuntimeTrie = {
    node(terminationPayloads = TerminationPayloads.one(payload))
  }

  def merge(
    left: RuntimeTrie,
    right: RuntimeTrie
  ): Result[RuntimeTrie, FiTrieMergeError] = {
    if (left eq right) {
      Result.Ok(left)
    } else {
      mergeTerminations(left.terminationPayloads, right.terminationPayloads).flatMap { terminations =>
        mergeRoutes(left.routeContinuations, right.routeContinuations).map { routes =>
          node(
            left.responseComputations ++ right.responseComputations,
            routes,
            terminations
          )
        }
      }
    }
  }

  def filter(trie: RuntimeTrie, selectedRootKeys: RootKeySet): RuntimeTrie = {
    /*
     * t = { C ; Φ ; Θ }
     * Θ′ = { ι ↦ a ∈ Θ | ι ∈ K }
     * Φ′ = { κ ↦ u ∈ Φ | κ ∈ K }
     * C′ = { { c ; · ; · } ▷ K | c ∈ C }
     * ─────────────────────────────────── Filter-Node
     * t ▷ K = { C′ ; Φ′ ; Θ′ }
     */
    node(
      trie.responseComputations.map(responseComputation =>
        new RuntimeFilter(
          response(responseComputation),
          RootKeyExpression.concrete(selectedRootKeys)
        )
      ),
      trie.routeContinuations.filter { case (routeKey, _) =>
        selectedRootKeys.contains(routeKey.rootKey)
      },
      trie.terminationPayloads.restrict(selectedRootKeys)
    )
  }

  def index(
    trie: RuntimeTrie,
    requests: RuntimeRequestSet
  ): Result[RuntimeTrie, FiTrieMergeError] = {
    val selectedContinuations = requests.requests.iterator.flatMap { request =>
      trie.routeContinuations.get(request.routeKey).map { continuation =>
        request match {
          /*
           * app[u] ∈ Q    appₓ ↦ t ∈ Φ    t[x ↦ u] = t′
           * ───────────────────────────────────────── Index-App
           * Φ ; Q selects t′
           */
          case application: RuntimeApplicationRequest =>
            RuntimeBinding.substituteTermVariable(continuation, 0, application.argument)

          // tapp[𝒜] ∈ Q    tappα ↦ t ∈ Φ    t[α ↦ 𝒜] = t′
          // ───────────────────────────────────────────── Index-TApp-Paths
          // Φ ; Q selects t′
          case typeApplication: RuntimeTypeApplicationRequest => Result.Ok(
            RuntimeBinding.substitutePathVariable(
              continuation,
              PathVariableIndex(0),
              typeApplication.pathInterface
            )
          )

          // projℓ ∈ Q    projℓ ↦ t ∈ Φ
          // ───────────────────────────── Index-Proj
          // Φ ; Q selects t
          case _: RuntimeProjectionRequest => Result.Ok(continuation)
        }
      }
    }
    mergeResults(selectedContinuations).flatMap { selected =>
      val forwarded = node(responseComputations = trie.responseComputations.map(
        responseComputation => new RuntimeIndex(response(responseComputation), requests)
      ))
      merge(selected, forwarded)
    }
  }

  private def mergeResults(
    tries: Iterator[Result[RuntimeTrie, FiTrieMergeError]]
  ): Result[RuntimeTrie, FiTrieMergeError] = {
    tries.foldLeft(Result.Ok(empty): Result[RuntimeTrie, FiTrieMergeError]) {
      case (accumulated, trie) =>
        accumulated.flatMap(current => trie.flatMap(merge(current, _)))
    }
  }

  private def mergeRoutes(
    left: Map[RouteKey, RuntimeTrie],
    right: Map[RouteKey, RuntimeTrie]
  ): Result[Map[RouteKey, RuntimeTrie], FiTrieMergeError] = {
    (left.keySet ++ right.keySet).foldLeft(
      Result.Ok(Map.empty[RouteKey, RuntimeTrie]): Result[Map[RouteKey, RuntimeTrie], FiTrieMergeError]
    ) { (accumulated, routeKey) =>
      accumulated.flatMap { routes =>
        (left.get(routeKey), right.get(routeKey)) match {
          case (Some(leftContinuation), Some(rightContinuation)) =>
            merge(leftContinuation, rightContinuation)
              .mapError(_.beneath(routeKey))
              .map(continuation => routes.updated(routeKey, continuation))
          case (Some(continuation), None) => Result.Ok(routes.updated(routeKey, continuation))
          case (None, Some(continuation)) => Result.Ok(routes.updated(routeKey, continuation))
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

private object RuntimeBinding {
  def substituteTermVariable(
    trie: RuntimeTrie,
    index: Int,
    replacement: RuntimeTrie
  ): Result[RuntimeTrie, FiTrieMergeError] = {
    substituteTrieTermVariable(trie, index, 0, replacement)
  }

  def substitutePathVariable(
    trie: RuntimeTrie,
    index: PathVariableIndex,
    replacement: ObservationPathInterface
  ): RuntimeTrie = {
    substituteTriePathVariable(trie, index, replacement)
  }

  private def substituteTriePathVariable(
    trie: RuntimeTrie,
    index: PathVariableIndex,
    replacement: ObservationPathInterface
  ): RuntimeTrie = {
    RuntimeTrie.node(
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
    responseComputation: RuntimeResponseComputation,
    index: PathVariableIndex,
    replacement: ObservationPathInterface
  ): RuntimeResponseComputation = responseComputation match {
    case _: RuntimeLocalVariable | _: RuntimeGlobal | _: RuntimeStructuralReference =>
      responseComputation
    case indexing: RuntimeIndex => new RuntimeIndex(
      substituteTriePathVariable(indexing.receiver, index, replacement),
      RuntimeRequestSet(indexing.requests.requests.map(
        substituteRequestPathVariable(_, index, replacement)
      ))
    )
    case filtering: RuntimeFilter => new RuntimeFilter(
      substituteTriePathVariable(filtering.receiver, index, replacement),
      filtering.selectedRootKeys.substitutePathVariable(index, replacement)
    )
    case primitive: RuntimePrimitiveOperation => new RuntimePrimitiveOperation(
      primitive.operator,
      substituteTriePathVariable(primitive.left, index, replacement),
      substituteTriePathVariable(primitive.right, index, replacement)
    )
    case conditional: RuntimeConditional => new RuntimeConditional(
      substituteTriePathVariable(conditional.condition, index, replacement),
      substituteTriePathVariable(conditional.whenTrue, index, replacement),
      substituteTriePathVariable(conditional.whenFalse, index, replacement)
    )
  }

  private def substituteRequestPathVariable(
    request: RuntimeRequest,
    index: PathVariableIndex,
    replacement: ObservationPathInterface
  ): RuntimeRequest = request match {
    case application: RuntimeApplicationRequest => new RuntimeApplicationRequest(
      substituteTriePathVariable(application.argument, index, replacement)
    )
    case typeApplication: RuntimeTypeApplicationRequest => RuntimeTypeApplicationRequest(
      typeApplication.pathInterface.substitutePathVariable(index, replacement)
    )
    case _: RuntimeProjectionRequest => request
  }

  private def substituteTrieTermVariable(
    trie: RuntimeTrie,
    targetIndex: Int,
    binderDepth: Int,
    replacement: RuntimeTrie
  ): Result[RuntimeTrie, FiTrieMergeError] = {
    substituteRoutes(trie.routeContinuations, targetIndex, binderDepth, replacement).flatMap { routes =>
      val knownStructure = RuntimeTrie.node(
        routeContinuations = routes,
        terminationPayloads = trie.terminationPayloads
      )
      trie.responseComputations.iterator.map(
        substituteResponse(_, targetIndex, binderDepth, replacement)
      ).foldLeft(Result.Ok(knownStructure): Result[RuntimeTrie, FiTrieMergeError]) {
        case (accumulated, responseResult) =>
          accumulated.flatMap(current => responseResult.flatMap(RuntimeTrie.merge(current, _)))
      }
    }
  }

  private def substituteResponse(
    responseComputation: RuntimeResponseComputation,
    targetIndex: Int,
    binderDepth: Int,
    replacement: RuntimeTrie
  ): Result[RuntimeTrie, FiTrieMergeError] = responseComputation match {
    case variable: RuntimeLocalVariable
        if variable.index.value == targetIndex + binderDepth =>
      Result.Ok(shiftTermVariables(replacement, binderDepth, 0))
    case variable: RuntimeLocalVariable
        if variable.index.value < targetIndex + binderDepth =>
      Result.Ok(RuntimeTrie.response(responseComputation))
    case variable: RuntimeLocalVariable =>
      Result.Ok(RuntimeTrie.response(new RuntimeLocalVariable(
        TermVariableIndex(variable.index.value - 1)
      )))
    case _: RuntimeGlobal | _: RuntimeStructuralReference =>
      Result.Ok(RuntimeTrie.response(responseComputation))
    case indexing: RuntimeIndex =>
      substituteTrieTermVariable(indexing.receiver, targetIndex, binderDepth, replacement).flatMap { receiver =>
        substituteRequests(indexing.requests, targetIndex, binderDepth, replacement).map { requests =>
          RuntimeTrie.response(new RuntimeIndex(receiver, requests))
        }
      }
    case filtering: RuntimeFilter =>
      substituteTrieTermVariable(filtering.receiver, targetIndex, binderDepth, replacement)
        .map(receiver =>
          RuntimeTrie.response(new RuntimeFilter(receiver, filtering.selectedRootKeys))
        )
    case primitive: RuntimePrimitiveOperation =>
      for {
        left <- substituteTrieTermVariable(primitive.left, targetIndex, binderDepth, replacement)
        right <- substituteTrieTermVariable(primitive.right, targetIndex, binderDepth, replacement)
      } yield RuntimeTrie.response(new RuntimePrimitiveOperation(primitive.operator, left, right))
    case conditional: RuntimeConditional =>
      for {
        condition <- substituteTrieTermVariable(
          conditional.condition,
          targetIndex,
          binderDepth,
          replacement
        )
        whenTrue <- substituteTrieTermVariable(
          conditional.whenTrue,
          targetIndex,
          binderDepth,
          replacement
        )
        whenFalse <- substituteTrieTermVariable(
          conditional.whenFalse,
          targetIndex,
          binderDepth,
          replacement
        )
      } yield RuntimeTrie.response(new RuntimeConditional(condition, whenTrue, whenFalse))
  }

  private def substituteRoutes(
    routes: Map[RouteKey, RuntimeTrie],
    targetIndex: Int,
    binderDepth: Int,
    replacement: RuntimeTrie
  ): Result[Map[RouteKey, RuntimeTrie], FiTrieMergeError] = {
    routes.foldLeft(
      Result.Ok(Map.empty[RouteKey, RuntimeTrie]): Result[Map[RouteKey, RuntimeTrie], FiTrieMergeError]
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
    requests: RuntimeRequestSet,
    targetIndex: Int,
    binderDepth: Int,
    replacement: RuntimeTrie
  ): Result[RuntimeRequestSet, FiTrieMergeError] = {
    requests.requests.foldLeft(
      Result.Ok(Set.empty[RuntimeRequest]): Result[Set[RuntimeRequest], FiTrieMergeError]
    ) { (accumulated, request) =>
      accumulated.flatMap { substitutedRequests =>
        request match {
          case application: RuntimeApplicationRequest =>
            substituteTrieTermVariable(application.argument, targetIndex, binderDepth, replacement)
              .map(argument => substitutedRequests + new RuntimeApplicationRequest(argument))
          case _: RuntimeTypeApplicationRequest | _: RuntimeProjectionRequest =>
            Result.Ok(substitutedRequests + request)
        }
      }
    }.map(RuntimeRequestSet(_))
  }

  private def shiftTermVariables(trie: RuntimeTrie, by: Int, cutoff: Int): RuntimeTrie = {
    RuntimeTrie.node(
      trie.responseComputations.map(shiftResponse(_, by, cutoff)),
      trie.routeContinuations.map { case (routeKey, continuation) =>
        routeKey -> shiftTermVariables(
          continuation,
          by,
          cutoff + routeKey.introducedTermVariables
        )
      },
      trie.terminationPayloads
    )
  }

  private def shiftResponse(
    responseComputation: RuntimeResponseComputation,
    by: Int,
    cutoff: Int
  ): RuntimeResponseComputation = responseComputation match {
    case variable: RuntimeLocalVariable if variable.index.value >= cutoff =>
      new RuntimeLocalVariable(TermVariableIndex(variable.index.value + by))
    case _: RuntimeLocalVariable | _: RuntimeGlobal | _: RuntimeStructuralReference =>
      responseComputation
    case indexing: RuntimeIndex =>
      new RuntimeIndex(
        shiftTermVariables(indexing.receiver, by, cutoff),
        RuntimeRequestSet(indexing.requests.requests.map {
          case application: RuntimeApplicationRequest =>
            new RuntimeApplicationRequest(shiftTermVariables(application.argument, by, cutoff))
          case request => request
        })
      )
    case filtering: RuntimeFilter =>
      new RuntimeFilter(
        shiftTermVariables(filtering.receiver, by, cutoff),
        filtering.selectedRootKeys
      )
    case primitive: RuntimePrimitiveOperation =>
      new RuntimePrimitiveOperation(
        primitive.operator,
        shiftTermVariables(primitive.left, by, cutoff),
        shiftTermVariables(primitive.right, by, cutoff)
      )
    case conditional: RuntimeConditional =>
      new RuntimeConditional(
        shiftTermVariables(conditional.condition, by, cutoff),
        shiftTermVariables(conditional.whenTrue, by, cutoff),
        shiftTermVariables(conditional.whenFalse, by, cutoff)
      )
  }
}

private[evaluation] object RuntimeEvaluation {
  def step(
    trie: RuntimeTrie,
    globalEnvironment: RuntimeGlobalEnvironment
  ): Result[Option[RuntimeTrie], EvaluationError] = {
    findResponse(
      trie.responseComputations.toList,
      globalEnvironment,
      None
    ).flatMap {
      case Some((responseComputation, responseResult)) =>
        /*
         * c ∈ C    (N :: t⃗) ⊢ₙ c responds u
         * { C − {c} ; Φ ; Θ } ⊕ u = N′
         * ───────────────────────────────────── Step-Resolve
         * t⃗ ⊢ { C ; Φ ; Θ } ⟶ₙ N′
         */
        val remaining = RuntimeTrie.node(
          trie.responseComputations - responseComputation,
          trie.routeContinuations,
          trie.terminationPayloads
        )
        RuntimeTrie.merge(remaining, responseResult)
          .mapError(EvaluationError.Merge(_))
          .map(Some(_))
      case None => Result.Ok(None)
    }
  }

  @tailrec
  private def findResponse(
    remaining: List[RuntimeResponseComputation],
    globalEnvironment: RuntimeGlobalEnvironment,
    firstError: Option[EvaluationError]
  ): Result[Option[(RuntimeResponseComputation, RuntimeTrie)], EvaluationError] = remaining match {
    case Nil => firstError match {
      case Some(error) => Result.Err(error)
      case None => Result.Ok(None)
    }
    case responseComputation :: tail =>
      respond(responseComputation, globalEnvironment) match {
        case Result.Ok(Some(responseResult)) =>
          Result.Ok(Some(responseComputation -> responseResult))
        case Result.Ok(None) =>
          findResponse(tail, globalEnvironment, firstError)
        case Result.Err(error) =>
          findResponse(tail, globalEnvironment, firstError.orElse(Some(error)))
      }
  }

  private def respond(
    responseComputation: RuntimeResponseComputation,
    globalEnvironment: RuntimeGlobalEnvironment
  ): Result[Option[RuntimeTrie], EvaluationError] = responseComputation match {
    case variable: RuntimeLocalVariable =>
      Result.Err(EvaluationError.UnboundLocalVariable(variable.index))

    // G(g) = t
    // ───────────────────────── Resp-Global
    // G ⊢ global g responds t
    case global: RuntimeGlobal => globalEnvironment.lookup(global.identifier) match {
      case Some(definition) => Result.Ok(Some(definition))
      case None => Result.Err(EvaluationError.UnknownGlobal(global.identifier))
    }
    // binding(ref k) = t
    // ───────────────────── Resp-Ref
    // ref k responds t
    case reference: RuntimeStructuralReference => Result.Ok(Some(reference.target()))
    case indexing: RuntimeIndex =>
      if (!isSingletonResponseNode(indexing.receiver)) {
        /*
         * t ◁ₙ Q = u    u is not the administrative forwarding loop
         * ─────────────────────────────────────────────────────── Resp-Index-Run
         * t ◁ Q responds u
         */
        RuntimeTrie.index(indexing.receiver, indexing.requests)
          .mapError(EvaluationError.Merge(_))
          .map(Some(_))
      } else {
        /*
         * t ⟶ₙ t′
         * ───────────────────────────────────── Resp-Index-Receiver
         * t ◁ Q responds { t′ ◁ Q ; · ; · }
         */
        step(indexing.receiver, globalEnvironment).map {
          _.map(receiver => RuntimeTrie.response(new RuntimeIndex(receiver, indexing.requests)))
        }
      }
    case filtering: RuntimeFilter =>
      filtering.selectedRootKeys.normalize match {
        case None => Result.Err(EvaluationError.UnresolvedRootKeyExpression(
          filtering.selectedRootKeys
        ))

        // ───────────────────────────────── Resp-Filter-Empty
        // t⃗ ⊢ₙ t ▷ ∅ responds {}
        case Some(selectedRootKeys) if selectedRootKeys.isEmpty =>
          Result.Ok(Some(RuntimeTrie.empty))

        case Some(selectedRootKeys) =>
          if (!isSingletonResponseNode(filtering.receiver)) {
            /*
             * t = wₙ    t ▷ K = u    u is not the administrative forwarding loop
             * ───────────────────────────────────────────────────────────── Resp-Filter-Run
             * t ▷ K responds u
             */
            Result.Ok(Some(RuntimeTrie.filter(filtering.receiver, selectedRootKeys)))
          } else {
            composeNestedFilter(filtering.receiver, selectedRootKeys).flatMap {
              case Some(composed) => Result.Ok(Some(composed))
              case None =>
                /*
                 * t ⟶ₙ t′
                 * ────────────────────────────────────── Resp-Filter-Receiver
                 * t ▷ K responds { t′ ▷ K ; · ; · }
                 */
                step(filtering.receiver, globalEnvironment).map {
                  _.map(receiver => RuntimeTrie.response(new RuntimeFilter(
                    receiver,
                    filtering.selectedRootKeys
                  )))
                }
            }
          }
      }
    case primitive: RuntimePrimitiveOperation =>
      respondPrimitiveOperation(primitive, globalEnvironment)
    case conditional: RuntimeConditional =>
      conditional.condition.terminationPayloads.get(PrimitiveType.Boolean) match {
        // Θ(bool) = true
        // ─────────────────────────────────── Resp-If-True
        // if { C ; Φ ; Θ } then t₁ else t₂ responds t₁
        case Some(PrimitiveValue.Boolean(true)) => Result.Ok(Some(conditional.whenTrue))

        // Θ(bool) = false
        // ──────────────────────────────────── Resp-If-False
        // if { C ; Φ ; Θ } then t₁ else t₂ responds t₂
        case Some(PrimitiveValue.Boolean(false)) => Result.Ok(Some(conditional.whenFalse))
        case _ => step(conditional.condition, globalEnvironment).flatMap {
          // t ⟶ₙ t′
          // ────────────────────────────────────────────── Resp-If-Condition
          // if t then t₁ else t₂ responds { if t′ then t₁ else t₂ ; · ; · }
          case Some(condition) => Result.Ok(Some(RuntimeTrie.response(
            new RuntimeConditional(condition, conditional.whenTrue, conditional.whenFalse)
          )))

          // t ↛ₙ    bool ∉ dom(Θ)
          // ───────────────────────────────── Resp-If-Inapplicable
          // if { C ; Φ ; Θ } then t₁ else t₂ responds {}
          case None => Result.Ok(Some(RuntimeTrie.empty))
        }
      }
  }

  private def respondPrimitiveOperation(
    primitive: RuntimePrimitiveOperation,
    globalEnvironment: RuntimeGlobalEnvironment
  ): Result[Option[RuntimeTrie], EvaluationError] = {
    val readyOperands = primitive.operator.signatures.iterator.flatMap { signature =>
      for {
        leftPayload <- primitive.left.terminationPayloads.get(signature.leftArgument)
        rightPayload <- primitive.right.terminationPayloads.get(signature.rightArgument)
      } yield leftPayload -> rightPayload
    }.toList.headOption

    readyOperands match {
      /*
       * Θ₁(p₁) = a₁    Θ₂(p₂) = a₂    operator(a₁, a₂) = a
       * ───────────────────────────────────────────────────── Resp-Primitive-Run
       * operator({ C₁ ; Φ₁ ; Θ₁ }, { C₂ ; Φ₂ ; Θ₂ })
       *   responds { · ; · ; type(a) ↦ a }
       */
      case Some((leftPayload, rightPayload)) =>
        primitive.operator(leftPayload, rightPayload)
          .mapError(EvaluationError.PrimitiveFailure(_))
          .map(value => Some(RuntimeTrie.termination(value)))
      case None => step(primitive.left, globalEnvironment).flatMap {
        // ready(operator, Θ₁, Θ₂) = ∅    t₁ ⟶ₙ t₁′
        // ───────────────────────────────────────────────────── Resp-Primitive-Left
        // operator(t₁, t₂) responds {operator(t₁′, t₂) ; · ; ·}
        case Some(left) => Result.Ok(Some(RuntimeTrie.response(
          new RuntimePrimitiveOperation(primitive.operator, left, primitive.right)
        )))
        case None => step(primitive.right, globalEnvironment).flatMap {
          // ready(operator, Θ₁, Θ₂) = ∅    t₁ ↛ₙ    t₂ ⟶ₙ t₂′
          // ───────────────────────────────────────────────────── Resp-Primitive-Right
          // operator(t₁, t₂) responds {operator(t₁, t₂′) ; · ; ·}
          case Some(right) => Result.Ok(Some(RuntimeTrie.response(
            new RuntimePrimitiveOperation(primitive.operator, primitive.left, right)
          )))
          /*
           * t₁ ↛ₙ    t₂ ↛ₙ
           * ¬∃(p₁, p₂) ∈ sig(operator). p₁ ∈ dom(Θ₁) ∧ p₂ ∈ dom(Θ₂)
           * ─────────────────────────────────────────────────────── Resp-Primitive-Inapplicable
           * operator({ C₁ ; Φ₁ ; Θ₁ }, { C₂ ; Φ₂ ; Θ₂ }) responds {}
           */
          case None => Result.Ok(Some(RuntimeTrie.empty))
        }
      }
    }
  }

  private def composeNestedFilter(
    receiver: RuntimeTrie,
    selectedRootKeys: RootKeySet
  ): Result[Option[RuntimeTrie], EvaluationError] = {
    receiver.responseComputations.collectFirst { case filtering: RuntimeFilter => filtering } match {
      case None => Result.Ok(None)
      case Some(nestedFilter) => nestedFilter.selectedRootKeys.normalize match {
        case None => Result.Err(EvaluationError.UnresolvedRootKeyExpression(
          nestedFilter.selectedRootKeys
        ))
        case Some(nestedRootKeys) =>
          /*
           * c = u ▷ K₁    C′ = C ∖ {c}    K = K₁ ∩ K₂
           * D = ({u ▷ K} if K ≠ ∅ else ∅)
           * E = ({{C′ ; · ; ·} ▷ K₂} if C′ ≠ ∅ else ∅)
           * ───────────────────────────────────────────────── Resp-Filter-Compose
           * t⃗ ⊢ₙ {C ; · ; ·} ▷ K₂ responds {D ∪ E ; · ; ·}
           */
          val intersection = nestedRootKeys.intersection(selectedRootKeys)
          val composedResponse =
            if (intersection.isEmpty) Set.empty
            else Set[RuntimeResponseComputation](new RuntimeFilter(
              nestedFilter.receiver,
              RootKeyExpression.concrete(intersection)
            ))
          val remainingResponses = receiver.responseComputations - nestedFilter
          val filteredRemaining =
            if (remainingResponses.isEmpty) Set.empty
            else Set[RuntimeResponseComputation](new RuntimeFilter(
              RuntimeTrie.node(responseComputations = remainingResponses),
              RootKeyExpression.concrete(selectedRootKeys)
            ))
          Result.Ok(Some(RuntimeTrie.node(
            responseComputations = composedResponse ++ filteredRemaining
          )))
      }
    }
  }

  private def isSingletonResponseNode(trie: RuntimeTrie): Boolean = {
    trie.responseComputations.size == 1 &&
      trie.routeContinuations.isEmpty &&
      trie.terminationPayloads.isEmpty
  }
}
