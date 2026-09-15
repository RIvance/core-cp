package cp.fiobs

import cp.primitive.{BinaryOperator, PrimitiveType, PrimitiveValue}
import cp.util.PrettyDocument

/** Deterministic, scope-aware rendering of elaborated Fiobs source terms. */
private[fiobs] object FiobsRendering {
  private val indentationWidth = 2

  def render(term: Term, maximumLineWidth: Int): String = {
    PrettyDocument.render(termDocument(term, RenderingScope.empty, Precedence.Minimum), maximumLineWidth)
  }

  def render(inputType: Type, maximumLineWidth: Int): String = {
    PrettyDocument.render(typeDocument(inputType, RenderingScope.empty, Precedence.Minimum), maximumLineWidth)
  }

  private def termDocument(
    term: Term,
    scope: RenderingScope,
    enclosingPrecedence: Int
  ): PrettyDocument = {
    val (precedence, document) = term match {
      case Term.Variable(index) =>
        Precedence.Atomic -> PrettyDocument.text(scope.termVariable(index))
      case Term.Global(identifier) =>
        Precedence.Atomic -> PrettyDocument.text(s"global ${identifier.render}")
      case Term.Literal(value) =>
        Precedence.Atomic -> PrettyDocument.text(primitiveValueSyntax(value))
      case Term.Top => Precedence.Atomic -> PrettyDocument.text("top")
      case Term.Lambda(body) =>
        val (binder, bodyScope) = scope.bindTermVariable
        Precedence.Binding -> bindingDocument(s"λ$binder.", termDocument(body, bodyScope, Precedence.Minimum))
      case Term.Fix(annotatedType, body) =>
        val (binder, bodyScope) = scope.bindTermVariable
        val header = PrettyDocument.concatenate(
          PrettyDocument.text(s"fix ($binder : "),
          typeDocument(annotatedType, scope, Precedence.Minimum),
          PrettyDocument.text(").")
        )
        Precedence.Binding -> bindingDocument(header, termDocument(body, bodyScope, Precedence.Minimum))
      case Term.Application(function, argument) =>
        Precedence.Application -> PrettyDocument.group(PrettyDocument.concatenate(
          termDocument(function, scope, Precedence.Application),
          PrettyDocument.indent(
            indentationWidth,
            PrettyDocument.concatenate(
              PrettyDocument.line,
              termDocument(argument, scope, Precedence.Application + 1)
            )
          )
        ))
      case Term.Merge(left, right) =>
        Precedence.Merge -> infixDocument(
          termDocument(left, scope, Precedence.Merge),
          ",,",
          termDocument(right, scope, Precedence.Merge + 1)
        )
      case Term.Annotation(inner, annotatedType) =>
        Precedence.Atomic -> PrettyDocument.group(PrettyDocument.concatenate(
          PrettyDocument.text("("),
          PrettyDocument.indent(
            indentationWidth,
            PrettyDocument.concatenate(
              termDocument(inner, scope, Precedence.Minimum),
              PrettyDocument.text(" :"),
              PrettyDocument.line,
              typeDocument(annotatedType, scope, Precedence.Minimum)
            )
          ),
          PrettyDocument.text(")")
        ))
      case Term.TypeLambda(disjointBound, body) =>
        val (binder, bodyScope) = scope.bindTypeVariable
        val header = PrettyDocument.concatenate(
          PrettyDocument.text(s"Λ($binder * "),
          typeDocument(disjointBound, scope, Precedence.Minimum),
          PrettyDocument.text(").")
        )
        Precedence.Binding -> bindingDocument(header, termDocument(body, bodyScope, Precedence.Minimum))
      case Term.TypeApplication(function, argumentType) =>
        Precedence.Postfix -> PrettyDocument.group(PrettyDocument.concatenate(
          termDocument(function, scope, Precedence.Postfix),
          PrettyDocument.text("["),
          typeDocument(argumentType, scope, Precedence.Minimum),
          PrettyDocument.text("]")
        ))
      case Term.Record(label, field) =>
        Precedence.Atomic -> PrettyDocument.group(PrettyDocument.concatenate(
          PrettyDocument.text(s"{$label ="),
          PrettyDocument.indent(
            indentationWidth,
            PrettyDocument.concatenate(
              PrettyDocument.line,
              termDocument(field, scope, Precedence.Minimum)
            )
          ),
          PrettyDocument.text("}")
        ))
      case Term.Projection(record, label) =>
        Precedence.Postfix -> PrettyDocument.concatenate(
          termDocument(record, scope, Precedence.Postfix),
          PrettyDocument.text(s".$label")
        )
      case Term.Binary(operator, left, right) =>
        Precedence.Binary -> infixDocument(
          termDocument(left, scope, Precedence.Binary),
          operatorSyntax(operator),
          termDocument(right, scope, Precedence.Binary + 1)
        )
      case Term.If(condition, whenTrue, whenFalse) =>
        Precedence.Binding -> PrettyDocument.group(PrettyDocument.concatenate(
          PrettyDocument.text("if "),
          termDocument(condition, scope, Precedence.Minimum),
          PrettyDocument.line,
          PrettyDocument.text("then "),
          termDocument(whenTrue, scope, Precedence.Minimum),
          PrettyDocument.line,
          PrettyDocument.text("else "),
          termDocument(whenFalse, scope, Precedence.Minimum)
        ))
    }
    parenthesize(precedence < enclosingPrecedence, document)
  }

  private def typeDocument(
    inputType: Type,
    scope: RenderingScope,
    enclosingPrecedence: Int
  ): PrettyDocument = {
    val (precedence, document) = inputType match {
      case Type.Primitive(kind) =>
        Precedence.Atomic -> PrettyDocument.text(primitiveTypeSyntax(kind))
      case Type.Top => Precedence.Atomic -> PrettyDocument.text("⊤")
      case Type.Bottom => Precedence.Atomic -> PrettyDocument.text("⊥")
      case Type.Variable(index) =>
        Precedence.Atomic -> PrettyDocument.text(scope.typeVariable(index))
      case Type.Arrow(from, to) =>
        Precedence.TypeArrow -> infixDocument(
          typeDocument(from, scope, Precedence.TypeArrow + 1),
          "→",
          typeDocument(to, scope, Precedence.TypeArrow)
        )
      case Type.Intersection(left, right) =>
        Precedence.TypeIntersection -> infixDocument(
          typeDocument(left, scope, Precedence.TypeIntersection),
          "&",
          typeDocument(right, scope, Precedence.TypeIntersection + 1)
        )
      case Type.ForAll(disjointBound, body) =>
        val (binder, bodyScope) = scope.bindTypeVariable
        val header = PrettyDocument.concatenate(
          PrettyDocument.text(s"∀($binder * "),
          typeDocument(disjointBound, scope, Precedence.Minimum),
          PrettyDocument.text(").")
        )
        Precedence.Binding -> bindingDocument(
          header,
          typeDocument(body, bodyScope, Precedence.Minimum)
        )
      case Type.Record(label, fieldType) =>
        Precedence.Atomic -> PrettyDocument.group(PrettyDocument.concatenate(
          PrettyDocument.text(s"{$label : "),
          typeDocument(fieldType, scope, Precedence.Minimum),
          PrettyDocument.text("}")
        ))
    }
    parenthesize(precedence < enclosingPrecedence, document)
  }

  private def bindingDocument(header: String, body: PrettyDocument): PrettyDocument = {
    bindingDocument(PrettyDocument.text(header), body)
  }

  private def bindingDocument(header: PrettyDocument, body: PrettyDocument): PrettyDocument = {
    PrettyDocument.group(PrettyDocument.concatenate(
      header,
      PrettyDocument.indent(
        indentationWidth,
        PrettyDocument.concatenate(PrettyDocument.line, body)
      )
    ))
  }

  private def infixDocument(
    left: PrettyDocument,
    operator: String,
    right: PrettyDocument
  ): PrettyDocument = {
    PrettyDocument.group(PrettyDocument.concatenate(
      left,
      PrettyDocument.text(s" $operator"),
      PrettyDocument.indent(
        indentationWidth,
        PrettyDocument.concatenate(PrettyDocument.line, right)
      )
    ))
  }

  private def parenthesize(required: Boolean, document: PrettyDocument): PrettyDocument = {
    if (required) {
      PrettyDocument.concatenate(
        PrettyDocument.text("("),
        document,
        PrettyDocument.text(")")
      )
    } else {
      document
    }
  }

  private def primitiveTypeSyntax(primitiveType: PrimitiveType): String = primitiveType match {
    case PrimitiveType.Integer => "Int"
    case PrimitiveType.Decimal => "Decimal"
    case PrimitiveType.Boolean => "Bool"
    case PrimitiveType.Text => "String"
    case PrimitiveType.Unit => "Unit"
  }

  private def primitiveValueSyntax(value: PrimitiveValue): String = value match {
    case PrimitiveValue.Integer(number) => number.toString
    case PrimitiveValue.Decimal(number) => number.toString
    case PrimitiveValue.Boolean(boolean) => boolean.toString
    case PrimitiveValue.Text(text) => quotedText(text)
    case PrimitiveValue.UnitValue => "()"
  }

  private def operatorSyntax(operator: BinaryOperator): String = operator match {
    case BinaryOperator.LessThanOrEqual => "≤"
    case BinaryOperator.GreaterThanOrEqual => "≥"
    case BinaryOperator.Equal => "="
    case BinaryOperator.NotEqual => "≠"
    case BinaryOperator.And => "∧"
    case BinaryOperator.Or => "∨"
    case _ => operator.symbol
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
}

private final case class RenderingScope(
  termVariables: List[String],
  typeVariables: List[String]
) {
  def termVariable(index: Int): String = termVariables.lift(index).getOrElse(s"x${subscript(index)}")

  def typeVariable(index: Int): String = typeVariables.lift(index).getOrElse(s"α${subscript(index)}")

  def bindTermVariable: (String, RenderingScope) = {
    val binder = s"x${subscript(termVariables.size)}"
    binder -> copy(termVariables = binder :: termVariables)
  }

  def bindTypeVariable: (String, RenderingScope) = {
    val binder = s"α${subscript(typeVariables.size)}"
    binder -> copy(typeVariables = binder :: typeVariables)
  }

  private def subscript(value: Int): String = {
    require(value >= 0, "a de Bruijn index cannot be negative")
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
}

private object RenderingScope {
  val empty: RenderingScope = RenderingScope(Nil, Nil)
}

private object Precedence {
  val Minimum = 0
  val Binding = 1
  val Merge = 2
  val Binary = 3
  val TypeArrow = 2
  val TypeIntersection = 3
  val Application = 4
  val Postfix = 5
  val Atomic = 6
}
