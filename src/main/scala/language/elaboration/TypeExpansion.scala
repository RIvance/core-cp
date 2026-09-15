package cp.language.elaboration

import cp.language.core.{Declaration, SortArgument, Type}
import cp.naming.{Identifier, NameReference}
import cp.util.Result

enum TypeExpansionError {
  case NameResolution(error: NameResolutionError)
  case UnknownSignature(identifier: Identifier)
  case SignatureArityMismatch(identifier: Identifier, expected: Int, actual: Int)
  case UnexpectedNamedType(inputType: Type)
  case UnexpectedSignatureApplication(inputType: Type)
}

final case class SignatureDefinition(
  negativeParameters: List[String],
  positiveParameters: List[String],
  bodyType: Type
)

final case class TypeExpansionContext private (
  moduleScope: ModuleScope,
  signatures: Map[Identifier, SignatureDefinition]
) {
  def lookup(identifier: Identifier): Option[SignatureDefinition] = {
    signatures.get(identifier)
  }

  def resolve(reference: NameReference): Result[Identifier, TypeExpansionError] = {
    moduleScope.resolveType(reference).mapError(TypeExpansionError.NameResolution(_))
  }

  def register(identifier: Identifier, definition: SignatureDefinition): TypeExpansionContext = {
    copy(signatures = signatures.updated(identifier, definition))
  }

  def registeredNames: Set[String] = {
    signatures.keySet.map(_.name)
  }
}

object TypeExpansionContext {
  def apply(
    moduleScope: ModuleScope,
    importedDefinitions: Map[Identifier, SignatureDefinition]
  ): TypeExpansionContext = new TypeExpansionContext(moduleScope, importedDefinitions)
}

enum TypePolarity {
  case Positive, Negative

  def flipped: TypePolarity = this match {
    case Positive => Negative
    case Negative => Positive
  }
}

object TypeExpansion {
  def registerSignature(
    declaration: Declaration.TypeSignature,
    identifier: Identifier,
    context: TypeExpansionContext
  ): Result[TypeExpansionContext, TypeExpansionError] = {
    val unavailableNames =
      declaration.requiredInterface.freeVariables ++
        declaration.providedInterface.freeVariables ++
        declaration.sortParameters.toSet ++
        context.registeredNames
    val positiveParameters = freshPositiveParameters(
      declaration.sortParameters,
      unavailableNames
    )
    val sortMappings = declaration.sortParameters.zip(positiveParameters).toMap

    // β̄ fresh
    // Δ ; ᾱ ↦ β̄ ⊢ A ⇒ A₁    Δ ; ᾱ ↦ β̄ ⊢ B ⇒ B₁
    // ᾱ ↦ β̄ ⊢⁺false B₁ ⇒ B₂
    // These are the signature-expansion premises of E-TypeDecl. The complete
    // program rule is beside the declaration case in CpElaborator.
    for {
      requiredInterface <- expand(
        declaration.requiredInterface,
        context,
        sortMappings,
        declaration.sortParameters.toSet
      )
      providedInterface <- expand(
        declaration.providedInterface,
        context,
        sortMappings,
        declaration.sortParameters.toSet
      )
      transformedProvided <- transformSorts(
        providedInterface,
        sortMappings,
        TypePolarity.Positive,
        insideConstructorField = false
      )
    } yield context.register(
      identifier,
      SignatureDefinition(
        declaration.sortParameters,
        positiveParameters,
        Type.Intersection(requiredInterface, transformedProvided)
      )
    )
  }

  def expand(
    inputType: Type,
    context: TypeExpansionContext,
    sortMappings: Map[String, String] = Map.empty,
    boundVariables: Set[String] = Set.empty
  ): Result[Type, TypeExpansionError] = inputType match {
    case Type.Primitive(_) | Type.Top | Type.Bottom => Result.Ok(inputType)

    // α ∈ Ξ
    // ───────────── Expand-Var
    // Δ, Σ, Ξ ⊢ α ⇒ α
    case Type.Variable(name) if boundVariables.contains(name) => Result.Ok(inputType)

    case Type.Variable(name) =>
      context.resolve(NameReference.Unqualified(name)).flatMap { identifier =>
        context.lookup(identifier) match {
          // X⟨⟩ ↦ C ∈ Δ
          // ──────────────── Expand-Alias
          // Δ, Σ, Ξ ⊢ X ⇒ C
          case Some(definition) if definition.negativeParameters.isEmpty => Result.Ok(definition.bodyType)
          case Some(definition) => Result.Err(TypeExpansionError.SignatureArityMismatch(
            identifier,
            definition.negativeParameters.length,
            0
          ))
          case None => Result.Err(TypeExpansionError.UnknownSignature(identifier))
        }
      }
    case Type.Named(reference) =>
      context.resolve(reference).flatMap { identifier =>
        context.lookup(identifier) match {
          case Some(definition) if definition.negativeParameters.isEmpty => Result.Ok(definition.bodyType)
          case Some(definition) => Result.Err(TypeExpansionError.SignatureArityMismatch(
            identifier,
            definition.negativeParameters.length,
            0
          ))
          case None => Result.Err(TypeExpansionError.UnknownSignature(identifier))
        }
      }
    case Type.Arrow(parameterType, resultType) =>
      for {
        expandedParameter <- expand(parameterType, context, sortMappings, boundVariables)
        expandedResult <- expand(resultType, context, sortMappings, boundVariables)
      } yield Type.Arrow(expandedParameter, expandedResult)
    case Type.ForAll(typeParameter, disjointBound, bodyType) =>
      for {
        expandedBound <- expand(disjointBound, context, sortMappings, boundVariables)
        expandedBody <- expand(
          bodyType,
          context,
          sortMappings - typeParameter,
          boundVariables + typeParameter
        )
      } yield Type.ForAll(typeParameter, expandedBound, expandedBody)
    case Type.Intersection(leftType, rightType) =>
      for {
        expandedLeft <- expand(leftType, context, sortMappings, boundVariables)
        expandedRight <- expand(rightType, context, sortMappings, boundVariables)
      } yield Type.Intersection(expandedLeft, expandedRight)
    case Type.Record(label, fieldType) =>
      expand(fieldType, context, sortMappings, boundVariables).map(Type.Record(label, _))
    case Type.Trait(requiredInterface, providedInterface) =>
      for {
        expandedRequired <- expand(requiredInterface, context, sortMappings, boundVariables)
        expandedProvided <- expand(providedInterface, context, sortMappings, boundVariables)
      } yield Type.Trait(expandedRequired, expandedProvided)
    case Type.SignatureApplication(reference, arguments) =>
      context.resolve(reference).flatMap { identifier =>
        context.lookup(identifier) match {
          case None => Result.Err(TypeExpansionError.UnknownSignature(identifier))
          case Some(definition) if definition.negativeParameters.length != arguments.length =>
            Result.Err(TypeExpansionError.SignatureArityMismatch(
              identifier,
              definition.negativeParameters.length,
              arguments.length
            ))
          case Some(definition) =>
            // X⟨ᾱ, β̄⟩ ↦ C ∈ Δ    Σ ⊢ S̄ ⇒ (Ā, B̄)
            // ───────────────────────────────────────────── Expand-Sig
            // Δ, Σ ⊢ X⟨S̄⟩ ⇒ [Ā/ᾱ, B̄/β̄]C
            Result.traverse(arguments)(
              expandSortArgument(_, context, sortMappings, boundVariables)
            ).map { pairs =>
              val replacements =
                definition.negativeParameters.zip(pairs.map(_._1)).toMap ++
                  definition.positiveParameters.zip(pairs.map(_._2)).toMap
              definition.bodyType.substituteVariables(replacements)
            }
        }
      }
  }

  private def expandSortArgument(
    argument: SortArgument,
    context: TypeExpansionContext,
    sortMappings: Map[String, String],
    boundVariables: Set[String]
  ): Result[(Type, Type), TypeExpansionError] = argument match {
    // α ↦ β ∈ Σ
    // ───────────────── Sort-Var
    // Σ ⊢ α ⇒ (α, β)
    case SortArgument.TypeArgument(Type.Variable(name)) if sortMappings.contains(name) =>
      Result.Ok(Type.Variable(name) -> Type.Variable(sortMappings(name)))

    // Δ, Σ ⊢ A ⇒ A′    A is not a registered sort variable
    // ──────────────────────────────────────────────── Sort-Type
    // Σ ⊢ A ⇒ (A′, A′)
    case SortArgument.TypeArgument(argumentType) =>
      expand(argumentType, context, sortMappings, boundVariables).map(expanded => expanded -> expanded)

    // Δ, Σ ⊢ A ⇒ A′    Δ, Σ ⊢ B ⇒ B′
    // ─────────────────────────────────────── Sort-Pair
    // Σ ⊢ A % B ⇒ (A′ ∧ B′, B′)
    case SortArgument.Dependency(negativeType, positiveType) =>
      for {
        expandedNegative <- expand(negativeType, context, sortMappings, boundVariables)
        expandedPositive <- expand(positiveType, context, sortMappings, boundVariables)
      } yield Type.Intersection(expandedNegative, expandedPositive) -> expandedPositive
  }

  private def transformSorts(
    inputType: Type,
    sortMappings: Map[String, String],
    polarity: TypePolarity,
    insideConstructorField: Boolean
  ): Result[Type, TypeExpansionError] = inputType match {
    // α ↦ β ∈ Σ
    // ───────────────────────── ST-Positive
    // Σ ⊢⁺false α ⇒ β
    case Type.Variable(name)
        if polarity == TypePolarity.Positive &&
          !insideConstructorField &&
          sortMappings.contains(name) =>
      Result.Ok(Type.Variable(sortMappings(name)))

    // α ↦ β ∈ Σ
    // ─────────────────────────────── ST-CtorPositive
    // Σ ⊢⁺true α ⇒ Trait[α, β]
    case Type.Variable(name)
        if polarity == TypePolarity.Positive &&
          insideConstructorField &&
          sortMappings.contains(name) =>
      Result.Ok(Type.Trait(Type.Variable(name), Type.Variable(sortMappings(name))))

    case Type.Primitive(_) | Type.Top | Type.Bottom | Type.Variable(_) => Result.Ok(inputType)

    case Type.Named(_) => Result.Err(TypeExpansionError.UnexpectedNamedType(inputType))

    // Σ ⊢ᶠˡⁱᵖ⁽ᵖ⁾ A ⇒ A′    Σ ⊢ᵖ B ⇒ B′
    // ─────────────────────────────────────── ST-Arrow
    // Σ ⊢ᵖ A ⇾ B ⇒ A′ ⇾ B′
    case Type.Arrow(parameterType, resultType) =>
      for {
        transformedParameter <- transformSorts(
          parameterType,
          sortMappings,
          polarity.flipped,
          insideConstructorField
        )
        transformedResult <- transformSorts(
          resultType,
          sortMappings,
          polarity,
          insideConstructorField
        )
      } yield Type.Arrow(transformedParameter, transformedResult)

    case Type.ForAll(typeParameter, disjointBound, bodyType) =>
      val mappingsBelowBinder = sortMappings - typeParameter
      // Σ∖{α} ⊢ᵖ A ⇒ A′    Σ∖{α} ⊢ᵖ B ⇒ B′
      // ───────────────────────────────────────── ST-All
      // Σ ⊢ᵖ ∀(α ∗ A). B ⇒ ∀(α ∗ A′). B′
      for {
        transformedBound <- transformSorts(
          disjointBound,
          mappingsBelowBinder,
          polarity,
          insideConstructorField
        )
        transformedBody <- transformSorts(
          bodyType,
          mappingsBelowBinder,
          polarity,
          insideConstructorField
        )
      } yield Type.ForAll(typeParameter, transformedBound, transformedBody)

    // Σ ⊢ᵖ A ⇒ A′    Σ ⊢ᵖ B ⇒ B′
    // ─────────────────────────── ST-And
    // Σ ⊢ᵖ A ∧ B ⇒ A′ ∧ B′
    case Type.Intersection(leftType, rightType) =>
      for {
        transformedLeft <- transformSorts(leftType, sortMappings, polarity, insideConstructorField)
        transformedRight <- transformSorts(rightType, sortMappings, polarity, insideConstructorField)
      } yield Type.Intersection(transformedLeft, transformedRight)

    // Σ ⊢ᵖᶜᵃᵖ⁽ℓ⁾ A ⇒ A′
    // ─────────────────────── ST-Record
    // Σ ⊢ᵖ {ℓ : A} ⇒ {ℓ : A′}
    case Type.Record(label, fieldType) =>
      transformSorts(
        fieldType,
        sortMappings,
        polarity,
        insideConstructorField = label.headOption.exists(_.isUpper)
      ).map(Type.Record(label, _))

    // Σ ⊢ᶠˡⁱᵖ⁽ᵖ⁾ A ⇒ A′    Σ ⊢ᵖ B ⇒ B′
    // ─────────────────────────────────────── ST-Trait
    // Σ ⊢ᵖ Trait[A, B] ⇒ Trait[A′, B′]
    case Type.Trait(requiredInterface, providedInterface) =>
      for {
        transformedRequired <- transformSorts(
          requiredInterface,
          sortMappings,
          polarity.flipped,
          insideConstructorField
        )
        transformedProvided <- transformSorts(
          providedInterface,
          sortMappings,
          polarity,
          insideConstructorField
        )
      } yield Type.Trait(transformedRequired, transformedProvided)

    case signatureApplication @ Type.SignatureApplication(_, _) =>
      Result.Err(TypeExpansionError.UnexpectedSignatureApplication(signatureApplication))
  }

  private def freshPositiveParameters(
    sortParameters: List[String],
    unavailableNames: Set[String]
  ): List[String] = {
    sortParameters.foldLeft((List.empty[String], unavailableNames)) {
      case ((generated, unavailable), sortParameter) =>
        val positiveParameter = Type.freshName(s"${sortParameter}Positive", unavailable)
        (generated :+ positiveParameter, unavailable + positiveParameter)
    }._1
  }
}
