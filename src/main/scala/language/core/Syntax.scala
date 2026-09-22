package cp.language.core

import cp.naming.NameReference
import cp.primitive.{BinaryOperator, PrimitiveType, PrimitiveValue}
import cp.source.SourceSpan

final case class TypeBinder(name: String, disjointBound: TypeSyntax)

final case class ValueParameter(name: String, parameterType: TypeSyntax)

final case class PatternParameter(name: String, declaredType: Option[TypeSyntax])

enum SortArgument {
  case TypeArgument(argumentType: TypeSyntax)
  case Dependency(negativeType: TypeSyntax, positiveType: TypeSyntax)
}

/** Unresolved CP type syntax. References acquire binder or module identities during expansion. */
enum TypeSyntax {
  case Primitive(kind: PrimitiveType)
  case Reference(reference: NameReference)
  case Top
  case Bottom
  case Arrow(parameterType: TypeSyntax, resultType: TypeSyntax)
  case ForAll(typeParameter: String, disjointBound: TypeSyntax, bodyType: TypeSyntax)
  case Recursive(typeParameter: String, bodyType: TypeSyntax)
  case Intersection(leftType: TypeSyntax, rightType: TypeSyntax)
  case Record(label: String, fieldType: TypeSyntax)
  case Trait(requiredInterface: TypeSyntax, providedInterface: TypeSyntax)
  case SignatureApplication(reference: NameReference, arguments: List[SortArgument])

  def render: String = CpTypeRendering.render(this)

  /** Module references, excluding lexically bound type and sort parameters. */
  def referencedNames(boundVariables: Set[String] = Set.empty): Set[NameReference] = this match {
    case Primitive(_) | Top | Bottom => Set.empty
    case Reference(NameReference.Unqualified(name)) if boundVariables.contains(name) => Set.empty
    case Reference(reference) => Set(reference)
    case Arrow(parameterType, resultType) =>
      parameterType.referencedNames(boundVariables) ++ resultType.referencedNames(boundVariables)
    case ForAll(typeParameter, disjointBound, bodyType) =>
      disjointBound.referencedNames(boundVariables) ++ bodyType.referencedNames(boundVariables + typeParameter)
    case Recursive(typeParameter, bodyType) => bodyType.referencedNames(boundVariables + typeParameter)
    case Intersection(leftType, rightType) =>
      leftType.referencedNames(boundVariables) ++ rightType.referencedNames(boundVariables)
    case Record(_, fieldType) => fieldType.referencedNames(boundVariables)
    case Trait(requiredInterface, providedInterface) =>
      requiredInterface.referencedNames(boundVariables) ++ providedInterface.referencedNames(boundVariables)
    case SignatureApplication(reference, arguments) =>
      Set(reference) ++ arguments.flatMap {
        case SortArgument.TypeArgument(argumentType) => argumentType.referencedNames(boundVariables)
        case SortArgument.Dependency(negativeType, positiveType) =>
          negativeType.referencedNames(boundVariables) ++ positiveType.referencedNames(boundVariables)
      }
  }
}

object TypeSyntax {
  val Integer: TypeSyntax = Primitive(PrimitiveType.Integer)
  val Decimal: TypeSyntax = Primitive(PrimitiveType.Decimal)
  val Boolean: TypeSyntax = Primitive(PrimitiveType.Boolean)
  val Text: TypeSyntax = Primitive(PrimitiveType.Text)
  val Unit: TypeSyntax = Primitive(PrimitiveType.Unit)

  def records(firstField: (String, TypeSyntax), remainingFields: List[(String, TypeSyntax)]): TypeSyntax = {
    remainingFields.foldLeft(Record(firstField._1, firstField._2): TypeSyntax) {
      case (recordType, (label, fieldType)) => Intersection(recordType, Record(label, fieldType))
    }
  }
}

enum Member {
  case Field(label: String, value: Expression, annotation: Option[TypeSyntax])
  case MethodPattern(
    constructorName: String,
    constructorParameters: List[PatternParameter],
    selfRequirement: Option[TypeSyntax],
    label: String,
    valueParameters: List[ValueParameter],
    body: Expression
  )

  def definitionName: String = this match {
    case Field(label, _, _) => label
    case MethodPattern(constructorName, _, _, _, _, _) => constructorName
  }

  def declaredType: Option[TypeSyntax] = this match {
    case Field(_, _, annotation) => annotation
    case MethodPattern(_, _, _, _, _, _) => None
  }

  def withoutSourceSpans: Member = this match {
    case Field(label, value, annotation) => Field(label, value.withoutSourceSpans, annotation)
    case MethodPattern(
      constructorName,
      constructorParameters,
      selfRequirement,
      label,
      valueParameters,
      body
    ) =>
      MethodPattern(
        constructorName,
        constructorParameters,
        selfRequirement,
        label,
        valueParameters,
        body.withoutSourceSpans
      )
  }
}

enum Expression {
  case Literal(value: PrimitiveValue)
  case Variable(reference: NameReference)
  case Top
  case Lambda(parameter: ValueParameter, body: Expression)
  case Application(function: Expression, argument: Expression)
  case TypeLambda(binder: TypeBinder, body: Expression)
  case TypeApplication(function: Expression, argumentType: TypeSyntax)
  case Fold(recursiveType: TypeSyntax, body: Expression)
  case Unfold(recursiveType: TypeSyntax, expression: Expression)
  case Merge(left: Expression, right: Expression)
  case Record(members: List[Member])
  case Projection(record: Expression, label: String)
  case Annotation(expression: Expression, annotatedType: TypeSyntax)
  case Let(
    name: String,
    declaredType: Option[TypeSyntax],
    initializer: Expression,
    body: Expression
  )
  case RecursiveLet(
    name: String,
    declaredType: TypeSyntax,
    initializer: Expression,
    body: Expression
  )
  case Open(record: Expression, body: Expression)
  case New(traitExpression: Expression)
  case Forward(traitExpression: Expression, selfArgument: Expression)
  /** None denotes an omitted inheritance clause; explicit parents retain their expression. */
  case Trait(
    selfName: String,
    selfRequirement: TypeSyntax,
    providedInterface: TypeSyntax,
    inheritedTrait: Option[Expression],
    body: Expression
  )
  case Binary(operator: BinaryOperator, left: Expression, right: Expression)
  case If(condition: Expression, whenTrue: Expression, whenFalse: Expression)
  case Located(expression: Expression, sourceSpan: SourceSpan)

  /** Removes parser-owned diagnostic metadata while preserving the semantic tree. */
  def withoutSourceSpans: Expression = this match {
    case Literal(_) | Variable(_) | Top => this
    case Lambda(parameter, body) => Lambda(parameter, body.withoutSourceSpans)
    case Application(function, argument) =>
      Application(function.withoutSourceSpans, argument.withoutSourceSpans)
    case TypeLambda(binder, body) => TypeLambda(binder, body.withoutSourceSpans)
    case TypeApplication(function, argumentType) =>
      TypeApplication(function.withoutSourceSpans, argumentType)
    case Fold(recursiveType, body) => Fold(recursiveType, body.withoutSourceSpans)
    case Unfold(recursiveType, expression) => Unfold(recursiveType, expression.withoutSourceSpans)
    case Merge(left, right) => Merge(left.withoutSourceSpans, right.withoutSourceSpans)
    case Record(members) => Record(members.map(_.withoutSourceSpans))
    case Projection(record, label) => Projection(record.withoutSourceSpans, label)
    case Annotation(expression, annotatedType) =>
      Annotation(expression.withoutSourceSpans, annotatedType)
    case Let(name, declaredType, initializer, body) =>
      Let(name, declaredType, initializer.withoutSourceSpans, body.withoutSourceSpans)
    case RecursiveLet(name, declaredType, initializer, body) =>
      RecursiveLet(name, declaredType, initializer.withoutSourceSpans, body.withoutSourceSpans)
    case Open(record, body) => Open(record.withoutSourceSpans, body.withoutSourceSpans)
    case New(traitExpression) => New(traitExpression.withoutSourceSpans)
    case Forward(traitExpression, selfArgument) =>
      Forward(traitExpression.withoutSourceSpans, selfArgument.withoutSourceSpans)
    case Trait(selfName, selfRequirement, providedInterface, inheritedTrait, body) =>
      Trait(
        selfName,
        selfRequirement,
        providedInterface,
        inheritedTrait.map(_.withoutSourceSpans),
        body.withoutSourceSpans
      )
    case Binary(operator, left, right) =>
      Binary(operator, left.withoutSourceSpans, right.withoutSourceSpans)
    case If(condition, whenTrue, whenFalse) =>
      If(
        condition.withoutSourceSpans,
        whenTrue.withoutSourceSpans,
        whenFalse.withoutSourceSpans
      )
    case Located(expression, _) => expression.withoutSourceSpans
  }
}

object Expression {
  def variable(name: String): Expression = Variable(NameReference.Unqualified(name))

  def curriedLambda(parameters: List[ValueParameter], body: Expression): Expression = {
    parameters.reverse.foldLeft(body: Expression) { (currentBody, parameter) =>
      Lambda(parameter, currentBody)
    }
  }

  def curriedApplication(function: Expression, arguments: List[Expression]): Expression = {
    arguments.foldLeft(function: Expression)(Application(_, _))
  }

  def typeAbstractions(binders: List[TypeBinder], body: Expression): Expression = {
    binders.reverse.foldLeft(body: Expression) { (currentBody, binder) =>
      TypeLambda(binder, currentBody)
    }
  }

  def typeApplications(function: Expression, arguments: List[TypeSyntax]): Expression = {
    arguments.foldLeft(function: Expression)(TypeApplication(_, _))
  }
}
