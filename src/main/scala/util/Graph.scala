package cp.util

import scala.collection.mutable

/** An immutable graph whose algorithms may use mutation confined to one call. */
final class Graph[Vertex](
  val isDirected: Boolean = true,
  private val adjacencyList: Map[Vertex, Set[Vertex]] = Map.empty[Vertex, Set[Vertex]]
) {
  def addVertex(vertex: Vertex): Graph[Vertex] = {
    if (adjacencyList.contains(vertex)) {
      this
    } else {
      Graph(isDirected, adjacencyList + (vertex -> Set.empty[Vertex]))
    }
  }

  def addVertices(vertices: Iterable[Vertex]): Graph[Vertex] = {
    vertices.foldLeft(this) { (graph, vertex) => graph.addVertex(vertex) }
  }

  def addEdge(sourceVertex: Vertex, targetVertex: Vertex): Graph[Vertex] = {
    val graphWithVertices = addVertex(sourceVertex).addVertex(targetVertex)
    val updatedAdjacencyList = graphWithVertices.adjacencyList + (
      sourceVertex -> (graphWithVertices.adjacencyList(sourceVertex) + targetVertex)
    )
    val completeAdjacencyList = if (isDirected) {
      updatedAdjacencyList
    } else {
      updatedAdjacencyList + (
        targetVertex -> (updatedAdjacencyList.getOrElse(targetVertex, Set.empty[Vertex]) + sourceVertex)
      )
    }
    Graph(isDirected, completeAdjacencyList)
  }

  def neighbors(vertex: Vertex): Set[Vertex] = {
    adjacencyList.getOrElse(vertex, Set.empty[Vertex])
  }

  def containsVertex(vertex: Vertex): Boolean = adjacencyList.contains(vertex)

  def hasEdge(sourceVertex: Vertex, targetVertex: Vertex): Boolean = {
    neighbors(sourceVertex).contains(targetVertex)
  }

  def vertices: Iterable[Vertex] = adjacencyList.keys

  def edges: List[(Vertex, Vertex)] = adjacencyList.flatMap { case (sourceVertex, targetVertices) =>
    targetVertices.map(targetVertex => sourceVertex -> targetVertex)
  }.toList

  def hasCycle: Boolean = vertices.exists(isInCycle)

  def isInCycle(node: Vertex): Boolean = {
    def reachesNode(vertex: Vertex, visited: Set[Vertex]): Boolean = {
      if (vertex == node) {
        true
      } else if (visited.contains(vertex)) {
        false
      } else {
        val updatedVisited = visited + vertex
        neighbors(vertex).exists(neighbor => reachesNode(neighbor, updatedVisited))
      }
    }

    containsVertex(node) && neighbors(node).exists(neighbor => reachesNode(neighbor, Set.empty))
  }

  def isSelfLoop(node: Vertex): Boolean = neighbors(node).contains(node)

  def reachableSet(node: Vertex): Set[Vertex] = {
    def visit(vertex: Vertex, visited: Set[Vertex]): Set[Vertex] = {
      if (visited.contains(vertex)) {
        visited
      } else {
        val updatedVisited = visited + vertex
        neighbors(vertex).foldLeft(updatedVisited) { (accumulatedVertices, neighbor) =>
          visit(neighbor, accumulatedVertices)
        }
      }
    }

    neighbors(node).foldLeft(Set.empty[Vertex]) { (accumulatedVertices, neighbor) =>
      visit(neighbor, accumulatedVertices)
    }
  }

  def merge(other: Graph[Vertex]): Graph[Vertex] = {
    val combinedAdjacencyList = other.adjacencyList.foldLeft(adjacencyList) {
      case (accumulatedAdjacencyList, (vertex, adjacentVertices)) =>
        val combinedNeighbors = accumulatedAdjacencyList.getOrElse(vertex, Set.empty) ++ adjacentVertices
        accumulatedAdjacencyList + (vertex -> combinedNeighbors)
    }
    Graph(isDirected, combinedAdjacencyList)
  }

  def topologicalSort: Option[Seq[Vertex]] = {
    if (hasCycle) {
      None
    } else {
      Some(stronglyConnectedComponents.flatten)
    }
  }

  /** Computes strongly connected components and orders the component graph topologically. */
  def stronglyConnectedComponents: Seq[Set[Vertex]] = {
    val visited = mutable.Set[Vertex]()
    val completionOrder = mutable.Stack[Vertex]()

    def recordCompletionOrder(vertex: Vertex): Unit = {
      if (!visited.contains(vertex)) {
        visited += vertex
        neighbors(vertex).foreach(recordCompletionOrder)
        completionOrder.push(vertex)
      }
    }

    vertices.foreach(recordCompletionOrder)
    val transposedGraph = transpose()
    visited.clear()
    val components = mutable.ListBuffer[Set[Vertex]]()

    def collectComponent(vertex: Vertex, component: mutable.Set[Vertex]): Unit = {
      if (!visited.contains(vertex)) {
        visited += vertex
        component += vertex
        transposedGraph.neighbors(vertex).foreach(collectComponent(_, component))
      }
    }

    while (completionOrder.nonEmpty) {
      val vertex = completionOrder.pop()
      if (!visited.contains(vertex)) {
        val component = mutable.Set[Vertex]()
        collectComponent(vertex, component)
        components += component.toSet
      }
    }

    topologicallySortComponents(components.toList)
  }

  override def toString: String = adjacencyList.map { case (vertex, adjacentVertices) =>
    s"$vertex -> (${adjacentVertices.mkString(", ")})"
  }.mkString("; ")

  private def topologicallySortComponents(components: List[Set[Vertex]]): Seq[Set[Vertex]] = {
    val componentByVertex = components.flatMap { component =>
      component.map(_ -> component)
    }.toMap
    val componentGraph = mutable.Map.from(components.map(_ -> mutable.Set.empty[Set[Vertex]]))
    adjacencyList.foreach { case (sourceVertex, targetVertices) =>
      val sourceComponent = componentByVertex(sourceVertex)
      targetVertices.foreach { targetVertex =>
        val targetComponent = componentByVertex(targetVertex)
        if (sourceComponent != targetComponent) {
          componentGraph(sourceComponent) += targetComponent
        }
      }
    }

    val visitedComponents = mutable.Set[Set[Vertex]]()
    val orderedComponents = mutable.ListBuffer[Set[Vertex]]()

    def visitComponent(component: Set[Vertex]): Unit = {
      if (!visitedComponents.contains(component)) {
        visitedComponents += component
        componentGraph(component).foreach(visitComponent)
        orderedComponents.prepend(component)
      }
    }

    components.foreach(visitComponent)
    orderedComponents.toSeq
  }

  private def transpose(): Graph[Vertex] = {
    val transposedAdjacencyList = mutable.Map.from(
      vertices.map(_ -> Set.empty[Vertex])
    ).withDefaultValue(Set.empty[Vertex])
    adjacencyList.foreach { case (vertex, adjacentVertices) =>
      adjacentVertices.foreach { adjacentVertex =>
        transposedAdjacencyList(adjacentVertex) = transposedAdjacencyList(adjacentVertex) + vertex
      }
    }
    Graph(isDirected = true, transposedAdjacencyList.toMap)
  }
}

object Graph {
  def directed[Vertex]: Graph[Vertex] = Graph[Vertex](isDirected = true)

  def undirected[Vertex]: Graph[Vertex] = Graph[Vertex](isDirected = false)
}
