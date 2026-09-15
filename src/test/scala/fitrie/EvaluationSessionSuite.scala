package cp.fitrie

import cp.fitrie.evaluation.*
import cp.naming.Namespace
import cp.primitive.{BinaryOperator, PrimitiveValue}
import cp.util.Result

class EvaluationSessionSuite extends munit.FunSuite {
  test("a session exposes the compiled entry without taking an eager step") {
    val identifier = Namespace("Application").identifier("main")
    val entry = FiTrie.response(ResponseComputation.Global(identifier))
    val session = expectSuccess(Evaluation.start(
      entry,
      GlobalEnvironment(Map(identifier -> FiTrie.termination(PrimitiveValue.Integer(42))))
    ))

    val root = rootNode(session.snapshot)
    assertEquals(root.responses, Vector(EvaluationResponse.Global(identifier)))
    assertEquals(root.terminations, Vector.empty)
  }

  test("one session step delegates to one lazy FiTrie small step") {
    val addition = FiTrie.response(ResponseComputation.PrimitiveOperation(
      BinaryOperator.Add,
      FiTrie.termination(PrimitiveValue.Integer(20)),
      FiTrie.termination(PrimitiveValue.Integer(22))
    ))
    val initial = expectSuccess(Evaluation.start(addition))

    val next = expectProgress(expectSuccess(initial.step))
    assertEquals(
      rootNode(next.snapshot).terminations,
      Vector(EvaluationTermination(
        cp.primitive.PrimitiveType.Integer,
        PrimitiveValue.Integer(42)
      ))
    )
    assertEquals(expectSuccess(next.step), EvaluationStep.Complete)
  }

  test("snapshot node identities remain stable for retained trie structure") {
    val identifier = Namespace("Library").identifier("answer")
    val continuation = FiTrie.termination(PrimitiveValue.Boolean(true))
    val entry = FiTrie.node(
      responseComputations = Set(ResponseComputation.Global(identifier)),
      routeContinuations = Map(RouteKey.Application -> continuation)
    )
    val initial = expectSuccess(Evaluation.start(
      entry,
      GlobalEnvironment(Map(identifier -> FiTrie.termination(PrimitiveValue.Integer(42))))
    ))
    val initialRouteTarget = rootNode(initial.snapshot).routes.head.target

    val next = expectProgress(expectSuccess(initial.step))
    val nextSnapshot = next.snapshot

    assertNotEquals(nextSnapshot.root, initial.snapshot.root)
    assertEquals(rootNode(nextSnapshot).routes.head.target, initialRouteTarget)
    assert(nextSnapshot.nodes.exists(_.id == initialRouteTarget))
  }

  test("structural references are finite graph edges back to their bound node") {
    val recursive = FiTrie.response(ResponseComputation.StructuralReference(NodeReferenceIndex(0)))
    val snapshot = expectSuccess(Evaluation.start(recursive)).snapshot

    assertEquals(snapshot.nodes.size, 1)
    assertEquals(
      rootNode(snapshot).responses,
      Vector(EvaluationResponse.StructuralReference(snapshot.root))
    )
  }

  test("indexing and filtering an empty normal receiver eliminate their suspensions") {
    val indexing = FiTrie.response(ResponseComputation.Index(
      FiTrie.empty,
      RequestSet.one(Request.Application(FiTrie.termination(PrimitiveValue.Integer(42))))
    ))
    val filtering = FiTrie.response(ResponseComputation.Filter(
      indexing,
      RootKeyExpression.concrete(RootKeySet.one(
        RootKey.Termination(cp.primitive.PrimitiveType.Integer)
      ))
    ))
    val initial = expectSuccess(Evaluation.start(filtering))

    val afterFilterReceiver = expectProgress(expectSuccess(initial.step))
    val normalForm = expectProgress(expectSuccess(afterFilterReceiver.step))

    assertEquals(rootNode(normalForm.snapshot).responses, Vector.empty)
    assertEquals(expectSuccess(normalForm.step), EvaluationStep.Complete)
  }

  private def rootNode(snapshot: EvaluationSnapshot): EvaluationTrieNode = {
    snapshot.nodes.find(_.id == snapshot.root).getOrElse(fail("snapshot has no root node"))
  }

  private def expectProgress(step: EvaluationStep): EvaluationSession = step match {
    case EvaluationStep.Progressed(next) => next
    case EvaluationStep.Complete => fail("expected the session to take a step")
  }

  private def expectSuccess[T](result: Result[T, EvaluationError]): T = result match {
    case Result.Ok(value) => value
    case Result.Err(error) => fail(s"unexpected evaluation error: $error")
  }
}
