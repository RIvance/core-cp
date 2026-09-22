package cp.fitrie.evaluation

import cp.fitrie.*
import cp.naming.{FieldLabel, Identifier}
import cp.primitive.{BinaryOperator, PrimitiveType, PrimitiveValue}
import cp.util.Result

import scala.annotation.tailrec
import scala.util.control.TailCalls.{TailRec, done, tailcall}

/**
 * Evaluation closes scoped node references into immutable thunks before any
 * trie is reduced. A continuation can therefore escape its lexical parent
 * while retaining the same structural edge, without addresses, mutation, or
 * reference-rebasing terms in the target language.
 */
private[evaluation] final class RuntimeTrie(
  val responseComputations: Set[RuntimeResponseComputation],
  val routeContinuations: Map[RouteKey, RuntimeContinuation],
  val terminationPayloads: TerminationPayloads
)

private[evaluation] sealed trait RuntimeResponseComputation

/** Fresh identities of lexical route binders; runtime substitution never equates them by their display index. */
private[evaluation] final class RuntimeTermBinder
private[evaluation] final class RuntimePathBinder

private[evaluation] enum RuntimeRouteBinding {
  case None
  case Term(binders: Set[RuntimeTermBinder])
  case Path(binders: Set[RuntimePathBinder])

  def merge(other: RuntimeRouteBinding): RuntimeRouteBinding = (this, other) match {
    case (None, None) => None
    case (Term(left), Term(right)) => Term(left ++ right)
    case (Path(left), Path(right)) => Path(left ++ right)
    case _ => throw new IllegalStateException("matching runtime routes introduced different kinds of binder")
  }
}

/** A merged route supplies one argument to every original binder represented by that continuation. */
private[evaluation] final case class RuntimeContinuation(body: RuntimeTrie, binding: RuntimeRouteBinding)

private[evaluation] final case class RuntimePathScope(binders: List[RuntimePathBinder]) {
  def specialize[A](value: A, selected: Set[RuntimePathBinder])(
    substitute: (A, PathVariableIndex) => A
  ): (A, RuntimePathScope) = {
    val selectedIndices = binders.zipWithIndex.collect { case (binder, index) if selected.contains(binder) => index }
    val specialized = selectedIndices.reverse.foldLeft(value) { (current, index) =>
      substitute(current, PathVariableIndex(index))
    }
    specialized -> RuntimePathScope(binders.filterNot(selected.contains))
  }
}

private[evaluation] object RuntimePathScope {
  val empty: RuntimePathScope = RuntimePathScope(Nil)
}

private[evaluation] final class RuntimeLocalVariable(val index: TermVariableIndex, val binder: RuntimeTermBinder)
    extends RuntimeResponseComputation

private[evaluation] final class RuntimeGlobal(val identifier: Identifier)
    extends RuntimeResponseComputation

private[evaluation] final class RuntimeStructuralReference(val target: () => RuntimeTrie)
    extends RuntimeResponseComputation

private[evaluation] final class RuntimeIndex(val receiver: RuntimeTrie, val requests: RuntimeRequestSet)
    extends RuntimeResponseComputation

private[evaluation] final class RuntimeFilter(
  val receiver: RuntimeTrie,
  val selectedRootKeys: RootKeyExpression,
  val pathScope: RuntimePathScope = RuntimePathScope.empty
) extends RuntimeResponseComputation

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
  pathInterface: ObservationPathInterface,
  pathScope: RuntimePathScope
) extends RuntimeRequest {
  override val routeKey: RouteKey = RouteKey.TypeApplication
}

private[evaluation] final case class RuntimeProjectionRequest(label: FieldLabel) extends RuntimeRequest {
  override val routeKey: RouteKey = RouteKey.Projection(label)
}

private[evaluation] case object RuntimeUnfoldRequest extends RuntimeRequest {
  override val routeKey: RouteKey = RouteKey.Unfold
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
    ancestors: List[() => RuntimeTrie],
    termScope: List[RuntimeTermBinder] = Nil,
    pathScope: RuntimePathScope = RuntimePathScope.empty
  ): RuntimeTrie = {
    lazy val current: RuntimeTrie = {
      val currentBinding = () => current
      val responseAncestors = currentBinding :: ancestors
      new RuntimeTrie(
        trie.responseComputations.map(closeResponse(_, responseAncestors, termScope, pathScope)),
        trie.routeContinuations.map { case (routeKey, continuation) =>
          val closed = routeKey match {
            case RouteKey.Application =>
              val binder = new RuntimeTermBinder
              RuntimeContinuation(
                close(continuation, responseAncestors, binder :: termScope, pathScope),
                RuntimeRouteBinding.Term(Set(binder))
              )
            case RouteKey.TypeApplication =>
              val binder = new RuntimePathBinder
              RuntimeContinuation(
                close(continuation, responseAncestors, termScope, RuntimePathScope(binder :: pathScope.binders)),
                RuntimeRouteBinding.Path(Set(binder))
              )
            case RouteKey.Projection(_) | RouteKey.Unfold => RuntimeContinuation(
              close(continuation, responseAncestors, termScope, pathScope),
              RuntimeRouteBinding.None
            )
          }
          routeKey -> closed
        },
        trie.terminationPayloads
      )
    }
    current
  }

  private def closeResponse(
    responseComputation: ResponseComputation,
    ancestors: List[() => RuntimeTrie],
    termScope: List[RuntimeTermBinder],
    pathScope: RuntimePathScope
  ): RuntimeResponseComputation = responseComputation match {
    case ResponseComputation.LocalVariable(index) => new RuntimeLocalVariable(index, termScope(index.value))
    case ResponseComputation.Global(identifier) => new RuntimeGlobal(identifier)
    case ResponseComputation.StructuralReference(index) => new RuntimeStructuralReference(ancestors(index.value))
    case ResponseComputation.Index(receiver, requests) => new RuntimeIndex(
      close(receiver, ancestors, termScope, pathScope),
      closeRequests(requests, ancestors, termScope, pathScope)
    )
    case ResponseComputation.Filter(receiver, selectedRootKeys) => new RuntimeFilter(
      close(receiver, ancestors, termScope, pathScope),
      selectedRootKeys,
      pathScope
    )
    case ResponseComputation.PrimitiveOperation(operator, left, right) => new RuntimePrimitiveOperation(
      operator,
      close(left, ancestors, termScope, pathScope),
      close(right, ancestors, termScope, pathScope)
    )
    case ResponseComputation.Conditional(condition, whenTrue, whenFalse) => new RuntimeConditional(
      close(condition, ancestors, termScope, pathScope),
      close(whenTrue, ancestors, termScope, pathScope),
      close(whenFalse, ancestors, termScope, pathScope)
    )
  }

  private def closeRequests(
    requests: RequestSet,
    ancestors: List[() => RuntimeTrie],
    termScope: List[RuntimeTermBinder],
    pathScope: RuntimePathScope
  ): RuntimeRequestSet = RuntimeRequestSet(requests.requests.map {
    case Request.Application(argument) =>
      new RuntimeApplicationRequest(close(argument, ancestors, termScope, pathScope))
    case Request.TypeApplication(pathInterface) => RuntimeTypeApplicationRequest(pathInterface, pathScope)
    case Request.Projection(label) => RuntimeProjectionRequest(label)
    case Request.Unfold => RuntimeUnfoldRequest
  })
}

private object RuntimeTrie {
  val empty: RuntimeTrie = node()

  def node(
    responseComputations: Set[RuntimeResponseComputation] = Set.empty,
    routeContinuations: Map[RouteKey, RuntimeContinuation] = Map.empty,
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
            continuation.binding match {
              case RuntimeRouteBinding.Term(binders) =>
                RuntimeBinding.substituteTermVariables(continuation.body, binders, application.argument)
              case _ => throw new IllegalStateException("an application route has no runtime term binder")
            }

          // tapp[𝒜] ∈ Q    tappα ↦ t ∈ Φ    t[α ↦ 𝒜] = t′
          // ───────────────────────────────────────────── Index-TApp-Paths
          // Φ ; Q selects t′
          case typeApplication: RuntimeTypeApplicationRequest => continuation.binding match {
            case RuntimeRouteBinding.Path(binders) => RuntimeBinding.substitutePathVariables(
              continuation.body,
              binders,
              typeApplication.pathInterface
            )
            case _ => throw new IllegalStateException("a type-application route has no runtime path binder")
          }

          // projℓ ∈ Q    projℓ ↦ t ∈ Φ
          // ───────────────────────────── Index-Proj
          // Φ ; Q selects t
          case _: RuntimeProjectionRequest => Result.Ok(continuation.body)

          // unfold ∈ Q    unfold ↦ t ∈ Φ
          // ─────────────────────────── Index-Unfold
          // Φ ; Q selects t
          case RuntimeUnfoldRequest => Result.Ok(continuation.body)
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
    left: Map[RouteKey, RuntimeContinuation],
    right: Map[RouteKey, RuntimeContinuation]
  ): Result[Map[RouteKey, RuntimeContinuation], FiTrieMergeError] = {
    (left.keySet ++ right.keySet).foldLeft(
      Result.Ok(Map.empty[RouteKey, RuntimeContinuation]): Result[Map[RouteKey, RuntimeContinuation], FiTrieMergeError]
    ) { (accumulated, routeKey) =>
      accumulated.flatMap { routes =>
        (left.get(routeKey), right.get(routeKey)) match {
          case (Some(leftContinuation), Some(rightContinuation)) =>
            merge(leftContinuation.body, rightContinuation.body)
              .mapError(_.beneath(routeKey))
              .map(continuation => routes.updated(routeKey, RuntimeContinuation(
                continuation,
                leftContinuation.binding.merge(rightContinuation.binding)
              )))
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
  def substituteTermVariables(
    trie: RuntimeTrie,
    binders: Set[RuntimeTermBinder],
    replacement: RuntimeTrie
  ): Result[RuntimeTrie, FiTrieMergeError] = {
    new GraphSubstitution(Substitution.Term(binders, replacement)).apply(trie)
  }

  def substitutePathVariables(
    trie: RuntimeTrie,
    binders: Set[RuntimePathBinder],
    replacement: ObservationPathInterface
  ): Result[RuntimeTrie, FiTrieMergeError] = {
    new GraphSubstitution(Substitution.Path(binders, replacement)).apply(trie)
  }

  private enum Substitution {
    case Term(binders: Set[RuntimeTermBinder], replacement: RuntimeTrie)
    case Path(binders: Set[RuntimePathBinder], replacement: ObservationPathInterface)

    def isEmpty: Boolean = this match {
      case Term(binders, _) => binders.isEmpty
      case Path(binders, _) => binders.isEmpty
    }

    def beneath(binding: RuntimeRouteBinding): Substitution = (this, binding) match {
      case (Term(binders, replacement), RuntimeRouteBinding.Term(bound)) => Term(binders -- bound, replacement)
      case (Path(binders, replacement), RuntimeRouteBinding.Path(bound)) => Path(binders -- bound, replacement)
      case _ => this
    }
  }

  /**
   * A construction-only knot. All links are completed before publication and
   * retain only their finished node, so a runtime reference cannot keep the
   * substitution's source graph or lexical environment alive.
   */
  private final class GraphLink extends (() => RuntimeTrie) {
    private var completed: Option[RuntimeTrie] = None

    def complete(target: RuntimeTrie): Unit = {
      require(completed.isEmpty, "a runtime graph link can only be completed once")
      completed = Some(target)
    }

    override def apply(): RuntimeTrie = completed.getOrElse {
      throw new IllegalStateException("an unfinished runtime graph escaped its construction boundary")
    }
  }

  /**
   * Capture-avoiding substitution in a recursive graph. The lexical environment
   * binds each graph being constructed to its recursive knot. A backedge uses
   * that binding; every other occurrence is transformed independently.
   *
   * An executed request's argument is closed: evaluation never enters an
   * unapplied app/tapp continuation. Replacements therefore need no rebasing.
   * A route removes its own binders from the substitution before descending.
   */
  private final class GraphSubstitution(initial: Substitution) {
    private type Transformation[A] = TailRec[Result[A, FiTrieMergeError]]

    private final case class Binding(source: RuntimeTrie, substitution: Substitution, link: GraphLink)

    extension [A](computation: Transformation[A]) {
      private def flatMapResult[B](next: A => Transformation[B]): Transformation[B] = computation.flatMap {
        case Result.Ok(value) => tailcall(next(value))
        case Result.Err(error) => done(Result.Err(error))
      }

      private def mapResult[B](next: A => B): Transformation[B] = computation.map(_.map(next))
    }

    def apply(source: RuntimeTrie): Result[RuntimeTrie, FiTrieMergeError] =
      transform(source, initial, Nil).result

    private def traverse[A, B](values: List[A])(next: A => Transformation[B]): Transformation[List[B]] = values match {
      case Nil => done(Result.Ok(Nil))
      case head :: tail => tailcall(next(head)).flatMapResult { first =>
        tailcall(traverse(tail)(next)).mapResult(first :: _)
      }
    }

    private def transform(
      source: RuntimeTrie,
      substitution: Substitution,
      bindings: List[Binding]
    ): Transformation[RuntimeTrie] = {
      if (substitution.isEmpty) done(Result.Ok(source))
      else {
        val link = new GraphLink
        val nested = Binding(source, substitution, link) :: bindings
        val routes = traverse(source.routeContinuations.toList) { case (routeKey, continuation) =>
          // σ′ = σ ∖ binders(κ)    t[σ′] = u
          // ───────────────────────────────── Sub-Route
          // (κ ↦ t)[σ] = κ ↦ u
          tailcall(transform(continuation.body, substitution.beneath(continuation.binding), nested)).mapResult { body =>
            routeKey -> continuation.copy(body = body)
          }
        }
        routes.flatMapResult { substitutedRoutes =>
          val known = RuntimeTrie.node(
            routeContinuations = substitutedRoutes.toMap,
            terminationPayloads = source.terminationPayloads
          )
          source.responseComputations.toList.foldLeft(done(Result.Ok(known)): Transformation[RuntimeTrie]) {
            (accumulated, response) => accumulated.flatMapResult { current =>
              tailcall(transformResponse(response, substitution, nested)).flatMapResult { substituted =>
                done(RuntimeTrie.merge(current, substituted))
              }
            }
          }
        }.mapResult { target =>
          link.complete(target)
          target
        }
      }
    }

    private def transformResponse(
      response: RuntimeResponseComputation,
      substitution: Substitution,
      bindings: List[Binding]
    ): Transformation[RuntimeTrie] = response match {
      // σ(x) = u
      // ────────────────── Sub-Variable
      // {x ; · ; ·}[σ] = u
      case variable: RuntimeLocalVariable => substitution match {
        case Substitution.Term(binders, replacement) if binders.contains(variable.binder) =>
          done(Result.Ok(replacement))
        case _ => done(Result.Ok(RuntimeTrie.response(variable)))
      }
      case _: RuntimeGlobal => done(Result.Ok(RuntimeTrie.response(response)))

      // Ξ(t,σ) = α
      // ─────────────────────────── Sub-Reference-Bound
      // Ξ ⊢ ref(t)[σ] = ref(α)
      //
      // (t,σ) ∉ dom(Ξ)    Ξ, (t,σ) ↦ α ⊢ t[σ] = u    α = u
      // ─────────────────────────────────────────────────── Sub-Reference
      // Ξ ⊢ ref(t)[σ] = ref(u)
      case reference: RuntimeStructuralReference =>
        val source = reference.target()
        bindings.find(binding => (binding.source eq source) && binding.substitution == substitution) match {
          case Some(binding) => done(Result.Ok(RuntimeTrie.response(new RuntimeStructuralReference(binding.link))))
          case None => tailcall(transform(source, substitution, bindings)).mapResult { target =>
            RuntimeTrie.response(new RuntimeStructuralReference(() => target))
          }
        }

      case indexing: RuntimeIndex =>
        tailcall(transform(indexing.receiver, substitution, bindings)).flatMapResult { receiver =>
          val requests = indexing.requests.requests.toList
          traverse(requests)(transformRequest(_, substitution, bindings)).mapResult { transformed =>
            RuntimeTrie.response(new RuntimeIndex(receiver, RuntimeRequestSet(transformed.toSet)))
          }
        }

      case filtering: RuntimeFilter =>
        tailcall(transform(filtering.receiver, substitution, bindings)).mapResult { receiver =>
          val (keys, scope) = substitution match {
            case Substitution.Path(binders, replacement) =>
              filtering.pathScope.specialize(filtering.selectedRootKeys, binders)(
                _.substitutePathVariable(_, replacement)
              )
            case _ => filtering.selectedRootKeys -> filtering.pathScope
          }
          RuntimeTrie.response(new RuntimeFilter(receiver, keys, scope))
        }

      case primitive: RuntimePrimitiveOperation =>
        tailcall(transform(primitive.left, substitution, bindings)).flatMapResult { left =>
          tailcall(transform(primitive.right, substitution, bindings)).mapResult { right =>
            RuntimeTrie.response(new RuntimePrimitiveOperation(primitive.operator, left, right))
          }
        }

      case conditional: RuntimeConditional =>
        tailcall(transform(conditional.condition, substitution, bindings)).flatMapResult { condition =>
          tailcall(transform(conditional.whenTrue, substitution, bindings)).flatMapResult { whenTrue =>
            tailcall(transform(conditional.whenFalse, substitution, bindings)).mapResult { whenFalse =>
              RuntimeTrie.response(new RuntimeConditional(condition, whenTrue, whenFalse))
            }
          }
        }
    }

    private def transformRequest(
      request: RuntimeRequest,
      substitution: Substitution,
      bindings: List[Binding]
    ): Transformation[RuntimeRequest] = request match {
      case application: RuntimeApplicationRequest =>
        tailcall(transform(application.argument, substitution, bindings)).mapResult(new RuntimeApplicationRequest(_))
      case application: RuntimeTypeApplicationRequest => substitution match {
        case Substitution.Path(binders, replacement) =>
          val (paths, scope) = application.pathScope.specialize(application.pathInterface, binders)(
            _.substitutePathVariable(_, replacement)
          )
          done(Result.Ok(RuntimeTypeApplicationRequest(paths, scope)))
        case _ => done(Result.Ok(application))
      }
      case _: RuntimeProjectionRequest | RuntimeUnfoldRequest => done(Result.Ok(request))
    }
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
                    filtering.selectedRootKeys,
                    filtering.pathScope
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
