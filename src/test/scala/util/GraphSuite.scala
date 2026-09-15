package cp.util

class GraphSuite extends munit.FunSuite {
  test("strongly connected components preserve cycles and isolated vertices") {
    val graph = Graph.directed[String]
      .addVertices(List("first", "second", "dependent", "isolated"))
      .addEdge("first", "second")
      .addEdge("second", "first")
      .addEdge("dependent", "first")

    assertEquals(
      graph.stronglyConnectedComponents.toSet,
      Set(Set("first", "second"), Set("dependent"), Set("isolated"))
    )
  }

  test("topological sorting rejects a self loop") {
    val graph = Graph.directed[String].addEdge("loop", "loop")

    assertEquals(graph.topologicalSort, None)
  }

  test("topological sorting places an edge source before its target") {
    val graph = Graph.directed[String].addEdge("source", "target")

    assertEquals(graph.topologicalSort.map(_.toList), Some(List("source", "target")))
  }
}
