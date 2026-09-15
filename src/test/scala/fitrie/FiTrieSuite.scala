//package cp.fitrie
//
//import cp.naming.FieldLabel
//import cp.primitive.{PrimitiveType, PrimitiveValue}
//import cp.util.Result
//
//class FiTrieSuite extends munit.FunSuite {
//  private val answerLabel = FieldLabel("answer")
//  private val integerOne = PrimitiveValue.Integer(1)
//  private val booleanTrue = PrimitiveValue.Boolean(true)
//  private val applicationPath = ObservationPath.Empty
//  private val applicationRoute = RouteKey.Application(applicationPath)
//
//  test("root-key sets represent finite labels and the complete key universe") {
//    val integerKey = RootKey.Termination(PrimitiveType.Integer)
//    val booleanKey = RootKey.Termination(PrimitiveType.Boolean)
//    val integerKeys = RootKeySet.one(integerKey)
//    val booleanKeys = RootKeySet.one(booleanKey)
//
//    assert(integerKeys.isDisjointFrom(booleanKeys))
//    assertEquals(
//      integerKeys.union(booleanKeys),
//      RootKeySet.Finite(Set(integerKey, booleanKey))
//    )
//    assertEquals(RootKeySet.Universal.intersection(integerKeys), integerKeys)
//    assert(!RootKeySet.Universal.isDisjointFrom(integerKeys))
//  }
//
//  test("canonical merge unions every component pointwise") {
//    val applicationBody = FiTrie.termination(booleanTrue)
//    val projectionBody = FiTrie.termination(integerOne)
//    val left = FiTrie.node(
//      responseComputations = Set(ResponseComputation.LocalVariable(TermVariableIndex(0))),
//      routeContinuations = Map(applicationRoute -> applicationBody),
//      terminationPayloads = TerminationPayloads.one(integerOne)
//    )
//    val right = FiTrie.node(
//      responseComputations = Set(ResponseComputation.Global(
//        cp.naming.Namespace("Library").identifier("value")
//      )),
//      routeContinuations = Map(RouteKey.Projection(answerLabel) -> projectionBody),
//      terminationPayloads = TerminationPayloads.one(booleanTrue)
//    )
//
//    val expected = FiTrie.node(
//      responseComputations = left.responseComputations ++ right.responseComputations,
//      routeContinuations = left.routeContinuations ++ right.routeContinuations,
//      terminationPayloads = TerminationPayloads.fromEntries(Map(
//        PrimitiveType.Integer -> integerOne,
//        PrimitiveType.Boolean -> booleanTrue
//      ))
//    )
//
//    assertEquals(FiTrie.merge(left, right), Result.Ok(expected))
//    assertEquals(FiTrie.merge(right, left), Result.Ok(expected))
//    assertEquals(FiTrie.merge(left, left), Result.Ok(left))
//    assertEquals(FiTrie.mergeAll(List(left, right)), Result.Ok(expected))
//    assertEquals(FiTrie.mergeAll(Nil), Result.Ok(FiTrie.empty))
//  }
//
//  test("canonical merge reports a nested termination conflict without overwriting") {
//    val routeKey = RouteKey.Projection(answerLabel)
//    val left = FiTrie.route(routeKey, FiTrie.termination(PrimitiveValue.Integer(1)))
//    val right = FiTrie.route(routeKey, FiTrie.termination(PrimitiveValue.Integer(2)))
//
//    assertEquals(
//      FiTrie.merge(left, right),
//      Result.Err(FiTrieMergeError.ConflictingTermination(
//        List(routeKey),
//        PrimitiveType.Integer,
//        PrimitiveValue.Integer(1),
//        PrimitiveValue.Integer(2)
//      ))
//    )
//  }
//
//  test("canonical merge is associative on compatible tries") {
//    val integer = FiTrie.termination(integerOne)
//    val boolean = FiTrie.termination(booleanTrue)
//    val projection = FiTrie.route(
//      RouteKey.Projection(answerLabel),
//      FiTrie.termination(PrimitiveValue.Text("field"))
//    )
//
//    val leftAssociated = FiTrie.merge(integer, boolean).flatMap(FiTrie.merge(_, projection))
//    val rightAssociated = FiTrie.merge(boolean, projection).flatMap(FiTrie.merge(integer, _))
//
//    assertEquals(leftAssociated, rightAssociated)
//  }
//
//  test("canonical merge combines common application routes under their shared binder") {
//    val parameter = ResponseComputation.LocalVariable(TermVariableIndex(0))
//    val left = FiTrie.route(
//      applicationRoute,
//      FiTrie.node(
//        responseComputations = Set(parameter),
//        terminationPayloads = TerminationPayloads.one(integerOne)
//      )
//    )
//    val right = FiTrie.route(
//      applicationRoute,
//      FiTrie.node(
//        responseComputations = Set(parameter),
//        terminationPayloads = TerminationPayloads.one(booleanTrue)
//      )
//    )
//    val expectedBody = FiTrie.node(
//      responseComputations = Set(parameter),
//      terminationPayloads = TerminationPayloads.fromEntries(Map(
//        PrimitiveType.Integer -> integerOne,
//        PrimitiveType.Boolean -> booleanTrue
//      ))
//    )
//
//    assertEquals(
//      FiTrie.merge(left, right),
//      Result.Ok(FiTrie.route(applicationRoute, expectedBody))
//    )
//    assert(FiTrie.route(applicationRoute, expectedBody).isWellScoped())
//  }
//
//  test("filtering restricts only root keys and preserves complete route children") {
//    val routeKey = RouteKey.Projection(answerLabel)
//    val routeChild = FiTrie.node(
//      routeContinuations = Map(RouteKey.TypeApplication -> FiTrie.empty),
//      terminationPayloads = TerminationPayloads.one(integerOne)
//    )
//    val trie = FiTrie.node(
//      routeContinuations = Map(routeKey -> routeChild),
//      terminationPayloads = TerminationPayloads.one(booleanTrue)
//    )
//
//    val filtered = trie.filter(RootKeySet.one(routeKey.rootKey))
//
//    assertEquals(filtered.routeContinuations, Map(routeKey -> routeChild))
//    assert(filtered.terminationPayloads.isEmpty)
//    assertEquals(filtered.routeContinuations(routeKey), routeChild)
//  }
//
//  test("filtering suspends unresolved responses beneath the same filter") {
//    val originalResponse = ResponseComputation.StructuralReference(NodeReferenceIndex(0))
//    val selectedKeys = RootKeySet.one(RootKey.Termination(PrimitiveType.Integer))
//    val filtered = FiTrie.response(originalResponse).filter(selectedKeys)
//    val nestedResponse = FiTrie.response(
//      ResponseComputation.StructuralReference(NodeReferenceIndex(1))
//    )
//
//    assertEquals(
//      filtered.responseComputations,
//      Set(ResponseComputation.Filter(nestedResponse, selectedKeys))
//    )
//    assert(filtered.isWellScoped())
//  }
//
//  test("successive filters intersect their selected root-key sets") {
//    val trie = FiTrie.node(terminationPayloads = TerminationPayloads.fromEntries(Map(
//      PrimitiveType.Integer -> integerOne,
//      PrimitiveType.Boolean -> booleanTrue
//    )))
//    val integerKeys = RootKeySet.one(RootKey.Termination(PrimitiveType.Integer))
//    val bothKeys = integerKeys.union(
//      RootKeySet.one(RootKey.Termination(PrimitiveType.Boolean))
//    )
//
//    assertEquals(
//      trie.filter(bothKeys).filter(integerKeys),
//      trie.filter(bothKeys.intersection(integerKeys))
//    )
//  }
//
//  test("multi-request indexing consumes routes and merges every selected child") {
//    val projectionKey = RouteKey.Projection(answerLabel)
//    val trie = FiTrie.node(
//      routeContinuations = Map(
//        RouteKey.TypeApplication -> FiTrie.termination(integerOne),
//        projectionKey -> FiTrie.termination(booleanTrue)
//      ),
//      terminationPayloads = TerminationPayloads.one(PrimitiveValue.Text("discarded"))
//    )
//    val requests = RequestSet(Set(
//      Request.TypeApplication,
//      Request.Projection(answerLabel)
//    ))
//
//    assertEquals(
//      trie.index(requests),
//      Result.Ok(FiTrie.node(terminationPayloads = TerminationPayloads.fromEntries(Map(
//        PrimitiveType.Integer -> integerOne,
//        PrimitiveType.Boolean -> booleanTrue
//      ))))
//    )
//  }
//
//  test("application indexing substitutes its argument structurally") {
//    val identity = FiTrie.route(
//      applicationRoute,
//      FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(0)))
//    )
//    val argument = FiTrie.termination(integerOne)
//
//    assertEquals(
//      identity.index(RequestSet.one(Request.Application(applicationPath, argument))),
//      Result.Ok(argument)
//    )
//  }
//
//  test("multiple application requests instantiate and merge every payload at the same route") {
//    val identity = FiTrie.route(
//      applicationRoute,
//      FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(0)))
//    )
//    val requests = RequestSet(Set(
//      Request.Application(applicationPath, FiTrie.termination(integerOne)),
//      Request.Application(applicationPath, FiTrie.termination(booleanTrue))
//    ))
//
//    assertEquals(
//      identity.index(requests),
//      Result.Ok(FiTrie.node(terminationPayloads = TerminationPayloads.fromEntries(Map(
//        PrimitiveType.Integer -> integerOne,
//        PrimitiveType.Boolean -> booleanTrue
//      ))))
//    )
//  }
//
//  test("application binders shadow substitution while outer variables remain substitutable") {
//    val continuation = FiTrie.node(responseComputations = Set(
//      ResponseComputation.LocalVariable(TermVariableIndex(0)),
//      ResponseComputation.LocalVariable(TermVariableIndex(1))
//    ))
//    val function = FiTrie.route(applicationRoute, continuation)
//    val replacement = FiTrie.termination(integerOne)
//    val substituted = function.substituteTermVariable(TermVariableIndex(0), replacement)
//
//    substituted match {
//      case Result.Ok(trie) =>
//        val body = trie.routeContinuations(applicationRoute)
//        assert(body.responseComputations.contains(
//          ResponseComputation.LocalVariable(TermVariableIndex(0))
//        ))
//        assertEquals(body.terminationPayloads, TerminationPayloads.one(integerOne))
//      case Result.Err(error) => fail(s"unexpected substitution failure: $error")
//    }
//  }
//
//  test("substitution shifts a replacement only across application binders it traverses") {
//    val replacement = FiTrie.response(
//      ResponseComputation.LocalVariable(TermVariableIndex(0))
//    )
//    val trie = FiTrie.route(
//      applicationRoute,
//      FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(2)))
//    )
//
//    assertEquals(
//      trie.substituteTermVariable(TermVariableIndex(1), replacement),
//      Result.Ok(FiTrie.route(
//        applicationRoute,
//        FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(1)))
//      ))
//    )
//  }
//
//  test("application routes introduce term scope while every trie introduces node scope") {
//    val scoped = FiTrie.route(
//      applicationRoute,
//      FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(0)))
//    )
//    val freeTermVariable = FiTrie.response(
//      ResponseComputation.LocalVariable(TermVariableIndex(0))
//    )
//    val freeNodeReference = FiTrie.response(
//      ResponseComputation.StructuralReference(NodeReferenceIndex(1))
//    )
//
//    assert(scoped.isWellScoped())
//    assert(!freeTermVariable.isWellScoped())
//    assert(!freeNodeReference.isWellScoped())
//  }
//
//  test("fixed-point tying replaces only the selected variable with a filtered structural reference") {
//    val integerKeys = RootKeySet.one(RootKey.Termination(PrimitiveType.Integer))
//    val body = FiTrie.route(
//      applicationRoute,
//      FiTrie.node(responseComputations = Set(
//        ResponseComputation.LocalVariable(TermVariableIndex(0)),
//        ResponseComputation.LocalVariable(TermVariableIndex(1))
//      ))
//    )
//    val tied = body.tieFixedPoint(TermVariableIndex(0), integerKeys)
//    val applicationBody = tied.routeContinuations(applicationRoute)
//
//    assert(applicationBody.responseComputations.contains(
//      ResponseComputation.LocalVariable(TermVariableIndex(0))
//    ))
//    assert(applicationBody.responseComputations.contains(ResponseComputation.Filter(
//      FiTrie.response(ResponseComputation.StructuralReference(NodeReferenceIndex(2))),
//      integerKeys
//    )))
//    assert(tied.isWellScoped())
//  }
//
//  test("fixed-point tying traverses application payloads inside indexing responses") {
//    val integerKeys = RootKeySet.one(RootKey.Termination(PrimitiveType.Integer))
//    val body = FiTrie.response(ResponseComputation.Index(
//      FiTrie.empty,
//      RequestSet.one(Request.Application(applicationPath, FiTrie.response(
//        ResponseComputation.LocalVariable(TermVariableIndex(0))
//      )))
//    ))
//    val tied = body.tieFixedPoint(TermVariableIndex(0), integerKeys)
//    val expectedArgument = FiTrie.response(ResponseComputation.Filter(
//      FiTrie.response(ResponseComputation.StructuralReference(NodeReferenceIndex(2))),
//      integerKeys
//    ))
//
//    assertEquals(
//      tied,
//      FiTrie.response(ResponseComputation.Index(
//        FiTrie.empty,
//        RequestSet.one(Request.Application(applicationPath, expectedArgument))
//      ))
//    )
//    assert(tied.isWellScoped())
//  }
//
//  test("fixed-point tying at a nonzero index preserves nearer variables and closes the gap") {
//    val integerKeys = RootKeySet.one(RootKey.Termination(PrimitiveType.Integer))
//    val body = FiTrie.node(responseComputations = Set(
//      ResponseComputation.LocalVariable(TermVariableIndex(0)),
//      ResponseComputation.LocalVariable(TermVariableIndex(1)),
//      ResponseComputation.LocalVariable(TermVariableIndex(2))
//    ))
//    val tied = body.tieFixedPoint(TermVariableIndex(1), integerKeys)
//
//    assert(tied.responseComputations.contains(
//      ResponseComputation.LocalVariable(TermVariableIndex(0))
//    ))
//    assert(tied.responseComputations.contains(
//      ResponseComputation.LocalVariable(TermVariableIndex(1))
//    ))
//    assert(tied.responseComputations.contains(ResponseComputation.Filter(
//      FiTrie.response(ResponseComputation.StructuralReference(NodeReferenceIndex(1))),
//      integerKeys
//    )))
//    assert(tied.isWellScoped(termVariableDepth = 2))
//  }
//}
