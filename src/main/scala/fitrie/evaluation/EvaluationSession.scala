package cp.fitrie.evaluation

import cp.fitrie.*
import cp.naming.{FieldLabel, Identifier}
import cp.primitive.{BinaryOperator, PrimitiveType, PrimitiveValue}
import cp.util.Result

import scala.annotation.tailrec

final case class EvaluationNodeId(value: Int) {
  require(value >= 0, "an evaluation node identifier cannot be negative")
}

final case class EvaluationSnapshot(
  root: EvaluationNodeId,
  nodes: Vector[EvaluationTrieNode]
)

final case class EvaluationTrieNode(
  id: EvaluationNodeId,
  responses: Vector[EvaluationResponse],
  routes: Vector[EvaluationRoute],
  terminations: Vector[EvaluationTermination]
)

final case class EvaluationRoute(routeKey: RouteKey, target: EvaluationNodeId)

final case class EvaluationTermination(
  primitiveType: PrimitiveType,
  payload: PrimitiveValue
)

enum EvaluationResponse {
  case LocalVariable(index: TermVariableIndex)
  case Global(identifier: Identifier)
  case StructuralReference(target: EvaluationNodeId)
  case Index(receiver: EvaluationNodeId, requests: Vector[EvaluationRequest])
  case Filter(receiver: EvaluationNodeId, selectedRootKeys: RootKeyExpression)
  case PrimitiveOperation(
    operator: BinaryOperator,
    left: EvaluationNodeId,
    right: EvaluationNodeId
  )
  case Conditional(
    condition: EvaluationNodeId,
    whenTrue: EvaluationNodeId,
    whenFalse: EvaluationNodeId
  )
}

enum EvaluationRequest {
  case Application(argument: EvaluationNodeId)
  case TypeApplication(pathInterface: ObservationPathInterface)
  case Projection(label: FieldLabel)
  case Unfold
}

enum EvaluationStep {
  case Progressed(next: EvaluationSession)
  case Complete
}

/**
 * An immutable handle around the closed FiTrie runtime. Node identifiers are
 * stable for runtime objects retained between consecutive states, allowing a
 * separate snapshot diff to identify changed structure without instrumenting
 * the reduction rules.
 */
final class EvaluationSession private[evaluation] (
  private val current: RuntimeTrie,
  private val globalEnvironment: RuntimeGlobalEnvironment,
  private val registry: RuntimeNodeRegistry
) {
  def snapshot: EvaluationSnapshot = RuntimeInspection.snapshot(current, registry)

  def step: Result[EvaluationStep, EvaluationError] = {
    RuntimeEvaluation.step(current, globalEnvironment).map {
      case Some(next) =>
        val nextRegistry = RuntimeInspection.includeReachable(next, registry)
        EvaluationStep.Progressed(new EvaluationSession(next, globalEnvironment, nextRegistry))
      case None => EvaluationStep.Complete
    }
  }
}

private[evaluation] object EvaluationSession {
  def start(
    entry: FiTrie,
    globalEnvironment: GlobalEnvironment
  ): Result[EvaluationSession, EvaluationError] = {
    RuntimeCompiler.compile(entry, globalEnvironment).map { compiled =>
      val registry = RuntimeInspection.includeReachable(compiled.entry, RuntimeNodeRegistry.empty)
      new EvaluationSession(compiled.entry, compiled.globalEnvironment, registry)
    }
  }
}

private[evaluation] final case class RuntimeNodeRegistry(nodes: Vector[RuntimeTrie]) {
  def idOf(node: RuntimeTrie): Option[EvaluationNodeId] = {
    nodes.indexWhere(_ eq node) match {
      case -1 => None
      case index => Some(EvaluationNodeId(index))
    }
  }

  def register(node: RuntimeTrie): (RuntimeNodeRegistry, EvaluationNodeId) = {
    idOf(node) match {
      case Some(id) => this -> id
      case None =>
        val id = EvaluationNodeId(nodes.size)
        RuntimeNodeRegistry(nodes :+ node) -> id
    }
  }
}

private[evaluation] object RuntimeNodeRegistry {
  val empty: RuntimeNodeRegistry = RuntimeNodeRegistry(Vector.empty)
}

private[evaluation] object RuntimeInspection {
  def includeReachable(
    root: RuntimeTrie,
    initialRegistry: RuntimeNodeRegistry
  ): RuntimeNodeRegistry = {
    val (registryWithRoot, rootId) = initialRegistry.register(root)

    @tailrec
    def visit(
      pending: List[EvaluationNodeId],
      visited: Set[EvaluationNodeId],
      registry: RuntimeNodeRegistry
    ): RuntimeNodeRegistry = pending match {
      case Nil => registry
      case id :: remaining if visited.contains(id) => visit(remaining, visited, registry)
      case id :: remaining =>
        val node = registry.nodes(id.value)
        val (expandedRegistry, childIds) = children(node).foldLeft(
          registry -> List.empty[EvaluationNodeId]
        ) { case ((currentRegistry, ids), child) =>
          val (nextRegistry, childId) = currentRegistry.register(child)
          nextRegistry -> (childId :: ids)
        }
        visit(childIds ::: remaining, visited + id, expandedRegistry)
    }

    visit(List(rootId), Set.empty, registryWithRoot)
  }

  def snapshot(root: RuntimeTrie, registry: RuntimeNodeRegistry): EvaluationSnapshot = {
    val completeRegistry = includeReachable(root, registry)
    val rootId = completeRegistry.idOf(root).getOrElse {
      throw new IllegalStateException("the evaluation root was absent from its node registry")
    }
    val reachableIds = reachable(rootId, completeRegistry)
    EvaluationSnapshot(
      rootId,
      reachableIds.toVector.sortBy(_.value).map(id => inspect(id, completeRegistry))
    )
  }

  private def inspect(
    id: EvaluationNodeId,
    registry: RuntimeNodeRegistry
  ): EvaluationTrieNode = {
    val node = registry.nodes(id.value)
    val responses = node.responseComputations.toVector.map(response(_, registry)).sortBy(responseSortKey)
    val routes = node.routeContinuations.toVector.map { case (routeKey, continuation) =>
      EvaluationRoute(routeKey, requiredId(continuation.body, registry))
    }.sortBy(route => routeSortKey(route.routeKey))
    val terminations = node.terminationPayloads.entries.toVector.map { case (primitiveType, payload) =>
      EvaluationTermination(primitiveType, payload)
    }.sortBy(_.primitiveType.ordinal)
    EvaluationTrieNode(id, responses, routes, terminations)
  }

  private def response(
    runtimeResponse: RuntimeResponseComputation,
    registry: RuntimeNodeRegistry
  ): EvaluationResponse = runtimeResponse match {
    case variable: RuntimeLocalVariable => EvaluationResponse.LocalVariable(variable.index)
    case global: RuntimeGlobal => EvaluationResponse.Global(global.identifier)
    case reference: RuntimeStructuralReference =>
      EvaluationResponse.StructuralReference(requiredId(reference.target(), registry))
    case indexing: RuntimeIndex => EvaluationResponse.Index(
      requiredId(indexing.receiver, registry),
      indexing.requests.requests.toVector.map(request(_, registry)).sortBy(requestSortKey)
    )
    case filtering: RuntimeFilter => EvaluationResponse.Filter(
      requiredId(filtering.receiver, registry),
      filtering.selectedRootKeys
    )
    case primitive: RuntimePrimitiveOperation => EvaluationResponse.PrimitiveOperation(
      primitive.operator,
      requiredId(primitive.left, registry),
      requiredId(primitive.right, registry)
    )
    case conditional: RuntimeConditional => EvaluationResponse.Conditional(
      requiredId(conditional.condition, registry),
      requiredId(conditional.whenTrue, registry),
      requiredId(conditional.whenFalse, registry)
    )
  }

  private def request(
    runtimeRequest: RuntimeRequest,
    registry: RuntimeNodeRegistry
  ): EvaluationRequest = runtimeRequest match {
    case application: RuntimeApplicationRequest =>
      EvaluationRequest.Application(requiredId(application.argument, registry))
    case typeApplication: RuntimeTypeApplicationRequest =>
      EvaluationRequest.TypeApplication(typeApplication.pathInterface)
    case projection: RuntimeProjectionRequest => EvaluationRequest.Projection(projection.label)
    case RuntimeUnfoldRequest => EvaluationRequest.Unfold
  }

  private def children(node: RuntimeTrie): List[RuntimeTrie] = {
    node.routeContinuations.values.toList.map(_.body) ::: node.responseComputations.toList.flatMap {
      case _: RuntimeLocalVariable | _: RuntimeGlobal => Nil
      case reference: RuntimeStructuralReference => List(reference.target())
      case indexing: RuntimeIndex =>
        indexing.receiver :: indexing.requests.requests.toList.collect {
          case application: RuntimeApplicationRequest => application.argument
        }
      case filtering: RuntimeFilter => List(filtering.receiver)
      case primitive: RuntimePrimitiveOperation => List(primitive.left, primitive.right)
      case conditional: RuntimeConditional =>
        List(conditional.condition, conditional.whenTrue, conditional.whenFalse)
    }
  }

  private def reachable(
    root: EvaluationNodeId,
    registry: RuntimeNodeRegistry
  ): Set[EvaluationNodeId] = {
    @tailrec
    def visit(
      pending: List[EvaluationNodeId],
      visited: Set[EvaluationNodeId]
    ): Set[EvaluationNodeId] = pending match {
      case Nil => visited
      case id :: remaining if visited.contains(id) => visit(remaining, visited)
      case id :: remaining =>
        val childIds = children(registry.nodes(id.value)).map(requiredId(_, registry))
        visit(childIds ::: remaining, visited + id)
    }

    visit(List(root), Set.empty)
  }

  private def requiredId(
    node: RuntimeTrie,
    registry: RuntimeNodeRegistry
  ): EvaluationNodeId = registry.idOf(node).getOrElse {
    throw new IllegalStateException("a reachable runtime trie was absent from its node registry")
  }

  private def routeSortKey(routeKey: RouteKey): (Int, String) = routeKey match {
    case RouteKey.Application => 0 -> ""
    case RouteKey.TypeApplication => 1 -> ""
    case RouteKey.Unfold => 3 -> ""
    case RouteKey.Projection(label) => 2 -> label.value
  }

  private def requestSortKey(request: EvaluationRequest): (Int, String) = request match {
    case EvaluationRequest.Application(argument) => 0 -> argument.value.toString
    case EvaluationRequest.TypeApplication(pathInterface) => 1 -> pathInterface.toString
    case EvaluationRequest.Unfold => 3 -> ""
    case EvaluationRequest.Projection(label) => 2 -> label.value
  }

  private def responseSortKey(response: EvaluationResponse): (Int, String) = response match {
    case EvaluationResponse.LocalVariable(index) => 0 -> index.value.toString
    case EvaluationResponse.Global(identifier) => 1 -> identifier.render
    case EvaluationResponse.StructuralReference(target) => 2 -> target.value.toString
    case EvaluationResponse.Index(receiver, requests) => 3 -> s"${receiver.value}:$requests"
    case EvaluationResponse.Filter(receiver, selectedRootKeys) => 4 -> s"${receiver.value}:$selectedRootKeys"
    case EvaluationResponse.PrimitiveOperation(operator, left, right) =>
      5 -> s"${operator.ordinal}:${left.value}:${right.value}"
    case EvaluationResponse.Conditional(condition, whenTrue, whenFalse) =>
      6 -> s"${condition.value}:${whenTrue.value}:${whenFalse.value}"
  }
}
