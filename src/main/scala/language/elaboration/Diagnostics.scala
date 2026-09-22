package cp.language.elaboration

import cp.language.core.Expression
import cp.language.typing.Type
import cp.naming.Identifier
import cp.primitive.BinaryOperator
import cp.source.SourceSpan
import cp.util.Result

enum CpElaborationError {
  case Located(sourceSpan: SourceSpan, error: CpElaborationError)
  case NameResolution(error: NameResolutionError)
  case TypeExpansion(error: TypeExpansionError)
  case CheckingShapeMismatch(expression: Expression, expectedType: Type)
  case TypeMismatch(expression: Expression, actualType: Type, expectedType: Type)
  case LambdaParameterMismatch(actualType: Type, expectedType: Type)
  case TypeLambdaBoundMismatch(actualBound: Type, expectedBound: Type)
  case ExpectedFunction(expression: Expression, actualType: Type)
  case ExpectedUniversal(expression: Expression, actualType: Type)
  case ExpectedRecursiveType(actualType: Type)
  case ExpectedRecord(expression: Expression, actualType: Type)
  case MissingRecordField(expression: Expression, label: String, actualType: Type)
  case ExpectedTrait(expression: Expression, actualType: Type)
  case TypesAreNotDisjoint(leftType: Type, rightType: Type)
  case TypeArgumentViolatesBound(argumentType: Type, disjointBound: Type)
  case TraitRequirementNotSatisfied(providedInterface: Type, requiredInterface: Type)
  case TraitImplementationMismatch(actualInterface: Type, declaredInterface: Type)
  case PrimitiveSignatureNotFound(operator: BinaryOperator, expression: Expression)
  case PatternParameterTypeUnavailable(constructorName: String, parameterName: String)
  case RecursiveDeclarationRequiresType(name: String)
  case DuplicateTermDefinition(name: String)
  case DuplicateTypeDefinition(name: String)
  case RecursiveTypeDefinitions(identifiers: List[Identifier])

  /** Keeps the narrowest expression span while an error propagates outward. */
  def at(sourceSpan: SourceSpan): CpElaborationError = this match {
    case located: Located => located
    case _ => Located(sourceSpan, this)
  }

  def location: Option[SourceSpan] = this match {
    case Located(sourceSpan, _) => Some(sourceSpan)
    case _ => None
  }

  def underlying: CpElaborationError = this match {
    case Located(_, error) => error.underlying
    case _ => this
  }
}

private[elaboration] type ElaborationResult[A] = Result[A, CpElaborationError]
