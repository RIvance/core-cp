package cp.visualizer

import cp.fiobs.Type
import cp.fiobs.runtime.{EvaluationError, FullyEvaluatedValue}
import cp.language.evaluation.CpEvaluationError
import cp.primitive.{PrimitiveOperationError, PrimitiveValue}
import cp.util.PrettyDocument

import scala.annotation.tailrec

private[visualizer] enum FiobsEvaluationPresentation {
  case Success(value: String)
  case Failure(message: String)
}

/** Human-readable presentation of direct Fiobs evaluation at the browser boundary. */
private[visualizer] object FiobsEvaluationPresentation {
  private val indentationWidth = 2

  def success(value: FullyEvaluatedValue): FiobsEvaluationPresentation = {
    Success(PrettyDocument.render(valueDocument(value), 72))
  }

  def failure(error: CpEvaluationError): FiobsEvaluationPresentation = {
    Failure(error match {
      case CpEvaluationError.ModuleNotCompiled(namespace) =>
        s"Module ${namespace.render} was not compiled."
      case CpEvaluationError.EntryPointNotFound(identifier) =>
        s"Entry point ${identifier.render} was not found."
      case CpEvaluationError.Fiobs(evaluationError) => renderEvaluationError(evaluationError)
    })
  }

  private def valueDocument(value: FullyEvaluatedValue): PrettyDocument = value match {
    case FullyEvaluatedValue.Primitive(primitive) => PrettyDocument.text(renderPrimitiveValue(primitive))
    case FullyEvaluatedValue.Top => PrettyDocument.text("top")
    case FullyEvaluatedValue.Fold(recursiveType, body) => PrettyDocument.concatenate(
        PrettyDocument.text(s"fold[${recursiveType.render(64)}] ("),
        valueDocument(body),
        PrettyDocument.text(")")
      )
    case FullyEvaluatedValue.Lambda(parameterType, _, resultType) => PrettyDocument.concatenate(
        PrettyDocument.text("⟨function : "),
        PrettyDocument.text(Type.Arrow(parameterType, resultType).render(64)),
        PrettyDocument.text("⟩")
      )
    case merge: FullyEvaluatedValue.Merge =>
      coalescedRecordDocument(merge).getOrElse(mergedValueDocument(flattenMerge(merge)))
    case FullyEvaluatedValue.TypeLambda(disjointBound, _, resultType) => PrettyDocument.concatenate(
        PrettyDocument.text("⟨type function : "),
        PrettyDocument.text(Type.ForAll(disjointBound, resultType).render(64)),
        PrettyDocument.text("⟩")
      )
    case FullyEvaluatedValue.Record(label, field, _) => recordDocument(label, valueDocument(field))
  }

  private def coalescedRecordDocument(value: FullyEvaluatedValue): Option[PrettyDocument] = {
    val components = flattenMerge(value)
    components match {
      case FullyEvaluatedValue.Record(label, field, _) :: remaining =>
        val fields = remaining.foldLeft(Option(List(field))) {
          case (Some(accumulated), FullyEvaluatedValue.Record(nextLabel, nextField, _))
              if nextLabel == label => Some(nextField :: accumulated)
          case _ => None
        }
        fields.map(values => recordDocument(label, mergedValueDocument(values.reverse)))
      case _ => None
    }
  }

  private def flattenMerge(value: FullyEvaluatedValue): List[FullyEvaluatedValue] = {
    @tailrec
    def visit(
      pending: List[FullyEvaluatedValue],
      reversedComponents: List[FullyEvaluatedValue]
    ): List[FullyEvaluatedValue] = pending match {
      case FullyEvaluatedValue.Merge(left, right) :: remaining =>
        visit(left :: right :: remaining, reversedComponents)
      case component :: remaining => visit(remaining, component :: reversedComponents)
      case Nil => reversedComponents.reverse
    }

    visit(List(value), Nil)
  }

  private def mergedValueDocument(values: List[FullyEvaluatedValue]): PrettyDocument = {
    mergeDocuments(values.map(valueDocument))
  }

  private def mergeDocuments(values: List[PrettyDocument]): PrettyDocument = values match {
    case Nil => throw new IllegalArgumentException("cannot render an empty Fiobs merge")
    case head :: tail => PrettyDocument.group(tail.foldLeft(head) { (document, next) =>
        PrettyDocument.concatenate(
          document,
          PrettyDocument.text(" ,,"),
          PrettyDocument.indent(
            indentationWidth,
            PrettyDocument.concatenate(PrettyDocument.line, next)
          )
        )
      })
  }

  private def recordDocument(label: String, field: PrettyDocument): PrettyDocument = {
    PrettyDocument.group(PrettyDocument.concatenate(
      PrettyDocument.text(s"{ $label ="),
      PrettyDocument.indent(
        indentationWidth,
        PrettyDocument.concatenate(PrettyDocument.line, field)
      ),
      PrettyDocument.line,
      PrettyDocument.text("}")
    ))
  }

  private def renderEvaluationError(error: EvaluationError): String = error match {
    case EvaluationError.Stuck(_) =>
      "Fiobs evaluation became stuck before producing a value."
    case EvaluationError.UnknownGlobal(identifier) =>
      s"Fiobs evaluation could not resolve global ${identifier.render}."
    case EvaluationError.PrimitiveFailure(primitiveError) =>
      renderPrimitiveOperationError(primitiveError)
    case EvaluationError.InvalidNormalForm(_) =>
      "Fiobs evaluation reached an invalid normal form."
  }

  private def renderPrimitiveOperationError(error: PrimitiveOperationError): String = error match {
    case PrimitiveOperationError.DivisionByZero(operator) =>
      s"Operator '${operator.symbol}' attempted division by zero."
    case PrimitiveOperationError.InvalidOperands(operator, left, right) =>
      s"Operator '${operator.symbol}' cannot evaluate ${renderPrimitiveValue(left)} and " +
        s"${renderPrimitiveValue(right)}."
  }

  private def renderPrimitiveValue(value: PrimitiveValue): String = value match {
    case PrimitiveValue.Integer(number) => number.toString
    case PrimitiveValue.Decimal(number) => number.toString
    case PrimitiveValue.Boolean(boolean) => boolean.toString
    case PrimitiveValue.Text(text) => quotedText(text)
    case PrimitiveValue.UnitValue => "()"
  }

  private def quotedText(value: String): String = {
    val result = new StringBuilder("\"")
    value.foreach {
      case '"' => result.append("\\\"")
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
    result.append('"').result()
  }
}
