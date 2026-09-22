package cp.fitrie

import cp.primitive.{BinaryOperator, PrimitiveType, PrimitiveValue}
import cp.util.PrettyDocument

/** Deterministic pretty-printing for the concrete FiTrie syntax. */
private[fitrie] object FiTrieRendering {
  private val maximumLineWidth = 120
  private val indentationWidth = 2

  def render(trie: FiTrie): String = {
    PrettyDocument.render(trieDocument(trie), maximumLineWidth)
  }

  private def trieDocument(trie: FiTrie): PrettyDocument = {
    val responseDocuments = trie.responseComputations.toList
      .sortBy(responseSortKey)
      .map(responseDocument)
    val routeDocuments = trie.routeContinuations.toList
      .sortBy { case (routeKey, _) => routeSortKey(routeKey) }
      .map { case (routeKey, continuation) => routeDocument(routeKey, continuation) }
    val terminationDocuments = trie.terminationPayloads.entries.toList
      .sortBy { case (primitiveType, _) => primitiveType.ordinal }
      .map { case (primitiveType, payload) =>
        PrettyDocument.concatenate(
          primitiveTypeDocument(primitiveType),
          PrettyDocument.text(" ↦ "),
          primitiveValueDocument(payload)
        )
      }
    val components = List(
      componentDocument(responseDocuments),
      componentDocument(routeDocuments),
      componentDocument(terminationDocuments)
    )

    PrettyDocument.group(PrettyDocument.concatenate(
      PrettyDocument.text("{"),
      PrettyDocument.indent(
        indentationWidth,
        PrettyDocument.concatenate(
          PrettyDocument.line,
          PrettyDocument.join(
            components,
            PrettyDocument.concatenate(PrettyDocument.text(" ;"), PrettyDocument.line)
          )
        )
      ),
      PrettyDocument.line,
      PrettyDocument.text("}")
    ))
  }

  private def componentDocument(entries: List[PrettyDocument]): PrettyDocument = entries match {
    case Nil => PrettyDocument.text("·")
    case _ => PrettyDocument.group(PrettyDocument.join(
      entries,
      PrettyDocument.concatenate(PrettyDocument.text(","), PrettyDocument.line)
    ))
  }

  private def routeDocument(routeKey: RouteKey, continuation: FiTrie): PrettyDocument = {
    PrettyDocument.group(PrettyDocument.concatenate(
      routeKeyDocument(routeKey, showApplicationBinder = true),
      PrettyDocument.text(" ↦"),
      PrettyDocument.indent(
        indentationWidth,
        PrettyDocument.concatenate(PrettyDocument.line, trieDocument(continuation))
      )
    ))
  }

  private def responseDocument(responseComputation: ResponseComputation): PrettyDocument = {
    responseComputation match {
      case ResponseComputation.LocalVariable(index) =>
        PrettyDocument.text(s"x${subscript(index.value)}")
      case ResponseComputation.Global(identifier) =>
        PrettyDocument.text(s"global ${identifier.render}")
      case ResponseComputation.StructuralReference(index) =>
        PrettyDocument.text(s"ref ${index.value}")
      case ResponseComputation.Index(receiver, requests) =>
        PrettyDocument.group(PrettyDocument.concatenate(
          trieDocument(receiver),
          PrettyDocument.text(" ◁"),
          PrettyDocument.indent(
            indentationWidth,
            PrettyDocument.concatenate(PrettyDocument.line, requestSetDocument(requests))
          )
        ))
      case ResponseComputation.Filter(receiver, selectedRootKeys) =>
        PrettyDocument.group(PrettyDocument.concatenate(
          trieDocument(receiver),
          PrettyDocument.text(" ▷"),
          PrettyDocument.indent(
            indentationWidth,
            PrettyDocument.concatenate(PrettyDocument.line, rootKeyExpressionDocument(selectedRootKeys))
          )
        ))
      case ResponseComputation.PrimitiveOperation(operator, left, right) =>
        PrettyDocument.group(PrettyDocument.concatenate(
          PrettyDocument.text("("),
          PrettyDocument.indent(
            indentationWidth,
            PrettyDocument.concatenate(
              trieDocument(left),
              PrettyDocument.line,
              PrettyDocument.text(operatorSymbol(operator)),
              PrettyDocument.line,
              trieDocument(right)
            )
          ),
          PrettyDocument.text(")")
        ))
      case ResponseComputation.Conditional(condition, whenTrue, whenFalse) =>
        PrettyDocument.group(PrettyDocument.concatenate(
          PrettyDocument.text("if "),
          trieDocument(condition),
          PrettyDocument.line,
          PrettyDocument.text("then "),
          trieDocument(whenTrue),
          PrettyDocument.line,
          PrettyDocument.text("else "),
          trieDocument(whenFalse)
        ))
    }
  }

  private def requestSetDocument(requestSet: RequestSet): PrettyDocument = {
    val requestDocuments = requestSet.requests.toList
      .sortBy(requestSortKey)
      .map(requestDocument)
    angleSetDocument(requestDocuments)
  }

  private def requestDocument(request: Request): PrettyDocument = request match {
    case Request.Application(argument) => PrettyDocument.concatenate(
      PrettyDocument.text("ωᵃᵖᵖ["),
      trieDocument(argument),
      PrettyDocument.text("]")
    )
    case Request.TypeApplication(pathInterface) => PrettyDocument.concatenate(
      PrettyDocument.text("ωᵗᵃᵖᵖ["),
      PrettyDocument.text(pathInterface.toString),
      PrettyDocument.text("]")
    )
    case Request.Projection(label) => PrettyDocument.text(s"ωᵖʳᵒʲ_${label.value}")
    case Request.Unfold => PrettyDocument.text("ωᵘⁿᶠᵒˡᵈ")
  }

  private def rootKeyExpressionDocument(expression: RootKeyExpression): PrettyDocument = {
    expression match {
      case RootKeyExpression.Concrete(rootKeys) => rootKeySetDocument(rootKeys)
      case RootKeyExpression.Front(pathInterface) => PrettyDocument.text(s"($pathInterface)•")
      case RootKeyExpression.Union(left, right) => PrettyDocument.concatenate(
        PrettyDocument.text("("),
        rootKeyExpressionDocument(left),
        PrettyDocument.text(" ∪ "),
        rootKeyExpressionDocument(right),
        PrettyDocument.text(")")
      )
      case RootKeyExpression.Difference(left, right) => PrettyDocument.concatenate(
        PrettyDocument.text("("),
        rootKeyExpressionDocument(left),
        PrettyDocument.text(" ∖ "),
        rootKeyExpressionDocument(right),
        PrettyDocument.text(")")
      )
    }
  }

  private def rootKeySetDocument(rootKeySet: RootKeySet): PrettyDocument = rootKeySet match {
    case RootKeySet.Universal => PrettyDocument.text("𝕂")
    case RootKeySet.Finite(rootKeys) if rootKeys.isEmpty => PrettyDocument.text("∅")
    case RootKeySet.Finite(rootKeys) =>
      val rootKeyDocuments = rootKeys.toList.sortBy(rootKeySortKey).map(rootKeyDocument)
      angleSetDocument(rootKeyDocuments)
    case RootKeySet.Cofinite(excludedRootKeys) => PrettyDocument.concatenate(
      PrettyDocument.text("𝕂 ∖ "),
      angleSetDocument(excludedRootKeys.toList.sortBy(rootKeySortKey).map(rootKeyDocument))
    )
  }

  private def angleSetDocument(entries: List[PrettyDocument]): PrettyDocument = {
    PrettyDocument.group(PrettyDocument.concatenate(
      PrettyDocument.text("⟨"),
      PrettyDocument.indent(
        indentationWidth,
        PrettyDocument.join(
          entries,
          PrettyDocument.concatenate(PrettyDocument.text(","), PrettyDocument.line)
        )
      ),
      PrettyDocument.text("⟩")
    ))
  }

  private def rootKeyDocument(rootKey: RootKey): PrettyDocument = rootKey match {
    case RootKey.Route(routeKey) => routeKeyDocument(routeKey, showApplicationBinder = false)
    case RootKey.Termination(primitiveType) => primitiveTypeDocument(primitiveType)
  }

  private def routeKeyDocument(
    routeKey: RouteKey,
    showApplicationBinder: Boolean
  ): PrettyDocument = routeKey match {
    case RouteKey.Application if showApplicationBinder => PrettyDocument.text("κᵃᵖᵖₓ₀")
    case RouteKey.Application => PrettyDocument.text("κᵃᵖᵖ")
    case RouteKey.TypeApplication if showApplicationBinder => PrettyDocument.text("κᵗᵃᵖᵖᵅ₀")
    case RouteKey.TypeApplication => PrettyDocument.text("κᵗᵃᵖᵖ")
    case RouteKey.Projection(label) => PrettyDocument.text(s"κᵖʳᵒʲ_${label.value}")
    case RouteKey.Unfold => PrettyDocument.text("κᵘⁿᶠᵒˡᵈ")
  }

  private def primitiveTypeDocument(primitiveType: PrimitiveType): PrettyDocument = {
    val syntax = primitiveType match {
      case PrimitiveType.Integer => "int"
      case PrimitiveType.Decimal => "decimal"
      case PrimitiveType.Boolean => "bool"
      case PrimitiveType.Text => "string"
      case PrimitiveType.Unit => "unit"
    }
    PrettyDocument.text(syntax)
  }

  private def primitiveValueDocument(primitiveValue: PrimitiveValue): PrettyDocument = {
    val syntax = primitiveValue match {
      case PrimitiveValue.Integer(value) => value.toString
      case PrimitiveValue.Decimal(value) => value.toString
      case PrimitiveValue.Boolean(value) => value.toString
      case PrimitiveValue.Text(value) => quotedText(value)
      case PrimitiveValue.UnitValue => "()"
    }
    PrettyDocument.text(syntax)
  }

  private def operatorSymbol(operator: BinaryOperator): String = operator match {
    case BinaryOperator.LessThanOrEqual => "≤"
    case BinaryOperator.GreaterThanOrEqual => "≥"
    case BinaryOperator.Equal => "="
    case BinaryOperator.NotEqual => "≠"
    case BinaryOperator.And => "∧"
    case BinaryOperator.Or => "∨"
    case _ => operator.symbol
  }

  private def subscript(value: Int): String = {
    value.toString.map {
      case '0' => '₀'
      case '1' => '₁'
      case '2' => '₂'
      case '3' => '₃'
      case '4' => '₄'
      case '5' => '₅'
      case '6' => '₆'
      case '7' => '₇'
      case '8' => '₈'
      case '9' => '₉'
    }.mkString
  }

  private def quotedText(value: String): String = {
    val result = new StringBuilder("\"")
    value.foreach {
      case '\"' => result.append("\\\"")
      case '\\' => result.append("\\\\")
      case '\b' => result.append("\\b")
      case '\f' => result.append("\\f")
      case '\n' => result.append("\\n")
      case '\r' => result.append("\\r")
      case '\t' => result.append("\\t")
      case character if Character.isISOControl(character) =>
        result.append(f"\\u${character.toInt}%04x")
      case character => result.append(character)
    }
    result.append('\"').result()
  }

  private def responseSortKey(responseComputation: ResponseComputation): (Int, Int, String) = {
    responseComputation match {
      case ResponseComputation.LocalVariable(index) => (0, index.value, "")
      case ResponseComputation.Global(identifier) => (1, 0, identifier.render)
      case ResponseComputation.StructuralReference(index) => (2, index.value, "")
      case ResponseComputation.Index(_, _) => (3, 0, compactResponse(responseComputation))
      case ResponseComputation.Filter(_, _) => (4, 0, compactResponse(responseComputation))
      case ResponseComputation.PrimitiveOperation(_, _, _) => (5, 0, compactResponse(responseComputation))
      case ResponseComputation.Conditional(_, _, _) => (6, 0, compactResponse(responseComputation))
    }
  }

  private def requestSortKey(request: Request): (Int, String) = request match {
    case Request.Application(argument) =>
      (0, PrettyDocument.render(trieDocument(argument), Int.MaxValue))
    case Request.TypeApplication(pathInterface) => (1, pathInterface.toString)
    case Request.Unfold => (3, "")
    case Request.Projection(label) => (2, label.value)
  }

  private def rootKeySortKey(rootKey: RootKey): (Int, Int, String) = rootKey match {
    case RootKey.Route(routeKey) =>
      val (routeRank, label) = routeSortKey(routeKey)
      (0, routeRank, label)
    case RootKey.Termination(primitiveType) => (1, primitiveType.ordinal, "")
  }

  private def routeSortKey(routeKey: RouteKey): (Int, String) = routeKey match {
    case RouteKey.Application => (0, "")
    case RouteKey.TypeApplication => (1, "")
    case RouteKey.Unfold => (3, "")
    case RouteKey.Projection(label) => (2, label.value)
  }

  private def compactResponse(responseComputation: ResponseComputation): String = {
    PrettyDocument.render(responseDocument(responseComputation), Int.MaxValue)
  }
}
