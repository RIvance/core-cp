package cp.fiobs.typing

import cp.fiobs.Type
import cp.primitive.PrimitiveType

import scala.annotation.tailrec

/**
 * A finite graph of possible collision routes. Universal guards and type-argument tokens are erased;
 * opaque variables may expose any finite route. Consequently emptiness certifies silence or disjointness,
 * but a nonempty graph can overestimate the behaviors admitted by polymorphic bounds.
 *
 * Let U be all finite routes ending at a primitive, and η assign route languages to recursive variables:
 *
 * Mη(p) = {p}                      Mη(⊤) = ∅                  Mη(⊥) = U
 * Mη(α) = η(α), or U if opaque     Mη(A ∧ B) = Mη(A) ∪ Mη(B)
 * Mη(A ⇾ B) = app · Mη(B)          Mη({ℓ : A}) = projℓ · Mη(A)
 * Mη(∀(α ∗ A). B) = tapp · Mη[α ↦ U](B)
 * Mη(μα. A) = least S satisfying S = unfold · Mη[α ↦ S](A)
 *
 * Removing guards makes the recursive language equations monotone. A recursive occurrence is a graph
 * edge back to its binder, never a polymorphic disjointness assumption. Product-graph reachability
 * decides whether two languages share a finite route; revisiting a state adds no new finite witness.
 */
private[typing] final class PossibleRoutes private (
  private val root: PossibleRoutes.Vertex,
  private val states: Map[PossibleRoutes.Vertex, PossibleRoutes.State]
) {
  import PossibleRoutes.*

  def isEmpty: Boolean = {
    @tailrec
    def search(pending: List[Vertex], visited: Set[Vertex]): Boolean = pending match {
      case Nil => true
      case vertex :: rest if visited(vertex) => search(rest, visited)
      case vertex :: rest => states(vertex) match {
        case State.Empty => search(rest, visited + vertex)
        case State.Any | State.Atomic(_) => false
        case State.Prefix(_, response) => search(response :: rest, visited + vertex)
        case State.Union(left, right) => search(left :: right :: rest, visited + vertex)
      }
    }
    search(List(root), Set.empty)
  }
}

private[typing] object PossibleRoutes {
  /** A local graph address, with no identity outside this analysis. */
  private final case class Vertex(index: Int)

  private enum Request {
    case Application, TypeApplication, Unfold
    case Projection(label: String)
  }

  private enum State {
    case Empty, Any
    case Atomic(kind: PrimitiveType)
    case Prefix(request: Request, response: Vertex)
    case Union(left: Vertex, right: Vertex)
  }

  def apply(inputType: Type): PossibleRoutes = {
    // Allocation precedes body construction so recursive references have a stable graph address.
    // No unfinished state escapes: each allocated vertex is defined before this method returns.
    var nextVertex = 0
    var states = Map.empty[Vertex, State]

    def build(currentType: Type, binders: List[Option[Vertex]]): Vertex = {
      val recursiveReference = currentType match {
        case Type.Variable(index) => binders.lift(index).flatten
        case _ => None
      }
      recursiveReference match {
        case Some(vertex) => vertex
        case None =>
          val vertex = Vertex(nextVertex)
          nextVertex += 1
          val state = currentType match {
            case Type.Primitive(kind) => State.Atomic(kind)
            case Type.Top => State.Empty
            case Type.Bottom | Type.Variable(_) => State.Any
            case Type.Arrow(_, resultType) => State.Prefix(Request.Application, build(resultType, binders))
            case Type.Intersection(leftType, rightType) =>
              State.Union(build(leftType, binders), build(rightType, binders))
            case Type.ForAll(_, bodyType) => State.Prefix(Request.TypeApplication, build(bodyType, None :: binders))
            case Type.Recursive(bodyType) => State.Prefix(Request.Unfold, build(bodyType, Some(vertex) :: binders))
            case Type.Record(label, fieldType) => State.Prefix(Request.Projection(label), build(fieldType, binders))
          }
          states = states.updated(vertex, state)
          vertex
      }
    }

    val root = build(inputType, Nil)
    new PossibleRoutes(root, states)
  }

  def areDisjoint(leftType: Type, rightType: Type): Boolean = {
    val left = apply(leftType)
    val right = apply(rightType)

    @tailrec
    def search(pending: List[(Vertex, Vertex)], visited: Set[(Vertex, Vertex)]): Boolean = pending match {
      case Nil => true
      case pair :: rest if visited(pair) => search(rest, visited)
      case (leftVertex, rightVertex) :: rest =>
        val pair = leftVertex -> rightVertex
        val nextVisited = visited + pair
        (left.states(leftVertex), right.states(rightVertex)) match {
          case (State.Empty, _) | (_, State.Empty) => search(rest, nextVisited)
          case (State.Union(first, second), _) =>
            search((first, rightVertex) :: (second, rightVertex) :: rest, nextVisited)
          case (_, State.Union(first, second)) =>
            search((leftVertex, first) :: (leftVertex, second) :: rest, nextVisited)
          case (State.Any, State.Any | State.Atomic(_)) | (State.Atomic(_), State.Any) => false
          case (State.Atomic(first), State.Atomic(second)) =>
            if (first == second) false else search(rest, nextVisited)
          case (State.Any, State.Prefix(_, response)) => search((leftVertex, response) :: rest, nextVisited)
          case (State.Prefix(_, response), State.Any) => search((response, rightVertex) :: rest, nextVisited)
          case (State.Prefix(first, leftResponse), State.Prefix(second, rightResponse)) =>
            if (first == second) search((leftResponse, rightResponse) :: rest, nextVisited)
            else search(rest, nextVisited)
          case _ => search(rest, nextVisited)
        }
    }
    search(List(left.root -> right.root), Set.empty)
  }
}
