//package cp.fitrie
//
//import cp.fiobs.{Type, TypeContext}
//import cp.fitrie.elaboration.{
//  Coercion,
//  CoercionError,
//  IntersectionSelectionError,
//  SelectedIntersectionComponent,
//  TypeLabels
//}
//import cp.naming.FieldLabel
//import cp.primitive.{PrimitiveType, PrimitiveValue}
//import cp.util.Result
//
//class FiTrieCoercionSuite extends munit.FunSuite {
//  private val integerKeys = RootKeySet.one(RootKey.Termination(PrimitiveType.Integer))
//  private val integerPath = ObservationPath.Termination(PrimitiveType.Integer)
//  private val booleanPath = ObservationPath.Termination(PrimitiveType.Boolean)
//  private val integerApplicationRoute = RouteKey.Application(integerPath)
//  private val booleanApplicationRoute = RouteKey.Application(booleanPath)
//  private val integerApplicationKeys = RootKeySet.one(integerApplicationRoute.rootKey)
//
//  test("type labels compile every source constructor to root keys only") {
//    val projectionKey = RouteKey.Projection(FieldLabel("field")).rootKey
//
//    assertEquals(TypeLabels.compile(Type.Integer), integerKeys)
//    assertEquals(TypeLabels.compile(Type.Top), RootKeySet.empty)
//    assertEquals(TypeLabels.compile(Type.Bottom), RootKeySet.Universal)
//    assertEquals(TypeLabels.compile(Type.Variable(0)), RootKeySet.Universal)
//    assertEquals(TypeLabels.compile(Type.Arrow(Type.Integer, Type.Boolean)), integerApplicationKeys)
//    assertEquals(
//      TypeLabels.compile(Type.ForAll(Type.Top, Type.Integer)),
//      RootKeySet.one(RouteKey.TypeApplication.rootKey)
//    )
//    assertEquals(
//      TypeLabels.compile(Type.Record("field", Type.Integer)),
//      RootKeySet.one(projectionKey)
//    )
//    assertEquals(
//      TypeLabels.compile(Type.Intersection(Type.Integer, Type.Boolean)),
//      RootKeySet.Finite(Set(
//        RootKey.Termination(PrimitiveType.Integer),
//        RootKey.Termination(PrimitiveType.Boolean)
//      ))
//    )
//  }
//
//  test("reflexive coercion preserves its trie literally") {
//    val trie = FiTrie.termination(PrimitiveValue.Integer(1))
//
//    assertEquals(
//      Coercion.coerce(trie, Type.Integer, Type.Integer, TypeContext.empty),
//      Result.Ok(trie)
//    )
//  }
//
//  test("exact top and bottom elimination remain suspended filters") {
//    val trie = FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(0)))
//
//    assertEquals(
//      Coercion.coerce(trie, Type.Integer, Type.Top, TypeContext.empty),
//      Result.Ok(FiTrie.response(ResponseComputation.Filter(trie, RootKeySet.empty)))
//    )
//    assertEquals(
//      Coercion.coerce(trie, Type.Bottom, Type.Integer, TypeContext.empty),
//      Result.Ok(FiTrie.response(ResponseComputation.Filter(trie, integerKeys)))
//    )
//  }
//
//  test("universal coercion checks its bound statically and emits a nullary route") {
//    val sourceType = Type.ForAll(Type.Top, Type.Integer)
//    val targetType = Type.ForAll(Type.Bottom, Type.Integer)
//    val trie = FiTrie.route(
//      RouteKey.TypeApplication,
//      FiTrie.termination(PrimitiveValue.Integer(1))
//    )
//    val coerced = expectSuccess(Coercion.coerce(
//      trie,
//      sourceType,
//      targetType,
//      TypeContext.empty
//    ))
//
//    assert(coerced.routeContinuations.contains(RouteKey.TypeApplication))
//    assert(coerced.responseComputations.contains(
//      ResponseComputation.Filter(trie, RootKeySet.empty)
//    ))
//  }
//
//  test("record coercion consumes and reconstructs exactly one projection route") {
//    val label = FieldLabel("field")
//    val sourceType = Type.Record(label.value, Type.Integer)
//    val targetType = Type.Record(label.value, Type.Top)
//    val trie = FiTrie.route(
//      RouteKey.Projection(label),
//      FiTrie.termination(PrimitiveValue.Integer(1))
//    )
//    val coerced = expectSuccess(Coercion.coerce(
//      trie,
//      sourceType,
//      targetType,
//      TypeContext.empty
//    ))
//
//    assertEquals(coerced.routeContinuations.keySet, Set(RouteKey.Projection(label)))
//    assert(coerced.responseComputations.contains(
//      ResponseComputation.Filter(trie, RootKeySet.empty)
//    ))
//  }
//
//  test("same-domain arrows select a component beneath their shared application route") {
//    val leftType = Type.Arrow(Type.Integer, Type.Integer)
//    val rightType = Type.Arrow(Type.Integer, Type.Boolean)
//    val sourceType = Type.Intersection(leftType, rightType)
//
//    val trie = FiTrie.route(
//      integerApplicationRoute,
//      FiTrie.node(terminationPayloads = TerminationPayloads.fromEntries(Map(
//        PrimitiveType.Integer -> PrimitiveValue.Integer(1),
//        PrimitiveType.Boolean -> PrimitiveValue.Boolean(true)
//      )))
//    )
//    val coerced = expectSuccess(Coercion.coerce(
//      trie,
//      sourceType,
//      leftType,
//      TypeContext.empty
//    ))
//
//    assertEquals(coerced.routeContinuations.keySet, Set(integerApplicationRoute))
//    assert(coerced.isWellScoped())
//  }
//
//  test("opaque component selection remains an explicit unresolved theory boundary") {
//    val context = TypeContext.empty.extend(Type.Integer)
//    val variableType = Type.Variable(0)
//    val sourceType = Type.Intersection(variableType, Type.Integer)
//    val trie = FiTrie.node(
//      routeContinuations = Map(integerApplicationRoute -> FiTrie.empty),
//      terminationPayloads = TerminationPayloads.one(PrimitiveValue.Integer(1))
//    )
//
//    val expectedError = IntersectionSelectionError.Unsupported(
//      variableType,
//      Type.Integer,
//      SelectedIntersectionComponent.First,
//      RootKeySet.Universal,
//      integerKeys
//    )
//
//    assertEquals(
//      Coercion.coerce(trie, sourceType, variableType, context),
//      Result.Err(CoercionError.IntersectionSelection(expectedError))
//    )
//  }
//
//  test("different-domain arrow selection uses distinct application-path keys") {
//    val integerFunctionType = Type.Arrow(Type.Integer, Type.Integer)
//    val booleanFunctionType = Type.Arrow(Type.Boolean, Type.Boolean)
//    val sourceType = Type.Intersection(integerFunctionType, booleanFunctionType)
//    val trie = FiTrie.node(routeContinuations = Map(
//      integerApplicationRoute -> FiTrie.termination(PrimitiveValue.Integer(1)),
//      booleanApplicationRoute -> FiTrie.termination(PrimitiveValue.Boolean(true))
//    ))
//    val coerced = expectSuccess(Coercion.coerce(
//      trie,
//      sourceType,
//      integerFunctionType,
//      TypeContext.empty
//    ))
//
//    assertEquals(
//      coerced.responseComputations,
//      Set(ResponseComputation.Filter(trie, integerApplicationKeys))
//    )
//  }
//
//  private def expectSuccess(
//    result: Result[FiTrie, CoercionError]
//  ): FiTrie = result match {
//    case Result.Ok(trie) => trie
//    case Result.Err(error) => fail(s"unexpected coercion error: $error")
//  }
//}
