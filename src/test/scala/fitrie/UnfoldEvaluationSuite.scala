package cp.fitrie

import cp.fitrie.evaluation.*
import cp.naming.{FieldLabel, Namespace}
import cp.primitive.{PrimitiveType, PrimitiveValue}
import cp.util.Result

class UnfoldEvaluationSuite extends munit.FunSuite {
  private val answer = FiTrie.termination(PrimitiveValue.Integer(42))

  private def index(receiver: FiTrie, request: Request): FiTrie = {
    FiTrie.response(ResponseComputation.Index(receiver, RequestSet.one(request)))
  }

  private def assertAnswer(trie: FiTrie): Unit = {
    assertEquals(Evaluation.observeTermination(trie, PrimitiveType.Integer), Result.Ok(PrimitiveValue.Integer(42)))
  }

  test("unfold selects a folded continuation in both static indexing and closed execution") {
    val folded = FiTrie.route(RouteKey.Unfold, answer)
    assertEquals(folded.index(RequestSet.one(Request.Unfold)), Result.Ok(answer))
    assertAnswer(index(folded, Request.Unfold))
  }

  test("a fold keeps its payload suspended until unfolding") {
    val missing = Namespace("Missing").identifier("value")
    val folded = FiTrie.route(RouteKey.Unfold, FiTrie.response(ResponseComputation.Global(missing)))
    val session = expectSuccess(Evaluation.start(folded))
    assertEquals(session.step, Result.Ok(EvaluationStep.Complete))
    assertEquals(
      Evaluation.observeTermination(index(folded, Request.Unfold), PrimitiveType.Integer),
      Result.Err(EvaluationError.UnknownGlobal(missing))
    )
  }

  test("unfold neither captures nor removes an application's term binder") {
    val variable = FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(0)))
    val foldingFunction = FiTrie.route(RouteKey.Application, FiTrie.route(RouteKey.Unfold, variable))
    assertAnswer(index(index(foldingFunction, Request.Application(answer)), Request.Unfold))
    val foldedFunction = FiTrie.route(RouteKey.Unfold, FiTrie.route(RouteKey.Application, variable))
    assertAnswer(index(index(foldedFunction, Request.Unfold), Request.Application(answer)))
  }

  test("polymorphic specialization selects the unfold root of a recursive argument") {
    val argumentPaths = ObservationPathInterface.Recursive(
      ObservationPathInterface.termination(PrimitiveType.Integer)
    )
    val identity = FiTrie.route(RouteKey.TypeApplication, FiTrie.route(
      RouteKey.Application,
      FiTrie.response(ResponseComputation.Filter(
        FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(0))),
        RootKeyExpression.front(ObservationPathInterface.variable(PathVariableIndex(0)))
      ))
    ))
    val specialized = index(identity, Request.TypeApplication(argumentPaths))
    val applied = index(specialized, Request.Application(FiTrie.route(RouteKey.Unfold, answer)))
    assertAnswer(index(applied, Request.Unfold))
  }

  test("successive unfold requests retain a fixed point's structural binding") {
    val body = FiTrie.route(RouteKey.Unfold, FiTrie.node(routeContinuations = Map(
      RouteKey.Projection(FieldLabel("head")) -> answer,
      RouteKey.Projection(FieldLabel("tail")) ->
        FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(0)))
    )))
    val recursive = body.tieFixedPoint(
      TermVariableIndex(0),
      RootKeyExpression.concrete(RootKeySet.one(RouteKey.Unfold.rootKey))
    )
    assert(recursive.isWellScoped())
    val later = (1 to 5).foldLeft(recursive) { (stream, _) =>
      index(index(stream, Request.Unfold), Request.Projection(FieldLabel("tail")))
    }
    assertAnswer(index(index(later, Request.Unfold), Request.Projection(FieldLabel("head"))))
  }

  test("merge shares an unfold prefix and keeps both disjoint payloads") {
    val left = FiTrie.route(RouteKey.Unfold, answer)
    val right = FiTrie.route(RouteKey.Unfold, FiTrie.termination(PrimitiveValue.Boolean(true)))
    val merged = expectSuccess(FiTrie.merge(left, right))
    val unfolded = index(merged, Request.Unfold)
    assertAnswer(unfolded)
    assertEquals(
      Evaluation.observeTermination(unfolded, PrimitiveType.Boolean),
      Result.Ok(PrimitiveValue.Boolean(true))
    )
  }

  test("snapshots and rendering preserve unfold requests") {
    val entry = index(FiTrie.route(RouteKey.Unfold, answer), Request.Unfold)
    val snapshot = expectSuccess(Evaluation.start(entry)).snapshot
    val root = snapshot.nodes.find(_.id == snapshot.root).getOrElse(fail("snapshot has no root"))
    assert(root.responses.exists {
      case EvaluationResponse.Index(_, requests) => requests == Vector(EvaluationRequest.Unfold)
      case _ => false
    })
    assert(entry.toString.contains("ωᵘⁿᶠᵒˡᵈ"))
    assert(entry.toString.contains("κᵘⁿᶠᵒˡᵈ"))
  }

  test("a recursive value retains its captured term argument after following a backedge") {
    val recursive = capturedStream("head", polymorphic = false)
    val function = FiTrie.route(RouteKey.Application, recursive)
    val applied = index(function, Request.Application(answer))
    val next = index(index(applied, Request.Unfold), Request.Projection(FieldLabel("tail")))
    assertAnswer(index(index(next, Request.Unfold), Request.Projection(FieldLabel("head"))))
  }

  test("a recursive value retains its captured polymorphic argument after following a backedge") {
    val function = FiTrie.route(RouteKey.TypeApplication, FiTrie.route(
      RouteKey.Application,
      capturedStream("head", polymorphic = true)
    ))
    val specialized = index(function, Request.TypeApplication(
      ObservationPathInterface.termination(PrimitiveType.Integer)
    ))
    val applied = index(specialized, Request.Application(answer))
    val next = index(index(applied, Request.Unfold), Request.Projection(FieldLabel("tail")))
    assertAnswer(index(index(next, Request.Unfold), Request.Projection(FieldLabel("head"))))
  }

  test("an enclosing recursive universal can be instantiated differently at successive nodes") {
    val variable = FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(0)))
    val self = FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(1)))
    val body = FiTrie.route(RouteKey.Unfold, FiTrie.route(RouteKey.TypeApplication, FiTrie.route(
      RouteKey.Application,
      FiTrie.node(routeContinuations = Map(
        RouteKey.Projection(FieldLabel("head")) -> FiTrie.response(ResponseComputation.Filter(
          variable,
          RootKeyExpression.front(ObservationPathInterface.variable(PathVariableIndex(0)))
        )),
        RouteKey.Projection(FieldLabel("tail")) -> self
      ))
    )))
    val recursive = body.tieFixedPoint(
      TermVariableIndex(0),
      RootKeyExpression.concrete(RootKeySet.one(RouteKey.Unfold.rootKey))
    )
    val booleans = index(index(recursive, Request.Unfold), Request.TypeApplication(
      ObservationPathInterface.termination(PrimitiveType.Boolean)
    ))
    val first = index(booleans, Request.Application(FiTrie.termination(PrimitiveValue.Boolean(true))))
    val next = index(first, Request.Projection(FieldLabel("tail")))
    val integers = index(index(next, Request.Unfold), Request.TypeApplication(
      ObservationPathInterface.termination(PrimitiveType.Integer)
    ))
    assertAnswer(index(index(integers, Request.Application(answer)), Request.Projection(FieldLabel("head"))))
  }

  test("runtime merge preserves the binders of independently closed recursive functions") {
    val left = Namespace("Library").identifier("left")
    val right = Namespace("Library").identifier("right")
    val functions = FiTrie.node(responseComputations = Set(
      ResponseComputation.Global(left),
      ResponseComputation.Global(right)
    ))
    val globals = GlobalEnvironment(Map(
      left -> FiTrie.route(RouteKey.Application, capturedStream("left", polymorphic = false)),
      right -> FiTrie.route(RouteKey.Application, capturedStream("right", polymorphic = false))
    ))
    val applied = index(functions, Request.Application(answer))
    val next = index(index(applied, Request.Unfold), Request.Projection(FieldLabel("tail")))
    List("left", "right").foreach { label =>
      assertEquals(
        Evaluation.observeTermination(
          index(index(next, Request.Unfold), Request.Projection(FieldLabel(label))),
          PrimitiveType.Integer,
          globals
        ),
        Result.Ok(PrimitiveValue.Integer(42))
      )
    }
  }

  private def capturedStream(label: String, polymorphic: Boolean): FiTrie = {
    val argument = FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(1)))
    val head = if (polymorphic) FiTrie.response(ResponseComputation.Filter(
      argument,
      RootKeyExpression.front(ObservationPathInterface.variable(PathVariableIndex(0)))
    )) else argument
    val body = FiTrie.route(RouteKey.Unfold, FiTrie.node(routeContinuations = Map(
      RouteKey.Projection(FieldLabel(label)) -> head,
      RouteKey.Projection(FieldLabel("tail")) ->
        FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(0)))
    )))
    body.tieFixedPoint(TermVariableIndex(0), RootKeyExpression.concrete(RootKeySet.one(RouteKey.Unfold.rootKey)))
  }

  private def expectSuccess[T, E](result: Result[T, E]): T = result match {
    case Result.Ok(value) => value
    case Result.Err(error) => fail(s"unexpected error: $error")
  }
}
