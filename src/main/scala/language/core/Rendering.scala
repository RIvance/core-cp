package cp.language.core

import cp.primitive.PrimitiveType

/** Precedence-aware rendering for CP types at diagnostic and presentation boundaries. */
private[core] object CpTypeRendering {
  def render(inputType: TypeSyntax): String = renderType(inputType, Precedence.Minimum)

  private def renderType(inputType: TypeSyntax, enclosingPrecedence: Int): String = {
    val (precedence, syntax) = inputType match {
      case TypeSyntax.Primitive(kind) => Precedence.Atomic -> primitiveTypeSyntax(kind)
      case TypeSyntax.Reference(reference) => Precedence.Atomic -> reference.render
      case TypeSyntax.Top => Precedence.Atomic -> "Top"
      case TypeSyntax.Bottom => Precedence.Atomic -> "Bottom"
      case TypeSyntax.Arrow(parameterType, resultType) =>
        Precedence.Arrow -> (
          s"${renderType(parameterType, Precedence.Arrow + 1)} -> " +
            renderType(resultType, Precedence.Arrow)
        )
      case TypeSyntax.ForAll(typeParameter, disjointBound, bodyType) =>
        Precedence.Binding -> (
          s"∀($typeParameter * ${renderType(disjointBound, Precedence.Minimum)}). " +
            renderType(bodyType, Precedence.Minimum)
        )
      case TypeSyntax.Intersection(leftType, rightType) =>
        Precedence.Intersection -> (
          s"${renderType(leftType, Precedence.Intersection)} & " +
            renderType(rightType, Precedence.Intersection + 1)
        )
      case TypeSyntax.Record(label, fieldType) =>
        Precedence.Atomic -> s"{$label : ${renderType(fieldType, Precedence.Minimum)}}"
      case TypeSyntax.Trait(requiredInterface, providedInterface) =>
        Precedence.Atomic -> (
          s"Trait[${renderType(requiredInterface, Precedence.Minimum)}, " +
            s"${renderType(providedInterface, Precedence.Minimum)}]"
        )
      case TypeSyntax.SignatureApplication(reference, arguments) =>
        val renderedArguments = arguments.map(renderSortArgument).mkString(", ")
        Precedence.Atomic -> s"${reference.render}<$renderedArguments>"
    }
    if (precedence < enclosingPrecedence) s"($syntax)" else syntax
  }

  private def renderSortArgument(argument: SortArgument): String = argument match {
    case SortArgument.TypeArgument(argumentType) => renderType(argumentType, Precedence.Minimum)
    case SortArgument.Dependency(negativeType, positiveType) =>
      s"${renderType(negativeType, Precedence.Intersection + 1)} % " +
        renderType(positiveType, Precedence.Intersection + 1)
  }

  private def primitiveTypeSyntax(primitiveType: PrimitiveType): String = primitiveType match {
    case PrimitiveType.Integer => "Int"
    case PrimitiveType.Decimal => "Decimal"
    case PrimitiveType.Boolean => "Bool"
    case PrimitiveType.Text => "String"
    case PrimitiveType.Unit => "Unit"
  }
}

private object Precedence {
  val Minimum = 0
  val Binding = 1
  val Arrow = 2
  val Intersection = 3
  val Atomic = 4
}
