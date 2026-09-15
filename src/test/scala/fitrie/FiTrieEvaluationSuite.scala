//package cp.fitrie
//
//import cp.fiobs.{Term, Type}
//import cp.fitrie.elaboration.{Elaborator, FiTrieElaborationError, TypedFiTrie}
//import cp.fitrie.evaluation.{Evaluation, EvaluationError, GlobalEnvironment}
//import cp.naming.Namespace
//import cp.primitive.{BinaryOperator, PrimitiveType, PrimitiveValue}
//import cp.util.Result
//
//class FiTrieEvaluationSuite extends munit.FunSuite {
//  private val applicationPath = ObservationPath.Empty
//  private val applicationRoute = RouteKey.Application(applicationPath)
//
//  test("lazy observation returns a known termination without resolving unrelated responses") {
//    val missing = Namespace("Library").identifier("missing")
//    val trie = FiTrie.node(
//      responseComputations = Set(ResponseComputation.Global(missing)),
//      terminationPayloads = TerminationPayloads.one(PrimitiveValue.Integer(42))
//    )
//
//    assertEquals(
//      Evaluation.observeTermination(trie, PrimitiveType.Integer),
//      Result.Ok(PrimitiveValue.Integer(42))
//    )
//  }
//
//  test("term application indexes an elaborated lambda lazily") {
//    val identityType = Type.Arrow(Type.Integer, Type.Integer)
//    val identity = Term.Annotation(Term.Lambda(Term.Variable(0)), identityType)
//    val application = Term.Application(identity, Term.Literal(PrimitiveValue.Integer(42)))
//
//    assertEquals(evaluateInteger(application), PrimitiveValue.Integer(42))
//  }
//
//  test("nested lambdas retain an outer argument across an inner application binder") {
//    val functionType = Type.Arrow(
//      Type.Integer,
//      Type.Arrow(Type.Integer, Type.Integer)
//    )
//    val function = Term.Annotation(
//      Term.Lambda(Term.Lambda(Term.Variable(1))),
//      functionType
//    )
//    val application = Term.Application(
//      Term.Application(function, Term.Literal(PrimitiveValue.Integer(42))),
//      Term.Literal(PrimitiveValue.Integer(0))
//    )
//
//    assertEquals(evaluateInteger(application), PrimitiveValue.Integer(42))
//  }
//
//  test("lazy indexing merges every application request sharing the same route") {
//    val identity = FiTrie.route(
//      applicationRoute,
//      FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(0)))
//    )
//    val indexing = FiTrie.response(ResponseComputation.Index(
//      identity,
//      RequestSet(Set(
//        Request.Application(applicationPath, FiTrie.termination(PrimitiveValue.Integer(1))),
//        Request.Application(applicationPath, FiTrie.termination(PrimitiveValue.Boolean(true)))
//      ))
//    ))
//
//    assertEquals(
//      Evaluation.observeTermination(indexing, PrimitiveType.Integer),
//      Result.Ok(PrimitiveValue.Integer(1))
//    )
//    assertEquals(
//      Evaluation.observeTermination(indexing, PrimitiveType.Boolean),
//      Result.Ok(PrimitiveValue.Boolean(true))
//    )
//  }
//
//  test("call-by-name application can discard a divergent argument") {
//    val constantType = Type.Arrow(Type.Integer, Type.Integer)
//    val constant = Term.Annotation(
//      Term.Lambda(Term.Literal(PrimitiveValue.Integer(1))),
//      constantType
//    )
//    val divergentArgument = Term.Fix(Type.Integer, Term.Variable(0))
//    val application = Term.Application(constant, divergentArgument)
//
//    assertEquals(evaluateInteger(application), PrimitiveValue.Integer(1))
//  }
//
//  test("nullary type application observes an elaborated universal") {
//    val universal = Term.Annotation(
//      Term.TypeLambda(Type.Top, Term.Literal(PrimitiveValue.Integer(7))),
//      Type.ForAll(Type.Top, Type.Integer)
//    )
//    val application = Term.TypeApplication(universal, Type.Boolean)
//
//    assertEquals(evaluateInteger(application), PrimitiveValue.Integer(7))
//  }
//
//  test("projection consumes exactly one matching record route") {
//    val projection = Term.Annotation(
//      Term.Projection(
//        Term.Record("answer", Term.Literal(PrimitiveValue.Integer(9))),
//        "answer"
//      ),
//      Type.Integer
//    )
//
//    assertEquals(evaluateInteger(projection), PrimitiveValue.Integer(9))
//  }
//
//  test("primitive responses evaluate their checked operands") {
//    val expression = Term.Binary(
//      BinaryOperator.Multiply,
//      Term.Binary(
//        BinaryOperator.Add,
//        Term.Literal(PrimitiveValue.Integer(3)),
//        Term.Literal(PrimitiveValue.Integer(4))
//      ),
//      Term.Literal(PrimitiveValue.Integer(6))
//    )
//
//    assertEquals(evaluateInteger(expression), PrimitiveValue.Integer(42))
//  }
//
//  test("primitive failures remain structured at the calculus-specific response boundary") {
//    val divisionByZero = Term.Binary(
//      BinaryOperator.Divide,
//      Term.Literal(PrimitiveValue.Integer(1)),
//      Term.Literal(PrimitiveValue.Integer(0))
//    )
//    val translated = expectSuccess(Elaborator.infer(divisionByZero))
//
//    Evaluation.observeTermination(translated.trie, PrimitiveType.Integer) match {
//      case Result.Err(
//        EvaluationError.PrimitiveFailure(
//          cp.primitive.PrimitiveOperationError.DivisionByZero(BinaryOperator.Divide)
//        )
//      ) => ()
//      case other => fail(s"expected a structured division-by-zero error, found $other")
//    }
//  }
//
//  test("a lazy conditional evaluates only its condition and selected branch") {
//    val divergentBranch = Term.Fix(Type.Integer, Term.Variable(0))
//    val conditional = Term.If(
//      Term.Literal(PrimitiveValue.Boolean(true)),
//      Term.Literal(PrimitiveValue.Integer(42)),
//      divergentBranch
//    )
//
//    assertEquals(evaluateInteger(conditional), PrimitiveValue.Integer(42))
//  }
//
//  test("global responses resolve through complete module identifiers") {
//    val identifier = Namespace("Library").identifier("answer")
//    val translated = expectSuccess(Elaborator.infer(
//      Term.Global(identifier),
//      Map(identifier -> Type.Integer)
//    ))
//    val environment = GlobalEnvironment(Map(
//      identifier -> FiTrie.termination(PrimitiveValue.Integer(42))
//    ))
//
//    assertEquals(
//      Evaluation.observeTermination(translated.trie, PrimitiveType.Integer, environment),
//      Result.Ok(PrimitiveValue.Integer(42))
//    )
//  }
//
//  test("a demanded missing global remains a structured evaluation error") {
//    val missing = Namespace("Library").identifier("missing")
//
//    assertEquals(
//      Evaluation.observeTermination(
//        FiTrie.response(ResponseComputation.Global(missing)),
//        PrimitiveType.Integer
//      ),
//      Result.Err(EvaluationError.UnknownGlobal(missing))
//    )
//  }
//
//  test("arrow coercion rebuilds a handler for contravariant arguments") {
//    val sourceType = Type.Arrow(Type.Top, Type.Integer)
//    val targetType = Type.Arrow(Type.Integer, Type.Integer)
//    val sourceFunction = Term.Annotation(
//      Term.Lambda(Term.Literal(PrimitiveValue.Integer(1))),
//      sourceType
//    )
//    val coercedFunction = Term.Annotation(sourceFunction, targetType)
//    val application = Term.Application(
//      coercedFunction,
//      Term.Literal(PrimitiveValue.Integer(42))
//    )
//
//    assertEquals(evaluateInteger(application), PrimitiveValue.Integer(1))
//  }
//
//  test("same-label record selection recursively selects the marked field response") {
//    val identityType = Type.Arrow(Type.Integer, Type.Integer)
//    val integerRecordType = Type.Record("value", Type.Integer)
//    val functionRecordType = Type.Record("value", identityType)
//    val mergedRecord = Term.Merge(
//      Term.Record("value", Term.Literal(PrimitiveValue.Integer(1))),
//      Term.Record(
//        "value",
//        Term.Annotation(Term.Lambda(Term.Variable(0)), identityType)
//      )
//    )
//    val projection = Term.Annotation(
//      Term.Projection(Term.Annotation(mergedRecord, integerRecordType), "value"),
//      Type.Integer
//    )
//    val functionProjection = Term.Annotation(
//      Term.Projection(Term.Annotation(mergedRecord, functionRecordType), "value"),
//      identityType
//    )
//    val application = Term.Application(
//      functionProjection,
//      Term.Literal(PrimitiveValue.Integer(9))
//    )
//
//    assertEquals(evaluateInteger(projection), PrimitiveValue.Integer(1))
//    assertEquals(evaluateInteger(application), PrimitiveValue.Integer(9))
//  }
//
//  test("same-domain arrow selection recursively selects the marked result response") {
//    val integerFunctionType = Type.Arrow(Type.Integer, Type.Integer)
//    val higherOrderResultType = Type.Arrow(Type.Integer, integerFunctionType)
//    val integerFunction = Term.Annotation(
//      Term.Lambda(Term.Literal(PrimitiveValue.Integer(1))),
//      integerFunctionType
//    )
//    val functionReturningFunction = Term.Annotation(
//      Term.Lambda(Term.Lambda(Term.Variable(0))),
//      higherOrderResultType
//    )
//    val selectedFunction = Term.Annotation(
//      Term.Merge(integerFunction, functionReturningFunction),
//      integerFunctionType
//    )
//    val application = Term.Application(
//      selectedFunction,
//      Term.Literal(PrimitiveValue.Integer(0))
//    )
//
//    assertEquals(evaluateInteger(application), PrimitiveValue.Integer(1))
//  }
//
//  test("same-bound universal selection recursively selects the marked body response") {
//    val integerUniversalType = Type.ForAll(Type.Top, Type.Integer)
//    val booleanUniversalType = Type.ForAll(Type.Top, Type.Boolean)
//    val integerUniversal = Term.Annotation(
//      Term.TypeLambda(Type.Top, Term.Literal(PrimitiveValue.Integer(1))),
//      integerUniversalType
//    )
//    val booleanUniversal = Term.Annotation(
//      Term.TypeLambda(Type.Top, Term.Literal(PrimitiveValue.Boolean(true))),
//      booleanUniversalType
//    )
//    val selectedUniversal = Term.Annotation(
//      Term.Merge(integerUniversal, booleanUniversal),
//      integerUniversalType
//    )
//    val application = Term.TypeApplication(selectedUniversal, Type.Unit)
//
//    assertEquals(evaluateInteger(application), PrimitiveValue.Integer(1))
//  }
//
//  test("positive target splitting broadcasts one source trie through canonical merge") {
//    val targetType = Type.Intersection(Type.Integer, Type.Top)
//    val split = Term.Annotation(Term.Literal(PrimitiveValue.Integer(42)), targetType)
//    val translated = expectSuccess(Elaborator.infer(split))
//
//    assertEquals(
//      Evaluation.observeTermination(translated.trie, PrimitiveType.Integer),
//      Result.Ok(PrimitiveValue.Integer(42))
//    )
//  }
//
//  test("recursive functions resolve structural references without a target fix constructor") {
//    val factorialType = Type.Arrow(Type.Integer, Type.Integer)
//    val factorial = Term.Fix(
//      factorialType,
//      Term.Lambda(Term.If(
//        Term.Binary(
//          BinaryOperator.Equal,
//          Term.Variable(0),
//          Term.Literal(PrimitiveValue.Integer(0))
//        ),
//        Term.Literal(PrimitiveValue.Integer(1)),
//        Term.Binary(
//          BinaryOperator.Multiply,
//          Term.Variable(0),
//          Term.Application(
//            Term.Variable(1),
//            Term.Binary(
//              BinaryOperator.Subtract,
//              Term.Variable(0),
//              Term.Literal(PrimitiveValue.Integer(1))
//            )
//          )
//        )
//      ))
//    )
//    val application = Term.Application(
//      factorial,
//      Term.Literal(PrimitiveValue.Integer(5))
//    )
//
//    assertEquals(evaluateInteger(application), PrimitiveValue.Integer(120))
//  }
//
//  test("root-distinct recursive record components retain mutual structural references") {
//    val predicateType = Type.Arrow(Type.Integer, Type.Boolean)
//    val recursiveType = Type.Intersection(
//      Type.Record("even", predicateType),
//      Type.Record("odd", predicateType)
//    )
//
//    def recursiveCall(field: String): Term = {
//      Term.Application(
//        Term.Annotation(Term.Projection(Term.Variable(1), field), predicateType),
//        Term.Binary(
//          BinaryOperator.Subtract,
//          Term.Variable(0),
//          Term.Literal(PrimitiveValue.Integer(1))
//        )
//      )
//    }
//
//    def predicate(baseResult: Boolean, recursiveField: String): Term = {
//      Term.Annotation(
//        Term.Lambda(Term.If(
//          Term.Binary(
//            BinaryOperator.Equal,
//            Term.Variable(0),
//            Term.Literal(PrimitiveValue.Integer(0))
//          ),
//          Term.Literal(PrimitiveValue.Boolean(baseResult)),
//          recursiveCall(recursiveField)
//        )),
//        predicateType
//      )
//    }
//
//    val mutuallyRecursive = Term.Fix(
//      recursiveType,
//      Term.Merge(
//        Term.Record("even", predicate(true, "odd")),
//        Term.Record("odd", predicate(false, "even"))
//      )
//    )
//    val even = Term.Annotation(
//      Term.Projection(mutuallyRecursive, "even"),
//      predicateType
//    )
//    val application = Term.Application(
//      even,
//      Term.Literal(PrimitiveValue.Integer(10))
//    )
//    val translated = expectSuccess(Elaborator.infer(application))
//
//    assertEquals(
//      Evaluation.observeTermination(translated.trie, PrimitiveType.Boolean),
//      Result.Ok(PrimitiveValue.Boolean(true))
//    )
//  }
//
//  test("evaluation rejects an open target before attempting response reduction") {
//    val open = FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(0)))
//
//    assertEquals(
//      Evaluation.observeTermination(open, PrimitiveType.Integer),
//      Result.Err(EvaluationError.IllScopedTrie(open))
//    )
//  }
//
//  test("evaluation identifies an ill-scoped global definition") {
//    val identifier = Namespace("Library").identifier("invalid")
//    val open = FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(0)))
//    val environment = GlobalEnvironment(Map(identifier -> open))
//
//    assertEquals(
//      Evaluation.observeTermination(
//        FiTrie.termination(PrimitiveValue.Integer(1)),
//        PrimitiveType.Integer,
//        environment
//      ),
//      Result.Err(EvaluationError.IllScopedGlobal(identifier, open))
//    )
//  }
//
//  private def evaluateInteger(term: Term): PrimitiveValue = {
//    val translated = expectSuccess(Elaborator.infer(term))
//    Evaluation.observeTermination(translated.trie, PrimitiveType.Integer) match {
//      case Result.Ok(value) => value
//      case Result.Err(error) => fail(s"unexpected evaluation error: $error")
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
