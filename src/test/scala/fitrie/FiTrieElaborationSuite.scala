//package cp.fitrie
//
//import cp.fiobs.{Expr, Fiobs, SurfaceType, Term, Type}
//import cp.fiobs.runtime.Value
//import cp.fitrie.elaboration.{
//  CoercionError,
//  Elaborator,
//  FiTrieElaborationError,
//  IntersectionSelectionError,
//  SelectedIntersectionComponent,
//  TypedFiTrie
//}
//import cp.fitrie.evaluation.Evaluation
//import cp.naming.FieldLabel
//import cp.primitive.{PrimitiveType, PrimitiveValue}
//import cp.util.Result
//
//class FiTrieElaborationSuite extends munit.FunSuite {
//  private val integerOne = PrimitiveValue.Integer(1)
//  private val booleanTrue = PrimitiveValue.Boolean(true)
//  private val integerPath = ObservationPath.Termination(PrimitiveType.Integer)
//  private val textPath = ObservationPath.Termination(PrimitiveType.Text)
//  private val integerApplicationRoute = RouteKey.Application(integerPath)
//
//  test("primitive and merge elaboration use termination maps and canonical merge") {
//    val term = Term.Merge(
//      Term.Literal(integerOne),
//      Term.Literal(booleanTrue)
//    )
//
//    assertEquals(
//      Elaborator.infer(term),
//      Result.Ok(TypedFiTrie(
//        FiTrie.node(terminationPayloads = TerminationPayloads.fromEntries(Map(
//          PrimitiveType.Integer -> integerOne,
//          PrimitiveType.Boolean -> booleanTrue
//        ))),
//        Type.Intersection(Type.Integer, Type.Boolean)
//      ))
//    )
//  }
//
//  test("lambda and term application elaborate to one route and one payload request") {
//    val identityType = Type.Arrow(Type.Integer, Type.Integer)
//    val identity = Term.Annotation(Term.Lambda(Term.Variable(0)), identityType)
//    val application = Term.Application(identity, Term.Literal(PrimitiveValue.Integer(42)))
//
//    val translated = expectSuccess(Elaborator.infer(application))
//    val response = translated.trie.responseComputations.toList match {
//      case one :: Nil => one
//      case responses => fail(s"expected one application response, found $responses")
//    }
//
//    response match {
//      case ResponseComputation.Index(receiver, RequestSet(requests)) =>
//        assert(receiver.routeContinuations.contains(integerApplicationRoute))
//        assertEquals(
//          requests,
//          Set(Request.Application(
//            integerPath,
//            FiTrie.termination(PrimitiveValue.Integer(42))
//          ))
//        )
//      case other => fail(s"expected an indexing response, found $other")
//    }
//    assertEquals(translated.inferredType, Type.Integer)
//    assert(translated.trie.isWellScoped())
//  }
//
//  test("contravariant argument coercion preserves exact application-key agreement") {
//    val actualArgumentType = Type.Arrow(Type.Integer, Type.Integer)
//    val expectedArgumentType = Type.Arrow(
//      Type.Intersection(Type.Integer, Type.Text),
//      Type.Integer
//    )
//    val outerFunctionType = Type.Arrow(expectedArgumentType, Type.Integer)
//    val outerFunction = Term.Annotation(
//      Term.Lambda(Term.Application(
//        Term.Variable(0),
//        Term.Merge(
//          Term.Literal(PrimitiveValue.Integer(42)),
//          Term.Literal(PrimitiveValue.Text("ignored"))
//        )
//      )),
//      outerFunctionType
//    )
//    val actualArgument = Term.Annotation(
//      Term.Lambda(Term.Variable(0)),
//      actualArgumentType
//    )
//    val completeTerm = Term.Application(outerFunction, actualArgument)
//    val translated = expectSuccess(Elaborator.infer(completeTerm))
//    val expectedPayloadRoutes = Set(
//      RouteKey.Application(integerPath),
//      RouteKey.Application(textPath)
//    )
//
//    translated.trie.responseComputations.toList match {
//      case ResponseComputation.Index(receiver, RequestSet(requests)) :: Nil =>
//        val applicationRequests = requests.collect {
//          case request: Request.Application => request
//        }
//        val requestArguments = applicationRequests.map(_.argument)
//
//        assertEquals(applicationRequests.map(_.routeKey), receiver.routeContinuations.keySet)
//        assertEquals(requestArguments.size, 1)
//        assertEquals(requestArguments.head.routeContinuations.keySet, expectedPayloadRoutes)
//      case other => fail(s"expected one outer application index, found $other")
//    }
//    assertEquals(
//      Evaluation.observeTermination(translated.trie, PrimitiveType.Integer),
//      Result.Ok(PrimitiveValue.Integer(42))
//    )
//  }
//
//  test("type applications erase their source type argument") {
//    val universalType = Type.ForAll(Type.Top, Type.Integer)
//    val universal = Term.Annotation(
//      Term.TypeLambda(Type.Top, Term.Literal(integerOne)),
//      universalType
//    )
//    val application = Term.TypeApplication(universal, Type.Boolean)
//    val translated = expectSuccess(Elaborator.infer(application))
//
//    translated.trie.responseComputations.toList match {
//      case ResponseComputation.Index(receiver, requests) :: Nil
//          if requests.requests == Set(Request.TypeApplication) =>
//        assert(receiver.routeContinuations.contains(RouteKey.TypeApplication))
//      case other => fail(s"expected one nullary type-application request, found $other")
//    }
//    assertEquals(translated.inferredType, Type.Integer)
//  }
//
//  test("record construction and projection use the field label as their route discriminator") {
//    val label = FieldLabel("answer")
//    val record = Term.Record(label.value, Term.Literal(integerOne))
//    val projection = Term.Annotation(Term.Projection(record, label.value), Type.Integer)
//    val translated = expectSuccess(Elaborator.infer(projection))
//
//    assertEquals(translated.inferredType, Type.Integer)
//    assert(translated.trie.responseComputations.exists {
//      case ResponseComputation.Index(_, requests)
//          if requests.requests == Set(Request.Projection(label)) => true
//      case _ => false
//    })
//  }
//
//  test("a nonrecursive fixed point has exactly the same target as its body") {
//    val fixed = Term.Fix(Type.Integer, Term.Literal(integerOne))
//
//    assertEquals(
//      Elaborator.infer(fixed),
//      Result.Ok(TypedFiTrie(FiTrie.termination(integerOne), Type.Integer))
//    )
//  }
//
//  test("a recursive fixed point ties its occurrence through an untyped filtered structural reference") {
//    val fixed = Term.Fix(Type.Integer, Term.Variable(0))
//    val integerKeys = RootKeySet.one(RootKey.Termination(PrimitiveType.Integer))
//    val expected = FiTrie.response(ResponseComputation.Filter(
//      FiTrie.response(ResponseComputation.StructuralReference(NodeReferenceIndex(1))),
//      integerKeys
//    ))
//
//    assertEquals(
//      Elaborator.infer(fixed),
//      Result.Ok(TypedFiTrie(expected, Type.Integer))
//    )
//    assert(expected.isWellScoped())
//  }
//
//  test("root-distinct intersection elimination emits a suspended shallow filter") {
//    val merge = Term.Merge(Term.Literal(integerOne), Term.Literal(booleanTrue))
//    val selection = Term.Annotation(merge, Type.Integer)
//    val translated = expectSuccess(Elaborator.infer(selection))
//    val integerKeys = RootKeySet.one(RootKey.Termination(PrimitiveType.Integer))
//
//    assertEquals(
//      translated.trie.responseComputations,
//      Set(ResponseComputation.Filter(
//        FiTrie.node(terminationPayloads = TerminationPayloads.fromEntries(Map(
//          PrimitiveType.Integer -> integerOne,
//          PrimitiveType.Boolean -> booleanTrue
//        ))),
//        integerKeys
//      ))
//    )
//  }
//
//  test("same-domain arrow intersection projection elaborates beneath the shared route") {
//    val integerFunctionType = Type.Arrow(Type.Integer, Type.Integer)
//    val booleanFunctionType = Type.Arrow(Type.Integer, Type.Boolean)
//    val integerFunction = Term.Annotation(Term.Lambda(Term.Literal(integerOne)), integerFunctionType)
//    val booleanFunction = Term.Annotation(Term.Lambda(Term.Literal(booleanTrue)), booleanFunctionType)
//    val selection = Term.Annotation(
//      Term.Merge(integerFunction, booleanFunction),
//      integerFunctionType
//    )
//
//    val translated = expectSuccess(Elaborator.infer(selection))
//
//    assertEquals(translated.inferredType, integerFunctionType)
//    assertEquals(translated.trie.routeContinuations.keySet, Set(integerApplicationRoute))
//    assert(translated.trie.isWellScoped())
//  }
//
//  test("a closed opaque-variable selection reports the unresolved theory boundary") {
//    val identityType = Type.Arrow(Type.Integer, Type.Integer)
//    val variableType = Type.Variable(0)
//    val polymorphicType = Type.ForAll(
//      Type.Integer,
//      Type.Arrow(variableType, variableType)
//    )
//    val polymorphicFunction = Term.Annotation(
//      Term.TypeLambda(
//        Type.Integer,
//        Term.Lambda(Term.Annotation(
//          Term.Merge(
//            Term.Variable(0),
//            Term.Literal(PrimitiveValue.Integer(1))
//          ),
//          variableType
//        ))
//      ),
//      polymorphicType
//    )
//    val instantiatedFunction = Term.TypeApplication(polymorphicFunction, identityType)
//    val identity = Term.Annotation(Term.Lambda(Term.Variable(0)), identityType)
//    val completeTerm = Term.Application(
//      Term.Application(instantiatedFunction, identity),
//      Term.Literal(PrimitiveValue.Integer(0))
//    )
//    val expectedError = IntersectionSelectionError.Unsupported(
//      variableType,
//      Type.Integer,
//      SelectedIntersectionComponent.First,
//      RootKeySet.Universal,
//      RootKeySet.one(RootKey.Termination(PrimitiveType.Integer))
//    )
//
//    Elaborator.infer(completeTerm) match {
//      case Result.Err(FiTrieElaborationError.Coercion(CoercionError.IntersectionSelection(error))) =>
//        assertEquals(error, expectedError)
//      case other => fail(s"expected the documented opaque-selection boundary, found $other")
//    }
//  }
//
//  test("nested opaque bounds retain a source distinction erased by target type application") {
//    val alphaType = SurfaceType.Variable("Alpha")
//    val betaType = SurfaceType.Variable("Beta")
//    val alphaFunctionType = SurfaceType.Arrow(alphaType, alphaType)
//    val betaFunctionType = SurfaceType.Arrow(betaType, betaType)
//    val selectedFunction = Expr.Annotation(
//      Expr.Merge(
//        Expr.Annotation(Expr.Lambda("value", Expr.Variable("value")), betaFunctionType),
//        Expr.Annotation(Expr.Lambda("ignored", Expr.Variable("first")), alphaFunctionType)
//      ),
//      alphaFunctionType
//    )
//    val polymorphicType = SurfaceType.ForAll(
//      "Alpha",
//      SurfaceType.Integer,
//      SurfaceType.ForAll(
//        "Beta",
//        alphaType,
//        SurfaceType.Arrow(alphaType, SurfaceType.Arrow(alphaType, alphaType))
//      )
//    )
//    val polymorphicFunction = Expr.Annotation(
//      Expr.TypeLambda(
//        "Alpha",
//        SurfaceType.Integer,
//        Expr.TypeLambda(
//          "Beta",
//          alphaType,
//          Expr.Lambda(
//            "first",
//            Expr.Lambda(
//              "second",
//              Expr.Application(selectedFunction, Expr.Variable("second"))
//            )
//          )
//        )
//      ),
//      polymorphicType
//    )
//    val completeExpression = Expr.Application(
//      Expr.Application(
//        Expr.TypeApplication(
//          Expr.TypeApplication(polymorphicFunction, SurfaceType.Boolean),
//          SurfaceType.Integer
//        ),
//        Expr.Literal(PrimitiveValue.Boolean(false))
//      ),
//      Expr.Literal(PrimitiveValue.Boolean(true))
//    )
//
//    assertEquals(
//      Fiobs.evaluate(completeExpression),
//      Result.Ok(Value.Primitive(PrimitiveValue.Boolean(false)))
//    )
//    completeExpression.toTerm.flatMap(Elaborator.infer(_)) match {
//      case Result.Err(FiTrieElaborationError.Coercion(CoercionError.IntersectionSelection(_))) => ()
//      case other => fail(s"expected the documented opaque-selection boundary, found $other")
//    }
//  }
//
//  test("the public compiler accepts named source expressions without changing Fiobs") {
//    val expression = Expr.Application(
//      Expr.Annotation(
//        Expr.Lambda("value", Expr.Variable("value")),
//        SurfaceType.Arrow(SurfaceType.Integer, SurfaceType.Integer)
//      ),
//      Expr.Literal(PrimitiveValue.Integer(42))
//    )
//
//    FiTrieCompiler.compile(expression) match {
//      case Result.Ok(program) =>
//        assertEquals(program.programType, Type.Integer)
//        assert(program.targetTrie.isWellScoped())
//      case Result.Err(error) => fail(s"unexpected public compilation error: $error")
//    }
//  }
//
//  private def expectSuccess(
//    result: Result[TypedFiTrie, FiTrieElaborationError]
//  ): TypedFiTrie = result match {
//    case Result.Ok(value) => value
//    case Result.Err(error) => fail(s"unexpected elaboration error: $error")
//  }
//}
