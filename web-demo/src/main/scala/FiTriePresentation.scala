package cp.visualizer

import cp.fitrie.RootKeyExpression
import cp.fitrie.evaluation.*

import scala.annotation.tailrec
import scala.collection.mutable

/** Presentation-only projection of an evaluation graph; it never changes an evaluation session. */
private[visualizer] object FiTriePresentation {
  def project(snapshot: EvaluationSnapshot): EvaluationSnapshot = {
    val originalNodes = snapshot.nodes.map(node => node.id -> node).toMap
    val projectedNodes = snapshot.nodes.map { node =>
      node.copy(responses = node.responses.map {
        case EvaluationResponse.Filter(receiver, selectedRootKeys) =>
          EvaluationResponse.Filter(
            compactFilterReceiver(receiver, selectedRootKeys, originalNodes),
            selectedRootKeys
          )
        case response => response
      })
    }
    val projectedNodesById = projectedNodes.map(node => node.id -> node).toMap
    val reachableIds = reachable(snapshot.root, projectedNodesById)
    EvaluationSnapshot(
      snapshot.root,
      projectedNodes.filter(node => reachableIds.contains(node.id))
    )
  }

  def equivalent(left: EvaluationSnapshot, right: EvaluationSnapshot): Boolean = {
    new ProjectionComparison(left, right).equivalent()
  }

  private def compactFilterReceiver(
    receiver: EvaluationNodeId,
    selectedRootKeys: RootKeyExpression,
    nodesById: Map[EvaluationNodeId, EvaluationTrieNode],
    visited: Set[EvaluationNodeId] = Set.empty
  ): EvaluationNodeId = {
    if (visited.contains(receiver)) {
      receiver
    } else {
      nodesById.get(receiver) match {
        case Some(node) if node.routes.isEmpty && node.terminations.isEmpty =>
          node.responses match {
            case Vector(EvaluationResponse.Filter(nestedReceiver, nestedRootKeys))
                if nestedRootKeys == selectedRootKeys =>
              compactFilterReceiver(
                nestedReceiver,
                selectedRootKeys,
                nodesById,
                visited + receiver
              )
            case _ => receiver
          }
        case _ => receiver
      }
    }
  }

  private def reachable(
    root: EvaluationNodeId,
    nodesById: Map[EvaluationNodeId, EvaluationTrieNode]
  ): Set[EvaluationNodeId] = {
    @tailrec
    def visit(
      pending: List[EvaluationNodeId],
      visited: Set[EvaluationNodeId]
    ): Set[EvaluationNodeId] = pending match {
      case Nil => visited
      case nodeId :: remaining if visited.contains(nodeId) => visit(remaining, visited)
      case nodeId :: remaining =>
        val children = nodesById.get(nodeId).toList.flatMap(nodeChildren)
        visit(children ::: remaining, visited + nodeId)
    }

    visit(List(root), Set.empty)
  }

  private def nodeChildren(node: EvaluationTrieNode): List[EvaluationNodeId] = {
    node.routes.map(_.target).toList ::: node.responses.toList.flatMap {
      case EvaluationResponse.LocalVariable(_) | EvaluationResponse.Global(_) => Nil
      case EvaluationResponse.StructuralReference(target) => List(target)
      case EvaluationResponse.Index(receiver, requests) =>
        receiver :: requests.toList.collect {
          case EvaluationRequest.Application(argument) => argument
        }
      case EvaluationResponse.Filter(receiver, _) => List(receiver)
      case EvaluationResponse.PrimitiveOperation(_, left, right) => List(left, right)
      case EvaluationResponse.Conditional(condition, whenTrue, whenFalse) =>
        List(condition, whenTrue, whenFalse)
    }
  }

  private final class ProjectionComparison(
    left: EvaluationSnapshot,
    right: EvaluationSnapshot
  ) {
    private val leftNodes = left.nodes.map(node => node.id -> node).toMap
    private val rightNodes = right.nodes.map(node => node.id -> node).toMap
    private val leftToRight = mutable.Map.empty[EvaluationNodeId, EvaluationNodeId]
    private val rightToLeft = mutable.Map.empty[EvaluationNodeId, EvaluationNodeId]

    def equivalent(): Boolean = sameNode(left.root, right.root)

    private def sameNode(leftId: EvaluationNodeId, rightId: EvaluationNodeId): Boolean = {
      leftToRight.get(leftId) match {
        case Some(existingRight) => existingRight == rightId
        case None if rightToLeft.contains(rightId) => false
        case None =>
          (leftNodes.get(leftId), rightNodes.get(rightId)) match {
            case (Some(leftNode), Some(rightNode)) =>
              leftToRight.update(leftId, rightId)
              rightToLeft.update(rightId, leftId)
              sameNodeContents(leftNode, rightNode)
            case _ => false
          }
      }
    }

    private def sameNodeContents(leftNode: EvaluationTrieNode, rightNode: EvaluationTrieNode): Boolean = {
      sameEntries(leftNode.terminations, rightNode.terminations)(_ == _) &&
        sameEntries(leftNode.routes, rightNode.routes) { (leftRoute, rightRoute) =>
          leftRoute.routeKey == rightRoute.routeKey && sameNode(leftRoute.target, rightRoute.target)
        } &&
        sameEntries(leftNode.responses, rightNode.responses)(sameResponse)
    }

    private def sameResponse(leftResponse: EvaluationResponse, rightResponse: EvaluationResponse): Boolean = {
      (leftResponse, rightResponse) match {
        case (EvaluationResponse.LocalVariable(leftIndex), EvaluationResponse.LocalVariable(rightIndex)) =>
          leftIndex == rightIndex
        case (EvaluationResponse.Global(leftIdentifier), EvaluationResponse.Global(rightIdentifier)) =>
          leftIdentifier == rightIdentifier
        case (
            EvaluationResponse.StructuralReference(leftTarget),
            EvaluationResponse.StructuralReference(rightTarget)
          ) => sameNode(leftTarget, rightTarget)
        case (
            EvaluationResponse.Index(leftReceiver, leftRequests),
            EvaluationResponse.Index(rightReceiver, rightRequests)
          ) =>
          sameNode(leftReceiver, rightReceiver) &&
            sameEntries(leftRequests, rightRequests)(sameRequest)
        case (
            EvaluationResponse.Filter(leftReceiver, leftKeys),
            EvaluationResponse.Filter(rightReceiver, rightKeys)
          ) => leftKeys == rightKeys && sameNode(leftReceiver, rightReceiver)
        case (
            EvaluationResponse.PrimitiveOperation(leftOperator, leftFirst, leftSecond),
            EvaluationResponse.PrimitiveOperation(rightOperator, rightFirst, rightSecond)
          ) =>
          leftOperator == rightOperator &&
            sameNode(leftFirst, rightFirst) &&
            sameNode(leftSecond, rightSecond)
        case (
            EvaluationResponse.Conditional(leftCondition, leftTrue, leftFalse),
            EvaluationResponse.Conditional(rightCondition, rightTrue, rightFalse)
          ) =>
          sameNode(leftCondition, rightCondition) &&
            sameNode(leftTrue, rightTrue) &&
            sameNode(leftFalse, rightFalse)
        case _ => false
      }
    }

    private def sameRequest(leftRequest: EvaluationRequest, rightRequest: EvaluationRequest): Boolean = {
      (leftRequest, rightRequest) match {
        case (EvaluationRequest.Application(leftArgument), EvaluationRequest.Application(rightArgument)) =>
          sameNode(leftArgument, rightArgument)
        case (
            EvaluationRequest.TypeApplication(leftInterface),
            EvaluationRequest.TypeApplication(rightInterface)
          ) => leftInterface == rightInterface
        case (EvaluationRequest.Projection(leftLabel), EvaluationRequest.Projection(rightLabel)) =>
          leftLabel == rightLabel
        case _ => false
      }
    }

    private def sameEntries[Entry](
      leftEntries: Vector[Entry],
      rightEntries: Vector[Entry]
    )(sameEntry: (Entry, Entry) => Boolean): Boolean = {
      leftEntries.size == rightEntries.size &&
        leftEntries.lazyZip(rightEntries).forall(sameEntry)
    }
  }
}
