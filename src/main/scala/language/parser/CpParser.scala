package cp.language.parser

import cp.language.core.*
import cp.naming.{Identifier, NameReference, Namespace}
import cp.primitive.{BinaryOperator, PrimitiveValue}
import cp.source.SourceSpan
import cp.util.Result

import scala.util.matching.Regex
import scala.util.parsing.combinator.RegexParsers
import scala.util.parsing.input.CharSequenceReader

enum ParsingError {
  case Syntax(message: String, line: Int, column: Int)
  case SignatureUsedAsOrdinaryTypeApplication(signatureName: String)
}

/**
 * The concrete grammar normalizes directly into CP core constructors. Parser
 * combinators may return transient tuples and constructor functions, but no
 * surface-language AST exists.
 */
object CpParser extends RegexParsers {
  private enum LocalBindingKind {
    case Ordinary, Recursive
  }

  private enum ParsedLocalBinding {
    case Ordinary(name: String, declaredType: Option[Type], initializer: Expression)
    case Recursive(name: String, declaredType: Type, initializer: Expression)

    def wrap(body: Expression): Expression = this match {
      case Ordinary(name, declaredType, initializer) =>
        Expression.Let(name, declaredType, initializer, body)
      case Recursive(name, declaredType, initializer) =>
        Expression.RecursiveLet(name, declaredType, initializer, body)
    }
  }

  private final case class ParsedTermDeclarationHeader(
    name: String,
    binders: List[TypeBinder],
    parameters: List[ValueParameter],
    leadingResult: Option[Type],
    whereBounds: List[TypeBinder]
  )

  private final case class ParsedFieldMemberHeader(
    label: String,
    parameters: List[ValueParameter],
    resultType: Option[Type]
  )

  private final case class ParsedMethodPatternHeader(
    constructorName: String,
    constructorParameters: List[PatternParameter],
    selfRequirement: Option[Type],
    label: String,
    valueParameters: List[ValueParameter]
  )

  override protected val whiteSpace: Regex =
    """(?s)(?:\s+|--[^\r\n]*(?:\r?\n|\z)|//[^\r\n]*(?:\r?\n|\z)|/\*.*?\*/)+""".r

  private val reservedWords = Set(
    "and",
    "def",
    "else",
    "extends",
    "false",
    "forall",
    "from",
    "if",
    "impl",
    "import",
    "implements",
    "in",
    "inherits",
    "let",
    "module",
    "new",
    "open",
    "override",
    "rec",
    "self",
    "super",
    "then",
    "top",
    "trait",
    "true",
    "type",
    "where"
  )

  def parseModule(source: String): Result[Module, ParsingError] = {
    parseModuleWithSourceSpans(source).map(_.withoutSourceSpans)
  }

  private[language] def parseModuleWithSourceSpans(source: String): Result[Module, ParsingError] = {
    parseAll(compilationUnit, source) match {
      case Success(module, _) => validateDelimiterDiscipline(module)
      case failure: NoSuccess =>
        Result.Err(ParsingError.Syntax(
          failure.msg,
          failure.next.pos.line,
          failure.next.pos.column
        ))
    }
  }

  private def compilationUnit: Parser[Module] = {
    for {
      declaredNamespace <- opt(moduleDeclaration)
      imports <- rep(importDeclaration)
      definitions <- rep(declaration)
    } yield Module(declaredNamespace, imports, definitions)
  }

  private def moduleDeclaration: Parser[Namespace] = {
    word("module") ~> namespace <~ opt(";")
  }

  private def importDeclaration: Parser[ImportDeclaration] = {
    word("import") ~> (
      (word("module") ~> namespace <~ opt(";") ^^ ImportDeclaration.Module.apply) |
        importedMembers
    )
  }

  private def importedMembers: Parser[ImportDeclaration] = {
    rep1sep(identifier, "::") ~ opt("::" ~> "*") <~ opt(";") >> {
      case segments ~ Some(_) => success(ImportDeclaration.All(Namespace.from(segments)))
      case moduleSegments ~ None if moduleSegments.size >= 2 =>
        val identifier = Identifier(Namespace.from(moduleSegments.init), moduleSegments.last)
        success(ImportDeclaration.Member(identifier))
      case _ => failure("a member import requires a module namespace and a member name")
    }
  }

  private def namespace: Parser[Namespace] = {
    rep1sep(identifier, "::") ^^ Namespace.from
  }

  private def declaration: Parser[Declaration] = {
    typeDeclaration | implementationDeclaration | methodDeclaration | termDeclaration
  }

  private def typeDeclaration: Parser[Declaration] = {
    for {
      _ <- "type"
      name <- identifier
      sortParameters <- opt("<" ~> rep1sep(identifier, ",") <~ ">")
      requiredInterface <- opt("extends" ~> inputType)
      _ <- assignmentToken
      providedInterface <- inputType
      _ <- opt(";")
    } yield Declaration.TypeSignature(
      name,
      sortParameters.getOrElse(Nil),
      requiredInterface.getOrElse(Type.Top),
      providedInterface
    )
  }

  private def termDeclaration: Parser[Declaration] = {
    for {
      header <- termDeclarationHeader
      body <- declarationBodyExpression
      trailingResult <- opt(":" ~> inputType)
      _ <- opt(";")
      declaration <- normalizedTermDeclaration(
        header.name,
        header.binders,
        header.parameters,
        header.leadingResult,
        header.whereBounds,
        body,
        trailingResult
      )
    } yield declaration
  }

  private def termDeclarationHeader: Parser[ParsedTermDeclarationHeader] = {
    for {
      _ <- opt(word("def"))
      name <- identifier
      binderChunks <- rep(typeParameterChunk)
      parameterClauses <- rep(valueParameterClause)
      leadingResult <- opt(":" ~> inputType)
      whereBounds <- opt(whereClause)
      _ <- assignmentToken
    } yield ParsedTermDeclarationHeader(
      name,
      binderChunks.flatten,
      parameterClauses.flatten,
      leadingResult,
      whereBounds.getOrElse(Nil)
    )
  }

  private def implementationDeclaration: Parser[Declaration] = {
    for {
      _ <- "impl"
      name <- identifier
      binderChunks <- rep(typeParameterChunk)
      _ <- "from"
      selfRequirement <- inputType
      inheritedTrait <- opt("inherits" ~> expression)
      whereBounds <- opt(whereClause)
      _ <- assignmentToken
      body <- memberBlock
      _ <- opt(";")
      declaration <- normalizedImplementationDeclaration(
        name,
        binderChunks.flatten,
        whereBounds.getOrElse(Nil),
        selfRequirement,
        inheritedTrait.getOrElse(Expression.Top),
        body
      )
    } yield declaration
  }

  private def methodDeclaration: Parser[Declaration] = {
    methodPattern <~ opt(";") ^^ Declaration.Method.apply
  }

  private def typeParameterChunk: Parser[List[TypeBinder]] = {
    ("[" ~> rep1sep(typeBinder, ",") <~ "]") |
      ("(" ~> disjointClause <~ ")" ^^ (List(_))) |
      (identifier ^^ (name => List(TypeBinder(name, Type.Top))))
  }

  private def typeBinder: Parser[TypeBinder] = {
    identifier ~ opt("*" ~> inputType) ^^ {
      case name ~ bound => TypeBinder(name, bound.getOrElse(Type.Top))
    }
  }

  private def whereClause: Parser[List[TypeBinder]] = {
    "where" ~> rep1sep(disjointClause, "and" | ",")
  }

  private def disjointClause: Parser[TypeBinder] = {
    identifier ~ ("*" ~> inputType) ^^ {
      case name ~ bound => TypeBinder(name, bound)
    }
  }

  private def valueParameterClause: Parser[List[ValueParameter]] = {
    "(" ~> rep1sep(valueParameterGroup, ",") <~ ")" ^^ (_.flatten)
  }

  private def valueParameterGroup: Parser[List[ValueParameter]] = {
    rep1(identifier) ~ (":" ~> inputType) ^^ {
      case names ~ parameterType => names.map(ValueParameter(_, parameterType))
    }
  }

  private def normalizedTermDeclaration(
    name: String,
    initialBinders: List[TypeBinder],
    parameters: List[ValueParameter],
    leadingResult: Option[Type],
    whereBounds: List[TypeBinder],
    body: Expression,
    trailingResult: Option[Type]
  ): Parser[Declaration] = {
    if (leadingResult.nonEmpty && trailingResult.nonEmpty) {
      failure("a declaration may have only one result annotation")
    } else {
      normalizeBinders(initialBinders, whereBounds) match {
        case Result.Err(message) => failure(message)
        case Result.Ok(binders) =>
          val lambda = Expression.curriedLambda(parameters, body)
          val resultType = leadingResult.orElse(trailingResult)
          val annotatedLambda = resultType match {
            case Some(result) =>
              val completeType = parameters.reverse.foldLeft(result: Type) { (currentResult, parameter) =>
                Type.Arrow(parameter.parameterType, currentResult)
              }
              Expression.Annotation(lambda, completeType)
            case None => lambda
          }
          success(Declaration.Term(name, Expression.typeAbstractions(binders, annotatedLambda)))
      }
    }
  }

  private def normalizedImplementationDeclaration(
    name: String,
    initialBinders: List[TypeBinder],
    whereBounds: List[TypeBinder],
    selfRequirement: Type,
    inheritedTrait: Expression,
    body: Expression
  ): Parser[Declaration] = {
    normalizeBinders(initialBinders, whereBounds) match {
      case Result.Err(message) => failure(message)
      case Result.Ok(binders) =>
        val traitExpression = Expression.Trait(
          "self",
          selfRequirement,
          Type.Top,
          inheritedTrait,
          body
        )
        success(Declaration.Term(
          name,
          Expression.typeAbstractions(binders, traitExpression)
        ))
    }
  }

  private def normalizeBinders(
    initialBinders: List[TypeBinder],
    whereBounds: List[TypeBinder]
  ): Result[List[TypeBinder], String] = {
    val duplicateInitial = initialBinders.groupBy(_.name).collectFirst {
      case (name, occurrences) if occurrences.sizeIs > 1 => name
    }
    val duplicateWhere = whereBounds.groupBy(_.name).collectFirst {
      case (name, occurrences) if occurrences.sizeIs > 1 => name
    }

    duplicateInitial.orElse(duplicateWhere) match {
      case Some(name) => Result.Err(s"duplicate type binder: $name")
      case None =>
        val declaredNames = initialBinders.map(_.name).toSet
        whereBounds.find(bound => !declaredNames.contains(bound.name)) match {
          case Some(bound) => Result.Err(s"where clause refers to undeclared binder: ${bound.name}")
          case None =>
            val whereByName = whereBounds.map(bound => bound.name -> bound.disjointBound).toMap
            Result.Ok(initialBinders.map { binder =>
              whereByName.get(binder.name) match {
                case Some(bound) if binder.disjointBound != Type.Top => binder
                case Some(bound) => TypeBinder(binder.name, bound)
                case None => binder
              }
            })
        }
    }
  }

  private def inputType: Parser[Type] = {
    forallType | arrowType
  }

  private def forallType: Parser[Type] = {
    forallToken ~> (
      preferredTypeAbstractionBinder ~ ("->" ~> inputType) |
      rawTypeAbstractionBinder ~ ("." ~> inputType)
    ) ^^ {
      case binder ~ bodyType => Type.ForAll(binder.name, binder.disjointBound, bodyType)
    }
  }

  private def forallToken: Parser[String] = {
    word("forall") | "∀"
  }

  private def arrowType: Parser[Type] = {
    intersectionType ~ opt("->" ~> inputType) ^^ {
      case parameterType ~ Some(resultType) => Type.Arrow(parameterType, resultType)
      case completeType ~ None => completeType
    }
  }

  private def intersectionType: Parser[Type] = {
    rep1sep(atomicType, "&") ^^ { types =>
      types.tail.foldLeft(types.head: Type)(Type.Intersection(_, _))
    }
  }

  private def atomicType: Parser[Type] = {
    primitiveType |
    traitType |
    recordType |
    (word("Top") ^^^ Type.Top) |
    (word("Bottom") ^^^ Type.Bottom) |
    ("⊤" ^^^ Type.Top) |
    ("⊥" ^^^ Type.Bottom) |
    namedType |
    ("(" ~> inputType <~ ")")
  }

  private def primitiveType: Parser[Type] = {
    (word("Int") ^^^ Type.Integer) |
    (word("Float") ^^^ Type.Decimal) |
    (word("Decimal") ^^^ Type.Decimal) |
    (word("Bool") ^^^ Type.Boolean) |
    (word("String") ^^^ Type.Text) |
    (word("Text") ^^^ Type.Text) |
    (word("Unit") ^^^ Type.Unit)
  }

  private def traitType: Parser[Type] = {
    "Trait" ~> "[" ~> rep1sep(inputType, ",") <~ "]" >> {
      case providedInterface :: Nil => success(Type.Trait(Type.Top, providedInterface))
      case requiredInterface :: providedInterface :: Nil =>
        success(Type.Trait(requiredInterface, providedInterface))
      case _ => failure("Trait expects one or two type arguments")
    }
  }

  private def recordType: Parser[Type] = {
    "{" ~> rep1(recordTypeField <~ opt(";")) <~ "}" ^^ { fields =>
      Type.records(fields.head, fields.tail)
    }
  }

  private def recordTypeField: Parser[(String, Type)] = {
    identifier ~ (":" ~> inputType) ^^ {
      case label ~ fieldType => label -> fieldType
    }
  }

  private def namedType: Parser[Type] = {
    nameReference ~ opt("<" ~> rep1sep(sortArgument, ",") <~ ">") ^^ {
      case reference ~ Some(arguments) => Type.SignatureApplication(reference, arguments)
      case NameReference.Unqualified(name) ~ None => Type.Variable(name)
      case (reference @ NameReference.Qualified(_)) ~ None => Type.Named(reference)
    }
  }

  private def sortArgument: Parser[SortArgument] = {
    inputType ~ opt("%" ~> inputType) ^^ {
      case negativeType ~ Some(positiveType) => SortArgument.Dependency(negativeType, positiveType)
      case argumentType ~ None => SortArgument.TypeArgument(argumentType)
    }
  }

  private def expression: Parser[Expression] = {
    locatedExpression(expressionWithoutTopLevelAnnotation ~ opt(":" ~> inputType) ^^ {
      case body ~ Some(annotatedType) => Expression.Annotation(body, annotatedType)
      case body ~ None => body
    })
  }

  private def expressionWithoutTopLevelAnnotation: Parser[Expression] = {
    expressionForm(expression)
  }

  /**
   * A declaration's optional trailing result annotation follows the complete
   * initializer. Its rightmost subexpression must therefore leave that colon
   * for `TermDecl` instead of consuming it as a nested expression ascription.
   */
  private def declarationBodyExpression: Parser[Expression] = {
    locatedExpression(expressionForm(declarationBodyExpression))
  }

  private def expressionForm(
    trailingExpression: => Parser[Expression]
  ): Parser[Expression] = {
    ifExpression(trailingExpression) |
    letExpression(trailingExpression) |
    openExpression(trailingExpression) |
    lambdaExpression(trailingExpression) |
    typeAbstractionExpression(trailingExpression) |
    traitExpression |
    mergeExpression
  }

  private def ifExpression(
    trailingExpression: => Parser[Expression]
  ): Parser[Expression] = {
    "if" ~> expression ~ ("then" ~> expression) ~ ("else" ~> trailingExpression) ^^ {
      case condition ~ whenTrue ~ whenFalse => Expression.If(condition, whenTrue, whenFalse)
    }
  }

  private def letExpression(
    trailingExpression: => Parser[Expression]
  ): Parser[Expression] = {
    for {
      _ <- "let"
      bindingKind <- opt(word("rec")) ^^ {
        case Some(_) => LocalBindingKind.Recursive
        case None => LocalBindingKind.Ordinary
      }
      name <- identifier
      declaredType <- opt(":" ~> inputType)
      _ <- assignmentToken
      initializer <- expression
      _ <- "in"
      body <- trailingExpression
      binding <- localBinding(bindingKind, name, declaredType, initializer, body)
    } yield binding
  }

  private def localBinding(
    bindingKind: LocalBindingKind,
    name: String,
    declaredType: Option[Type],
    initializer: Expression,
    body: Expression
  ): Parser[Expression] = {
    parsedLocalBinding(bindingKind, name, declaredType, initializer).map(_.wrap(body))
  }

  private def openExpression(
    trailingExpression: => Parser[Expression]
  ): Parser[Expression] = {
    "open" ~> expression ~ ("in" ~> trailingExpression) ^^ {
      case record ~ body => Expression.Open(record, body)
    }
  }

  private def lambdaExpression(
    trailingExpression: => Parser[Expression]
  ): Parser[Expression] = {
    (
      lambdaParameters ~ ("=>" ~> trailingExpression) |
      ("λ" ~> lambdaParameters) ~ ("." ~> trailingExpression)
    ) ^^ { case parameters ~ body => Expression.curriedLambda(parameters, body) }
  }

  private def lambdaParameters: Parser[List[ValueParameter]] = {
    valueParameterClause
  }

  private def typeAbstractionExpression(
    trailingExpression: => Parser[Expression]
  ): Parser[Expression] = {
    (
      (opt("Λ") ~> preferredTypeAbstractionBinder) ~ ("=>" ~> trailingExpression) |
      ("Λ" ~> rawTypeAbstractionBinder) ~ ("." ~> trailingExpression)
    ) ^^ {
      case binder ~ body => Expression.TypeLambda(binder, body)
    }
  }

  private def preferredTypeAbstractionBinder: Parser[TypeBinder] = {
    "[" ~> word("type") ~> typeBinder <~ "]"
  }

  private def rawTypeAbstractionBinder: Parser[TypeBinder] = {
    ("(" ~> disjointClause <~ ")") |
      (identifier ^^ (name => TypeBinder(name, Type.Top)))
  }

  private def traitExpression: Parser[Expression] = {
    for {
      _ <- "trait"
      selfRequirement <- opt(selfClause)
      providedInterface <- opt("implements" ~> inputType)
      inheritedTrait <- opt("inherits" ~> expression)
      _ <- "=>"
      body <- memberBlock
    } yield Expression.Trait(
      "self",
      selfRequirement.getOrElse(Type.Top),
      providedInterface.getOrElse(Type.Top),
      inheritedTrait.getOrElse(Expression.Top),
      body
    )
  }

  private def selfClause: Parser[Type] = {
    "[" ~> "self" ~> ":" ~> inputType <~ "]"
  }

  private def mergeExpression: Parser[Expression] = {
    chainl1(forwardExpression, ",," ^^^ ((left: Expression, right: Expression) =>
      Expression.Merge(left, right)))
  }

  private def forwardExpression: Parser[Expression] = {
    chainl1(orExpression, "^" ^^^ ((traitExpression: Expression, selfArgument: Expression) =>
      Expression.Forward(traitExpression, selfArgument)))
  }

  private def orExpression: Parser[Expression] = {
    binaryLevel(
      andExpression,
      "||" ^^^ BinaryOperator.Or
    )
  }

  private def andExpression: Parser[Expression] = {
    binaryLevel(
      equalityExpression,
      "&&" ^^^ BinaryOperator.And
    )
  }

  private def equalityExpression: Parser[Expression] = {
    binaryLevel(
      comparisonExpression,
      ("==" ^^^ BinaryOperator.Equal) | ("!=" ^^^ BinaryOperator.NotEqual)
    )
  }

  private def comparisonExpression: Parser[Expression] = {
    binaryLevel(
      concatenationExpression,
      ("<=" ^^^ BinaryOperator.LessThanOrEqual) |
        (">=" ^^^ BinaryOperator.GreaterThanOrEqual) |
        ("<" ^^^ BinaryOperator.LessThan) |
        (">" ^^^ BinaryOperator.GreaterThan)
    )
  }

  private def concatenationExpression: Parser[Expression] = {
    binaryLevel(
      additiveExpression,
      "++" ^^^ BinaryOperator.Concatenate
    )
  }

  private def additiveExpression: Parser[Expression] = {
    binaryLevel(
      multiplicativeExpression,
      ("+" ^^^ BinaryOperator.Add) | ("-" ^^^ BinaryOperator.Subtract)
    )
  }

  private def multiplicativeExpression: Parser[Expression] = {
    binaryLevel(
      newExpression,
      ("*" ^^^ BinaryOperator.Multiply) |
        ("/" ^^^ BinaryOperator.Divide) |
        ("%" ^^^ BinaryOperator.Remainder)
    )
  }

  /**
   * `new` consumes a complete application spine. Consequently, the
   * established `new Constructor argument` spelling and the added
   * `new Constructor(argument)` spelling produce the same CP core tree.
   */
  private def newExpression: Parser[Expression] = {
    ("new" ~> newExpression ^^ Expression.New.apply) |
      applicationExpression
  }

  private def binaryLevel(
    operand: => Parser[Expression],
    operator: => Parser[BinaryOperator]
  ): Parser[Expression] = {
    chainl1(operand, operator ^^ { binaryOperator =>
      (left: Expression, right: Expression) => Expression.Binary(binaryOperator, left, right)
    })
  }

  private def applicationExpression: Parser[Expression] = {
    postfixExpression ~ rep(not(followingStatementHeader) ~> postfixExpression) ^^ {
      case function ~ arguments => arguments.foldLeft(function: Expression)(Expression.Application(_, _))
    }
  }

  private def postfixExpression: Parser[Expression] = {
    prefixExpression ~ rep(not(followingStatementHeader) ~> postfixSuffix) ^^ {
      case base ~ suffixes => suffixes.foldLeft(base)((expression, suffix) => suffix(expression))
    }
  }

  /**
   * An omitted terminator must not let whitespace application absorb the
   * header of the following definition. The lookahead consumes no input and
   * recognizes only complete headers ending at an assignment token.
   */
  private def followingStatementHeader: Parser[Unit] = {
    guard(
      (termDeclarationHeader ^^^ ()) |
        (fieldMemberHeader ^^^ ()) |
        (methodPatternHeader ^^^ ())
    )
  }

  private def postfixSuffix: Parser[Expression => Expression] = {
    callSuffix | typeArgumentSuffix | establishedTypeArgumentSuffix | projectionSuffix
  }

  private def callSuffix: Parser[Expression => Expression] = {
    "(" ~> rep1sep(expression, ",") <~ ")" ^^ { arguments =>
      (function: Expression) => Expression.curriedApplication(function, arguments)
    }
  }

  private def typeArgumentSuffix: Parser[Expression => Expression] = {
    "[" ~> rep1sep(inputType, ",") <~ "]" ^^ { arguments =>
      (function: Expression) => Expression.typeApplications(function, arguments)
    }
  }

  private def establishedTypeArgumentSuffix: Parser[Expression => Expression] = {
    "@" ~> inputType ^^ { argument =>
      (function: Expression) => Expression.TypeApplication(function, argument)
    }
  }

  private def projectionSuffix: Parser[Expression => Expression] = {
    "." ~> identifier ^^ { label =>
      (record: Expression) => Expression.Projection(record, label)
    }
  }

  private def prefixExpression: Parser[Expression] = {
    recordOrBlockExpression |
      unitLiteral |
      decimalLiteral |
      integerLiteral |
      booleanLiteral |
      textLiteral |
      ("top" ^^^ Expression.Top) |
      ("self" ^^^ Expression.variable("self")) |
      ("super" ^^^ Expression.variable("super")) |
      (nameReference ^^ Expression.Variable.apply) |
      ("(" ~> expression <~ ")")
  }

  private def nameReference: Parser[NameReference] = {
    identifier ~ rep("::" ~> identifier) ^^ {
      case name ~ Nil => NameReference.Unqualified(name)
      case firstSegment ~ remainingSegments =>
        val completeSegments = firstSegment :: remainingSegments
        NameReference.Qualified(Identifier(
          Namespace.from(completeSegments.init),
          completeSegments.last
        ))
    }
  }

  private def recordOrBlockExpression: Parser[Expression] = {
    memberBlock | blockExpression
  }

  private def memberBlock: Parser[Expression] = {
    "{" ~> rep(member <~ opt(";")) <~ "}" ^^ Expression.Record.apply
  }

  private def member: Parser[Member] = {
    methodPattern | fieldMember
  }

  private def fieldMember: Parser[Member] = {
    for {
      header <- fieldMemberHeader
      body <- declarationBodyExpression
    } yield {
      val lambda = Expression.curriedLambda(header.parameters, body)
      val value = header.resultType match {
        case Some(result) =>
          val completeType = header.parameters.reverse.foldLeft(result: Type) { (currentResult, parameter) =>
            Type.Arrow(parameter.parameterType, currentResult)
          }
          Expression.Annotation(lambda, completeType)
        case None => lambda
      }
      Member.Field(header.label, value)
    }
  }

  private def fieldMemberHeader: Parser[ParsedFieldMemberHeader] = {
    for {
      _ <- opt(word("override"))
      label <- identifier
      parameterClauses <- rep(valueParameterClause)
      resultType <- opt(":" ~> inputType)
      _ <- assignmentToken
    } yield ParsedFieldMemberHeader(label, parameterClauses.flatten, resultType)
  }

  private def methodPattern: Parser[Member] = {
    for {
      header <- methodPatternHeader
      body <- expression
    } yield Member.MethodPattern(
      header.constructorName,
      header.constructorParameters,
      header.selfRequirement,
      header.label,
      header.valueParameters,
      body
    )
  }

  private def methodPatternHeader: Parser[ParsedMethodPatternHeader] = {
    for {
      _ <- "("
      constructorName <- identifier
      constructorParameters <- rep(patternParameter)
      selfRequirement <- opt(selfClause)
      _ <- ")"
      _ <- "."
      label <- identifier
      valueParameterClauses <- rep(valueParameterClause)
      _ <- assignmentToken
    } yield ParsedMethodPatternHeader(
      constructorName,
      constructorParameters,
      selfRequirement,
      label,
      valueParameterClauses.flatten
    )
  }

  private def patternParameter: Parser[PatternParameter] = {
    identifier ~ opt(":" ~> inputType) ^^ {
      case name ~ declaredType => PatternParameter(name, declaredType)
    }
  }

  private def blockExpression: Parser[Expression] = {
    "{" ~> rep(blockBinding) ~ expression <~ opt(";") <~ "}" ^^ {
      case bindings ~ result => bindings.reverse.foldLeft(result) {
        case (body, binding) => binding.wrap(body)
      }
    }
  }

  private def blockBinding: Parser[ParsedLocalBinding] = {
    for {
      _ <- word("let")
      bindingColumn <- currentColumn ^^ (_ - "let".length)
      bindingKind <- opt(word("rec")) ^^ {
        case Some(_) => LocalBindingKind.Recursive
        case None => LocalBindingKind.Ordinary
      }
      name <- identifier
      declaredType <- opt(":" ~> inputType)
      _ <- assignmentToken
      initializer <- blockBindingInitializer(bindingColumn)
      _ <- opt(";")
      binding <- parsedLocalBinding(bindingKind, name, declaredType, initializer)
    } yield binding
  }

  /**
   * Without an explicit semicolon, a block binding's initializer ends before
   * the next expression that begins at or before the binding's indentation.
   * Nested delimiters and indented continuation lines remain part of the initializer.
   */
  private def blockBindingInitializer(bindingColumn: Int): Parser[Expression] = {
    new Parser[Expression] {
      override def apply(input: Input): ParseResult[Expression] = input match {
        case reader: CharSequenceReader =>
          val boundaries = BlockStatementLayout.boundaries(reader.source, reader.offset, bindingColumn)
          boundaries.iterator.flatMap { boundary =>
            val boundedSource = reader.source.subSequence(0, boundary)
            val boundedInput = new CharSequenceReader(boundedSource, reader.offset)
            parseAll(expression, boundedInput) match {
              case Success(initializer, _) if expression(reader.drop(boundary - reader.offset)).successful =>
                Some(Success(initializer, reader.drop(boundary - reader.offset)))
              case _ => None
            }
          }.nextOption().getOrElse(expression(input))
        case _ => expression(input)
      }
    }
  }

  private def currentColumn: Parser[Int] = {
    new Parser[Int] {
      override def apply(input: Input): ParseResult[Int] = Success(input.pos.column, input)
    }
  }

  private def locatedExpression(parser: => Parser[Expression]): Parser[Expression] = {
    new Parser[Expression] {
      override def apply(input: Input): ParseResult[Expression] = {
        parser(input) match {
          case Success(expression, next) => (input, next) match {
            case (start: CharSequenceReader, end: CharSequenceReader) =>
              val startOffset = handleWhiteSpace(start.source, start.offset)
              Success(Expression.Located(
                expression,
                SourceSpan(startOffset, end.offset)
              ), next)
            case _ => Success(expression, next)
          }
          case failure: NoSuccess => failure
        }
      }
    }
  }

  private def parsedLocalBinding(
    bindingKind: LocalBindingKind,
    name: String,
    declaredType: Option[Type],
    initializer: Expression
  ): Parser[ParsedLocalBinding] = bindingKind match {
    case LocalBindingKind.Ordinary =>
      success(ParsedLocalBinding.Ordinary(name, declaredType, initializer))
    case LocalBindingKind.Recursive => declaredType match {
      case Some(bindingType) => success(ParsedLocalBinding.Recursive(name, bindingType, initializer))
      case None => failure("a recursive local binding requires a type annotation")
    }
  }

  private def unitLiteral: Parser[Expression] = {
    "(" ~ ")" ^^^ Expression.Literal(PrimitiveValue.UnitValue)
  }

  private def decimalLiteral: Parser[Expression] = {
    """(?:0|[1-9][0-9]*)\.[0-9]+""".r ^^ { value =>
      Expression.Literal(PrimitiveValue.Decimal(BigDecimal(value)))
    }
  }

  private def integerLiteral: Parser[Expression] = {
    """0|[1-9][0-9]*""".r ^^ { value =>
      Expression.Literal(PrimitiveValue.Integer(BigInt(value)))
    }
  }

  private def booleanLiteral: Parser[Expression] = {
    ("true" ^^^ Expression.Literal(PrimitiveValue.Boolean(true))) |
      ("false" ^^^ Expression.Literal(PrimitiveValue.Boolean(false)))
  }

  private def textLiteral: Parser[Expression] = {
    """"(?:[^"\\]|\\.)*"""".r ^^ { quoted =>
      Expression.Literal(PrimitiveValue.Text(StringContext.processEscapes(
        quoted.substring(1, quoted.length - 1)
      )))
    }
  }

  private def identifier: Parser[String] = {
    """[A-Za-z_][A-Za-z0-9_]*""".r >> { name =>
      if (reservedWords.contains(name)) {
        failure(s"reserved word cannot be used as an identifier: $name")
      } else {
        success(name)
      }
    }
  }

  private def word(value: String): Parser[String] = {
    (java.util.regex.Pattern.quote(value) + "(?![A-Za-z0-9_])").r
  }

  private def assignmentToken: Parser[String] = "=(?!=|>)".r

  private def validateDelimiterDiscipline(module: Module): Result[Module, ParsingError] = {
    val signatureNames = module.definitions.collect {
      case Declaration.TypeSignature(name, sortParameters, _, _) if sortParameters.nonEmpty => name
    }.toSet

    findSignatureTypeApplication(module, signatureNames) match {
      case Some(signatureName) =>
        Result.Err(ParsingError.SignatureUsedAsOrdinaryTypeApplication(signatureName))
      case None => Result.Ok(module)
    }
  }

  private def findSignatureTypeApplication(
    module: Module,
    signatureNames: Set[String]
  ): Option[String] = {
    module.definitions.iterator.map {
      case Declaration.Term(_, initializer) =>
        findSignatureTypeApplication(initializer, signatureNames)
      case Declaration.Method(member) =>
        findSignatureTypeApplication(member, signatureNames)
      case Declaration.TypeSignature(_, _, _, _) => None
    }.collectFirst { case Some(name) => name }
  }

  private def findSignatureTypeApplication(
    member: Member,
    signatureNames: Set[String]
  ): Option[String] = member match {
    case Member.Field(_, value) => findSignatureTypeApplication(value, signatureNames)
    case Member.MethodPattern(_, _, _, _, _, body) =>
      findSignatureTypeApplication(body, signatureNames)
  }

  private def findSignatureTypeApplication(
    expression: Expression,
    signatureNames: Set[String]
  ): Option[String] = expression match {
    case Expression.Located(inner, _) => findSignatureTypeApplication(inner, signatureNames)
    case Expression.TypeApplication(
          Expression.Variable(NameReference.Unqualified(name)),
          _
        ) if signatureNames.contains(name) =>
      Some(name)
    case Expression.Lambda(_, body) => findSignatureTypeApplication(body, signatureNames)
    case Expression.Application(function, argument) =>
      findSignatureTypeApplication(function, signatureNames)
        .orElse(findSignatureTypeApplication(argument, signatureNames))
    case Expression.TypeLambda(_, body) => findSignatureTypeApplication(body, signatureNames)
    case Expression.TypeApplication(function, _) => findSignatureTypeApplication(function, signatureNames)
    case Expression.Merge(left, right) =>
      findSignatureTypeApplication(left, signatureNames)
        .orElse(findSignatureTypeApplication(right, signatureNames))
    case Expression.Record(members) =>
      members.iterator.map(findSignatureTypeApplication(_, signatureNames))
        .collectFirst { case Some(name) => name }
    case Expression.Projection(record, _) => findSignatureTypeApplication(record, signatureNames)
    case Expression.Annotation(inner, _) => findSignatureTypeApplication(inner, signatureNames)
    case Expression.Let(_, _, initializer, body) =>
      findSignatureTypeApplication(initializer, signatureNames)
        .orElse(findSignatureTypeApplication(body, signatureNames))
    case Expression.RecursiveLet(_, _, initializer, body) =>
      findSignatureTypeApplication(initializer, signatureNames)
        .orElse(findSignatureTypeApplication(body, signatureNames))
    case Expression.Open(record, body) =>
      findSignatureTypeApplication(record, signatureNames)
        .orElse(findSignatureTypeApplication(body, signatureNames))
    case Expression.New(traitExpression) => findSignatureTypeApplication(traitExpression, signatureNames)
    case Expression.Forward(traitExpression, selfArgument) =>
      findSignatureTypeApplication(traitExpression, signatureNames)
        .orElse(findSignatureTypeApplication(selfArgument, signatureNames))
    case Expression.Trait(_, _, _, inheritedTrait, body) =>
      findSignatureTypeApplication(inheritedTrait, signatureNames)
        .orElse(findSignatureTypeApplication(body, signatureNames))
    case Expression.Binary(_, left, right) =>
      findSignatureTypeApplication(left, signatureNames)
        .orElse(findSignatureTypeApplication(right, signatureNames))
    case Expression.If(condition, whenTrue, whenFalse) =>
      findSignatureTypeApplication(condition, signatureNames)
        .orElse(findSignatureTypeApplication(whenTrue, signatureNames))
        .orElse(findSignatureTypeApplication(whenFalse, signatureNames))
    case Expression.Literal(_) | Expression.Variable(_) | Expression.Top => None
  }
}
