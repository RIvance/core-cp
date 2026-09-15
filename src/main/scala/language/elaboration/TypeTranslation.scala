package cp.language.elaboration

import cp.fiobs.{SurfaceType, Type as FiobsType}
import cp.language.core.{SortArgument, Type}
import cp.naming.NameReference
import cp.util.Result

enum TypeTranslationError {
  case UnboundTypeVariable(name: String, scope: List[String])
  case UnexpandedNamedType(reference: NameReference)
  case UnexpandedSignatureApplication(reference: NameReference, arguments: List[SortArgument])
}

object TypeTranslation {
  // ⟦p⟧ = p                     ⟦A ⇾ B⟧ = ⟦A⟧ ⇾ ⟦B⟧
  // ⟦⊤⟧ = ⊤                     ⟦A ∧ B⟧ = ⟦A⟧ ∧ ⟦B⟧
  // ⟦⊥⟧ = ⊥                     ⟦{ℓ : A}⟧ = {ℓ : ⟦A⟧}
  // ⟦∀(α ∗ A). B⟧ = ∀(α ∗ ⟦A⟧). ⟦B⟧
  // ⟦Trait[A, B]⟧ = ⟦A⟧ ⇾ ⟦B⟧
  //
  // Signature applications have no translation rule: Expand-Sig must remove
  // them before this homomorphic translation begins.
  def toSurfaceType(inputType: Type): Result[SurfaceType, TypeTranslationError] = inputType match {
    case Type.Primitive(kind) => Result.Ok(SurfaceType.Primitive(kind))
    case Type.Variable(name) => Result.Ok(SurfaceType.Variable(name))
    case Type.Named(reference) => Result.Err(TypeTranslationError.UnexpandedNamedType(reference))
    case Type.Top => Result.Ok(SurfaceType.Top)
    case Type.Bottom => Result.Ok(SurfaceType.Bottom)
    case Type.Arrow(parameterType, resultType) =>
      for {
        translatedParameter <- toSurfaceType(parameterType)
        translatedResult <- toSurfaceType(resultType)
      } yield SurfaceType.Arrow(translatedParameter, translatedResult)
    case Type.ForAll(typeParameter, disjointBound, bodyType) =>
      for {
        translatedBound <- toSurfaceType(disjointBound)
        translatedBody <- toSurfaceType(bodyType)
      } yield SurfaceType.ForAll(typeParameter, translatedBound, translatedBody)
    case Type.Intersection(leftType, rightType) =>
      for {
        translatedLeft <- toSurfaceType(leftType)
        translatedRight <- toSurfaceType(rightType)
      } yield SurfaceType.Intersection(translatedLeft, translatedRight)
    case Type.Record(label, fieldType) =>
      toSurfaceType(fieldType).map(SurfaceType.Record(label, _))
    case Type.Trait(requiredInterface, providedInterface) =>
      for {
        translatedRequired <- toSurfaceType(requiredInterface)
        translatedProvided <- toSurfaceType(providedInterface)
      } yield SurfaceType.Arrow(translatedRequired, translatedProvided)
    case Type.SignatureApplication(name, arguments) =>
      Result.Err(TypeTranslationError.UnexpandedSignatureApplication(name, arguments))
  }

  def toFiobsType(
    inputType: Type,
    typeScope: List[String]
  ): Result[FiobsType, TypeTranslationError] = inputType match {
    case Type.Primitive(kind) => Result.Ok(FiobsType.Primitive(kind))
    case Type.Variable(name) =>
      typeScope.indexOf(name) match {
        case -1 => Result.Err(TypeTranslationError.UnboundTypeVariable(name, typeScope))
        case index => Result.Ok(FiobsType.Variable(index))
      }
    case Type.Named(reference) => Result.Err(TypeTranslationError.UnexpandedNamedType(reference))
    case Type.Top => Result.Ok(FiobsType.Top)
    case Type.Bottom => Result.Ok(FiobsType.Bottom)
    case Type.Arrow(parameterType, resultType) =>
      for {
        translatedParameter <- toFiobsType(parameterType, typeScope)
        translatedResult <- toFiobsType(resultType, typeScope)
      } yield FiobsType.Arrow(translatedParameter, translatedResult)
    case Type.ForAll(typeParameter, disjointBound, bodyType) =>
      for {
        translatedBound <- toFiobsType(disjointBound, typeScope)
        translatedBody <- toFiobsType(bodyType, typeParameter :: typeScope)
      } yield FiobsType.ForAll(translatedBound, translatedBody)
    case Type.Intersection(leftType, rightType) =>
      for {
        translatedLeft <- toFiobsType(leftType, typeScope)
        translatedRight <- toFiobsType(rightType, typeScope)
      } yield FiobsType.Intersection(translatedLeft, translatedRight)
    case Type.Record(label, fieldType) =>
      toFiobsType(fieldType, typeScope).map(FiobsType.Record(label, _))
    case Type.Trait(requiredInterface, providedInterface) =>
      for {
        translatedRequired <- toFiobsType(requiredInterface, typeScope)
        translatedProvided <- toFiobsType(providedInterface, typeScope)
      } yield FiobsType.Arrow(translatedRequired, translatedProvided)
    case Type.SignatureApplication(name, arguments) =>
      Result.Err(TypeTranslationError.UnexpandedSignatureApplication(name, arguments))
  }
}
