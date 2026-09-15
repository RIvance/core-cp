package cp.language.elaboration

import cp.fiobs.{Expr as FiobsExpression, SurfaceType, TypeContext}
import cp.fiobs.typing.Disjointness
import cp.language.core.*
import cp.naming.{Identifier, NameReference, Namespace}
import cp.primitive.BinaryOperator
import cp.source.SourceSpan
import cp.util.{Graph, Result}

final case class ElaboratedExpression(expression: FiobsExpression, inferredType: Type)

enum CpElaborationError {
  case Located(sourceSpan: SourceSpan, error: CpElaborationError)
  case NameResolution(error: NameResolutionError)
  case TypeExpansion(error: TypeExpansionError)
  case TypeTranslation(error: TypeTranslationError)
  case UnboundTermVariable(name: String, scope: List[String])
  case CannotInfer(expression: Expression)
  case TypeMismatch(expression: Expression, actualType: Type, expectedType: Type)
  case LambdaParameterMismatch(actualType: Type, expectedType: Type)
  case TypeLambdaBoundMismatch(actualBound: Type, expectedBound: Type)
  case ExpectedFunction(expression: Expression, actualType: Type)
  case ExpectedUniversal(expression: Expression, actualType: Type)
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

private enum ResolvedTermReference {
  case Local(name: String, referenceType: Type)
  case Global(identifier: Identifier, referenceType: Type)
}

private final case class ElaborationContext(
  moduleScope: ModuleScope,
  typeExpansion: TypeExpansionContext,
  typeScope: List[String],
  typeContext: TypeContext,
  localTermTypes: List[(String, Type)],
  localGlobalTermAliases: Map[Identifier, (String, Type)],
  globalTermTypes: Map[Identifier, Type],
  patternConstructorTypes: List[(String, Type)]
) {
  def resolveTerm(reference: NameReference): Result[ResolvedTermReference, CpElaborationError] = {
    reference match {
      case NameReference.Unqualified(name) =>
        localTermTypes.collectFirst { case (`name`, inputType) => inputType } match {
          case Some(inputType) => Result.Ok(ResolvedTermReference.Local(name, inputType))
          case None => resolveGlobalTerm(reference)
        }
      case NameReference.Qualified(identifier) =>
        localGlobalTermAliases.get(identifier) match {
          case Some((name, inputType)) => Result.Ok(ResolvedTermReference.Local(name, inputType))
          case None => resolveGlobalTerm(reference)
        }
    }
  }

  def withLocalTerm(name: String, inputType: Type): ElaborationContext = {
    copy(localTermTypes = (name -> inputType) :: localTermTypes)
  }

  def withLocalTerms(bindings: List[(String, Type)]): ElaborationContext = {
    bindings.foldLeft(this) { case (currentContext, (name, inputType)) =>
      currentContext.withLocalTerm(name, inputType)
    }
  }

  def withLocalGlobalTermAliases(
    bindings: Iterable[(Identifier, String, Type)]
  ): ElaborationContext = {
    copy(localGlobalTermAliases = localGlobalTermAliases ++ bindings.map {
      case (identifier, localName, inputType) => identifier -> (localName -> inputType)
    })
  }

  def withGlobalTerm(identifier: Identifier, inputType: Type): ElaborationContext = {
    copy(globalTermTypes = globalTermTypes.updated(identifier, inputType))
  }

  def withGlobalTerms(bindings: Iterable[(Identifier, Type)]): ElaborationContext = {
    bindings.foldLeft(this) { case (currentContext, (identifier, inputType)) =>
      currentContext.withGlobalTerm(identifier, inputType)
    }
  }

  def lookupPatternConstructor(name: String): Option[Type] = {
    patternConstructorTypes.collectFirst { case (`name`, constructorType) => constructorType }
  }

  def withPatternConstructors(interfaceType: Type): ElaborationContext = {
    copy(patternConstructorTypes = interfaceType.recordFields.toList ++ patternConstructorTypes)
  }

  def withType(
    binder: TypeBinder
  ): Result[ElaborationContext, CpElaborationError] = {
    TypeTranslation.toFiobsType(binder.disjointBound, typeScope)
      .mapError(CpElaborationError.TypeTranslation(_))
      .map { translatedBound =>
        copy(
          typeScope = binder.name :: typeScope,
          typeContext = typeContext.extend(translatedBound)
        )
      }
  }

  def withTypeExpansion(expansion: TypeExpansionContext): ElaborationContext = {
    copy(typeExpansion = expansion)
  }

  def isSubtype(sourceType: Type, targetType: Type): Result[Boolean, CpElaborationError] = {
    for {
      translatedSource <- TypeTranslation.toFiobsType(sourceType, typeScope)
        .mapError(CpElaborationError.TypeTranslation(_))
      translatedTarget <- TypeTranslation.toFiobsType(targetType, typeScope)
        .mapError(CpElaborationError.TypeTranslation(_))
    } yield translatedSource.isSubtypeOf(translatedTarget, typeContext)
  }

  def areDisjoint(leftType: Type, rightType: Type): Result[Boolean, CpElaborationError] = {
    for {
      translatedLeft <- TypeTranslation.toFiobsType(leftType, typeScope)
        .mapError(CpElaborationError.TypeTranslation(_))
      translatedRight <- TypeTranslation.toFiobsType(rightType, typeScope)
        .mapError(CpElaborationError.TypeTranslation(_))
    } yield Disjointness(typeContext).relates(translatedLeft, translatedRight)
  }

  def termScope: List[String] = {
    localTermTypes.map(_._1) ++ globalTermTypes.keysIterator.map(_.name)
  }

  private def resolveGlobalTerm(
    reference: NameReference
  ): Result[ResolvedTermReference, CpElaborationError] = {
    moduleScope.resolveTerm(reference)
      .mapError(CpElaborationError.NameResolution(_))
      .flatMap { identifier =>
        globalTermTypes.get(identifier) match {
          case Some(inputType) => Result.Ok(ResolvedTermReference.Global(identifier, inputType))
          case None => Result.Err(CpElaborationError.UnboundTermVariable(reference.render, termScope))
        }
      }
  }
}

final class CpElaborator private (context: ElaborationContext) {
  def infer(expression: Expression): Result[ElaboratedExpression, CpElaborationError] = expression match {
    case Expression.Located(inner, sourceSpan) => infer(inner).mapError(_.at(sourceSpan))

    // ───────────────────────── E-Primitive
    // Δ ; Γ ⊢ value ⇒ p ↝ value
    case Expression.Literal(value) =>
      Result.Ok(ElaboratedExpression(
        FiobsExpression.Literal(value),
        Type.Primitive(value.primitiveType)
      ))

    // ───────────────── E-Top
    // Δ ; Γ ⊢ top ⇒ ⊤ ↝ top
    case Expression.Top => Result.Ok(ElaboratedExpression(FiobsExpression.Top, Type.Top))

    // x : A ∈ Γ
    // ───────────────── E-Var
    // Δ ; Γ ⊢ x ⇒ A ↝ x
    case Expression.Variable(reference) =>
      context.resolveTerm(reference).map {
        case ResolvedTermReference.Local(name, variableType) =>
          ElaboratedExpression(FiobsExpression.Variable(name), variableType)
        case ResolvedTermReference.Global(identifier, variableType) =>
          ElaboratedExpression(FiobsExpression.Global(identifier), variableType)
      }

    // Δ ; Γ, x : A ⊢ E ⇒ B ↝ e
    // ───────────────────────────────────────── E-Abs-Synth
    // Δ ; Γ ⊢ λ(x : A). E ⇒ A ⇾ B ↝ (λx. e : ⟦A⟧ ⇾ ⟦B⟧)
    case Expression.Lambda(parameter, body) =>
      for {
        parameterType <- expandType(parameter.parameterType)
        typedBody <- CpElaborator(context.withLocalTerm(parameter.name, parameterType)).infer(body)
        completeType = Type.Arrow(parameterType, typedBody.inferredType)
        translatedType <- surfaceType(completeType)
      } yield ElaboratedExpression(
        FiobsExpression.Annotation(
          FiobsExpression.Lambda(parameter.name, typedBody.expression),
          translatedType
        ),
        completeType
      )

    // Δ ; Γ ⊢ E₁ ⇒ A ⇾ B ↝ e₁    Δ ; Γ ⊢ E₂ ⇐ A ↝ e₂
    // ─────────────────────────────────────────────────── E-App
    // Δ ; Γ ⊢ E₁ E₂ ⇒ B ↝ e₁ e₂
    case Expression.Application(function, argument) =>
      infer(function).flatMap { typedFunction =>
        typedFunction.inferredType.termApplicationView match {
          case ApplicativeView.Applicable(TermApplicationView(parameterType, resultType)) =>
            check(argument, parameterType).map { elaboratedArgument =>
              ElaboratedExpression(
                FiobsExpression.Application(typedFunction.expression, elaboratedArgument),
                resultType
              )
            }
          case ApplicativeView.Inert | ApplicativeView.Blocked =>
            Result.Err(CpElaborationError.ExpectedFunction(function, typedFunction.inferredType))
        }
      }

    // Δ, α ∗ A ; Γ ⊢ E ⇒ B ↝ e
    // ───────────────────────────────────────────────────────── E-TAbs
    // Δ ; Γ ⊢ Λ(α ∗ A). E ⇒ ∀(α ∗ A). B
    //   ↝ (Λ(α ∗ ⟦A⟧). e : ∀(α ∗ ⟦A⟧). ⟦B⟧)
    case Expression.TypeLambda(binder, body) =>
      for {
        expandedBound <- expandType(binder.disjointBound)
        extendedContext <- context.withType(TypeBinder(binder.name, expandedBound))
        typedBody <- CpElaborator(extendedContext).infer(body)
        universalType = Type.ForAll(binder.name, expandedBound, typedBody.inferredType)
        translatedBound <- surfaceType(expandedBound)
        translatedUniversal <- surfaceType(universalType)
      } yield ElaboratedExpression(
        FiobsExpression.Annotation(
          FiobsExpression.TypeLambda(binder.name, translatedBound, typedBody.expression),
          translatedUniversal
        ),
        universalType
      )

    // Δ ; Γ ⊢ E ⇒ ∀(α ∗ A). B ↝ e    Δ ⊢ C ∗ A
    // ───────────────────────────────────────────── E-TApp
    // Δ ; Γ ⊢ E@C ⇒ B[α ↦ C] ↝ e[⟦C⟧]
    case Expression.TypeApplication(function, argumentType) =>
      infer(function).flatMap { typedFunction =>
        typedFunction.inferredType.universalApplicationView match {
          case ApplicativeView.Applicable(UniversalApplicationView(typeParameter, disjointBound, bodyType)) =>
            for {
              expandedArgument <- expandType(argumentType)
              disjoint <- context.areDisjoint(expandedArgument, disjointBound)
              result <- if (disjoint) {
                surfaceType(expandedArgument).map { translatedArgument =>
                  ElaboratedExpression(
                    FiobsExpression.TypeApplication(typedFunction.expression, translatedArgument),
                    bodyType.substituteVariables(Map(typeParameter -> expandedArgument))
                  )
                }
              } else {
                Result.Err(CpElaborationError.TypeArgumentViolatesBound(
                  expandedArgument,
                  disjointBound
                ))
              }
            } yield result
          case ApplicativeView.Inert | ApplicativeView.Blocked =>
            Result.Err(CpElaborationError.ExpectedUniversal(function, typedFunction.inferredType))
        }
      }

    case Expression.Merge(left, right) => inferMerge(left, right)

    // Δ ; Γ ⊢ E ⇐ A ↝ e
    // ───────────────────────── E-Ann
    // Δ ; Γ ⊢ E : A ⇒ A ↝ (e : ⟦A⟧)
    case Expression.Annotation(inner, annotatedType) =>
      for {
        expandedType <- expandType(annotatedType)
        elaboratedInner <- check(inner, expandedType)
        translatedType <- surfaceType(expandedType)
      } yield ElaboratedExpression(
        FiobsExpression.Annotation(elaboratedInner, translatedType),
        expandedType
      )

    case Expression.Record(members) => inferRecord(members)

    // Δ ; Γ ⊢ E ⇒ {ℓ : A} ↝ e
    // ───────────────────────────────────── E-Proj
    // Δ ; Γ ⊢ E.ℓ ⇒ A ↝ (e.ℓ : ⟦A⟧)
    case Expression.Projection(record, label) =>
      infer(record).flatMap { typedRecord =>
        typedRecord.inferredType.recordFields.get(label) match {
          case None => Result.Err(CpElaborationError.MissingRecordField(
            record,
            label,
            typedRecord.inferredType
          ))
          case Some(fieldType) =>
            surfaceType(fieldType).map { translatedFieldType =>
              ElaboratedExpression(
                FiobsExpression.Annotation(
                  FiobsExpression.Projection(typedRecord.expression, label),
                  translatedFieldType
                ),
                fieldType
              )
            }
        }
      }

    case Expression.Let(name, declaredType, initializer, body) =>
      inferLet(name, declaredType, initializer, body)

    case Expression.RecursiveLet(name, declaredType, initializer, body) =>
      inferRecursiveLet(name, declaredType, initializer, body)

    case Expression.Open(record, body) => inferOpen(record, body)

    // Δ ; Γ ⊢ E ⇒ F ↝ e    F ▹ᵗ Trait[A, B]    B ≤ A
    // ─────────────────────────────────────────────────── E-New
    // Δ ; Γ ⊢ new E ⇒ B ↝ fix(self : ⟦B⟧). e self
    case Expression.New(traitExpression) =>
      infer(traitExpression).flatMap { typedTrait =>
        typedTrait.inferredType.traitComposition match {
          case Some(TraitComposition(requiredInterface, providedInterface)) =>
            context.isSubtype(providedInterface, requiredInterface).flatMap { requirementSatisfied =>
              if (!requirementSatisfied) {
                Result.Err(CpElaborationError.TraitRequirementNotSatisfied(
                  providedInterface,
                  requiredInterface
                ))
              } else {
                surfaceType(providedInterface).map { translatedProvided =>
                  val selfName = freshTermName("self")
                  ElaboratedExpression(
                    FiobsExpression.Fix(
                      selfName,
                      translatedProvided,
                      FiobsExpression.Application(
                        typedTrait.expression,
                        FiobsExpression.Variable(selfName)
                      )
                    ),
                    providedInterface
                  )
                }
              }
            }
          case None =>
            Result.Err(CpElaborationError.ExpectedTrait(traitExpression, typedTrait.inferredType))
        }
      }

    // Δ ; Γ ⊢ E₁ ⇒ F ↝ e₁    F ▹ᵗ Trait[A, B]    Δ ; Γ ⊢ E₂ ⇐ A ↝ e₂
    // ───────────────────────────────────────────────────────────────────── E-Forward
    // Δ ; Γ ⊢ E₁ ^ E₂ ⇒ B ↝ e₁ e₂
    case Expression.Forward(traitExpression, selfArgument) =>
      infer(traitExpression).flatMap { typedTrait =>
        typedTrait.inferredType.traitComposition match {
          case Some(TraitComposition(requiredInterface, providedInterface)) =>
            check(selfArgument, requiredInterface).map { elaboratedSelf =>
              ElaboratedExpression(
                FiobsExpression.Application(typedTrait.expression, elaboratedSelf),
                providedInterface
              )
            }
          case None =>
            Result.Err(CpElaborationError.ExpectedTrait(traitExpression, typedTrait.inferredType))
        }
      }

    case Expression.Trait(selfName, selfRequirement, providedInterface, inheritedTrait, body) =>
      inferTrait(selfName, selfRequirement, providedInterface, inheritedTrait, body)

    // operator : p₁ × p₂ ⇾ p
    // Δ ; Γ ⊢ E₁ ⇐ p₁ ↝ e₁    Δ ; Γ ⊢ E₂ ⇐ p₂ ↝ e₂
    // ───────────────────────────────────────────────── E-Primitive
    // Δ ; Γ ⊢ E₁ operator E₂ ⇒ p ↝ e₁ operator e₂
    case Expression.Binary(operator, left, right) =>
      inferPrimitiveOperation(expression, operator, left, right)

    // Δ ; Γ ⊢ E₁ ⇐ Bool ↝ e₁
    // Δ ; Γ ⊢ E₂ ⇒ A ↝ e₂    Δ ; Γ ⊢ E₃ ⇐ A ↝ e₃
    // ─────────────────────────────────────────────── E-If
    // Δ ; Γ ⊢ if E₁ then E₂ else E₃ ⇒ A
    //   ↝ if e₁ then e₂ else e₃
    case Expression.If(condition, whenTrue, whenFalse) =>
      check(condition, Type.Boolean).flatMap { elaboratedCondition =>
        infer(whenTrue).flatMap { typedTrue =>
          check(whenFalse, typedTrue.inferredType).map { elaboratedFalse =>
            ElaboratedExpression(
              FiobsExpression.If(elaboratedCondition, typedTrue.expression, elaboratedFalse),
              typedTrue.inferredType
            )
          }
        }
      }
  }

  def check(
    expression: Expression,
    expectedType: Type
  ): Result[FiobsExpression, CpElaborationError] = expression match {
    case Expression.Located(inner, sourceSpan) => check(inner, expectedType).mapError(_.at(sourceSpan))

    // Δ ; Γ, x : A ⊢ E ⇐ B ↝ e
    // ───────────────────────────────── E-Abs
    // Δ ; Γ ⊢ λx. E ⇐ A ⇾ B ↝ λx. e
    case Expression.Lambda(parameter, body) => expectedType match {
      case Type.Arrow(expectedParameter, expectedResult) =>
        expandType(parameter.parameterType).flatMap { actualParameter =>
          if (actualParameter != expectedParameter) {
            Result.Err(CpElaborationError.LambdaParameterMismatch(
              actualParameter,
              expectedParameter
            ))
          } else {
            CpElaborator(context.withLocalTerm(parameter.name, expectedParameter))
              .check(body, expectedResult)
              .map(FiobsExpression.Lambda(parameter.name, _))
          }
        }
      case _ => Result.Err(CpElaborationError.TypeMismatch(expression, expectedType, expectedType))
    }

    case Expression.TypeLambda(binder, body) => expectedType match {
      case Type.ForAll(expectedParameter, expectedBound, expectedBody) =>
        expandType(binder.disjointBound).flatMap { actualBound =>
          if (actualBound != expectedBound) {
            Result.Err(CpElaborationError.TypeLambdaBoundMismatch(actualBound, expectedBound))
          } else {
            val actualParameter = binder.name
            val alignedExpectedBody = expectedBody.substituteVariables(Map(
              expectedParameter -> Type.Variable(actualParameter)
            ))
            context.withType(TypeBinder(actualParameter, expectedBound)).flatMap { extendedContext =>
              CpElaborator(extendedContext).check(body, alignedExpectedBody).flatMap { elaboratedBody =>
                surfaceType(expectedBound).map { translatedBound =>
                  FiobsExpression.TypeLambda(actualParameter, translatedBound, elaboratedBody)
                }
              }
            }
          }
        }
      case _ => Result.Err(CpElaborationError.TypeMismatch(expression, expectedType, expectedType))
    }

    case Expression.If(condition, whenTrue, whenFalse) =>
      for {
        elaboratedCondition <- check(condition, Type.Boolean)
        elaboratedTrue <- check(whenTrue, expectedType)
        elaboratedFalse <- check(whenFalse, expectedType)
      } yield FiobsExpression.If(elaboratedCondition, elaboratedTrue, elaboratedFalse)

    // Δ ; Γ ⊢ E ⇒ B ↝ e    B ≤ A
    // ───────────────────────────── E-Sub
    // Δ ; Γ ⊢ E ⇐ A ↝ e
    case _ => infer(expression).flatMap { typedExpression =>
      context.isSubtype(typedExpression.inferredType, expectedType).flatMap { subtype =>
        if (subtype) {
          Result.Ok(typedExpression.expression)
        } else {
          Result.Err(CpElaborationError.TypeMismatch(
            expression,
            typedExpression.inferredType,
            expectedType
          ))
        }
      }
    }
  }

  private def inferMerge(
    left: Expression,
    right: Expression
  ): Result[ElaboratedExpression, CpElaborationError] = {
    // Δ ; Γ ⊢ E₁ ⇒ A ↝ e₁    Δ ; Γ ⊢ E₂ ⇒ B ↝ e₂    Δ ⊢ A ∗ B
    // ───────────────────────────────────────────────────────────── E-Merge
    // Δ ; Γ ⊢ E₁ ,, E₂ ⇒ A ∧ B ↝ e₁ ,, e₂
    //
    // When both synthesized types are traits, E-MergeTrait below is the
    // applicable rule and both functions receive the same self value.
    for {
      typedLeft <- infer(left)
      typedRight <- infer(right)
      elaborated <- (typedLeft.inferredType, typedRight.inferredType) match {
        case (
          Type.Trait(leftRequirement, leftProvided),
          Type.Trait(rightRequirement, rightProvided)
        ) => inferTraitMerge(
          typedLeft,
          typedRight,
          leftRequirement,
          leftProvided,
          rightRequirement,
          rightProvided
        )
        case _ =>
          context.areDisjoint(typedLeft.inferredType, typedRight.inferredType).flatMap { disjoint =>
            if (disjoint) {
              Result.Ok(ElaboratedExpression(
                FiobsExpression.Merge(typedLeft.expression, typedRight.expression),
                Type.Intersection(typedLeft.inferredType, typedRight.inferredType)
              ))
            } else {
              Result.Err(CpElaborationError.TypesAreNotDisjoint(
                typedLeft.inferredType,
                typedRight.inferredType
              ))
            }
          }
      }
    } yield elaborated
  }

  private def inferTraitMerge(
    typedLeft: ElaboratedExpression,
    typedRight: ElaboratedExpression,
    leftRequirement: Type,
    leftProvided: Type,
    rightRequirement: Type,
    rightProvided: Type
  ): Result[ElaboratedExpression, CpElaborationError] = {
    // Δ ; Γ ⊢ E₁ ⇒ Trait[A₁, B₁] ↝ e₁
    // Δ ; Γ ⊢ E₂ ⇒ Trait[A₂, B₂] ↝ e₂    Δ ⊢ B₁ ∗ B₂
    // ─────────────────────────────────────────────────────────── E-MergeTrait
    // Δ ; Γ ⊢ E₁ ,, E₂ ⇒ Trait[A₁ ∧ A₂, B₁ ∧ B₂]
    //   ↝ ((λself. e₁ self ,, e₂ self) : ⟦A₁ ∧ A₂⟧ ⇾ ⟦B₁ ∧ B₂⟧)
    context.areDisjoint(leftProvided, rightProvided).flatMap { disjoint =>
      if (!disjoint) {
        Result.Err(CpElaborationError.TypesAreNotDisjoint(leftProvided, rightProvided))
      } else {
        val combinedRequirement = Type.Intersection(leftRequirement, rightRequirement)
        val combinedProvided = Type.Intersection(leftProvided, rightProvided)
        val selfName = freshTermName("self")
        for {
          translatedFunctionType <- surfaceType(Type.Arrow(combinedRequirement, combinedProvided))
        } yield ElaboratedExpression(
          FiobsExpression.Annotation(
            FiobsExpression.Lambda(
              selfName,
              FiobsExpression.Merge(
                FiobsExpression.Application(
                  typedLeft.expression,
                  FiobsExpression.Variable(selfName)
                ),
                FiobsExpression.Application(
                  typedRight.expression,
                  FiobsExpression.Variable(selfName)
                )
              )
            ),
            translatedFunctionType
          ),
          Type.Trait(combinedRequirement, combinedProvided)
        )
      }
    }
  }

  private def inferRecord(
    members: List[Member]
  ): Result[ElaboratedExpression, CpElaborationError] = {
    // Δ ; Γ ⊢ E ⇒ A ↝ e
    // ─────────────────────────── E-Rcd
    // Δ ; Γ ⊢ {ℓ = E} ⇒ {ℓ : A} ↝ {ℓ = e}
    //
    // {M₁, …, Mₙ} ↝ {M₁} ,, … ,, {Mₙ}
    // Multi-member records are core normalization followed by E-Merge.
    if (members.isEmpty) {
      Result.Ok(ElaboratedExpression(FiobsExpression.Top, Type.Top))
    } else {
      Result.traverse(members)(normalizeMember).flatMap { normalizedMembers =>
        Result.traverse(normalizedMembers) {
          case Member.Field(label, value) => infer(value).map { typedValue =>
            ElaboratedExpression(
              FiobsExpression.Record(label, typedValue.expression),
              Type.Record(label, typedValue.inferredType)
            )
          }
          case method: Member.MethodPattern =>
            Result.Err(CpElaborationError.CannotInfer(Expression.Record(List(method))))
        }.flatMap { elaboratedMembers =>
          elaboratedMembers.tail.foldLeft(
            Result.Ok(elaboratedMembers.head): Result[ElaboratedExpression, CpElaborationError]
          ) { (accumulated, nextMember) =>
            accumulated.flatMap { current =>
              context.areDisjoint(current.inferredType, nextMember.inferredType).flatMap { disjoint =>
                if (disjoint) {
                  Result.Ok(ElaboratedExpression(
                    FiobsExpression.Merge(current.expression, nextMember.expression),
                    Type.Intersection(current.inferredType, nextMember.inferredType)
                  ))
                } else {
                  Result.Err(CpElaborationError.TypesAreNotDisjoint(
                    current.inferredType,
                    nextMember.inferredType
                  ))
                }
              }
            }
          }
        }
      }
    }
  }

  private def normalizeMember(member: Member): Result[Member, CpElaborationError] = member match {
    case field: Member.Field => Result.Ok(field)
    // (L x̄ [self : B]).ℓ = E
    // ───────────────────────────────────────────────────────────── Desugar-Method
    // L = λx̄. trait [self : B] implements ⊤ inherits top ⇒ {ℓ = E}
    //
    // methodSelf fresh
    // (L x̄).ℓ = E
    // ─────────────────────────────────────────────────────────────────── Desugar-Method-Outer-Self
    // L = λx̄. trait [methodSelf : ⊤] implements ⊤ inherits top ⇒ {ℓ = E}
    case Member.MethodPattern(
          constructorName,
          constructorParameters,
          selfRequirement,
          label,
          valueParameters,
          body
        ) =>
      resolvePatternParameters(constructorName, constructorParameters).map { resolvedParameters =>
        val methodValue = Expression.curriedLambda(valueParameters, body)
        // Without an explicit node-self clause, the enclosing trait's `self`
        // remains in scope beneath the generated constructor trait.
        val methodSelfName = selfRequirement.fold(freshTermName("$methodSelf"))(_ => "self")
        val methodTrait = Expression.Trait(
          methodSelfName,
          selfRequirement.getOrElse(Type.Top),
          Type.Top,
          Expression.Top,
          Expression.Record(List(Member.Field(label, methodValue)))
        )
        Member.Field(
          constructorName,
          Expression.curriedLambda(resolvedParameters, methodTrait)
        )
      }
  }

  private def resolvePatternParameters(
    constructorName: String,
    parameters: List[PatternParameter]
  ): Result[List[ValueParameter], CpElaborationError] = {
    val inferredParameterTypes = context.lookupPatternConstructor(constructorName)
      .orElse(context.resolveTerm(NameReference.Unqualified(constructorName)).toOption.map {
        case ResolvedTermReference.Local(_, referenceType) => referenceType
        case ResolvedTermReference.Global(_, referenceType) => referenceType
      })
      .map(leadingArrowParameters(_, parameters.length))
      .getOrElse(Nil)

    Result.traverse(parameters.zipWithIndex) { case (parameter, index) =>
      parameter.declaredType.orElse(inferredParameterTypes.lift(index)) match {
        case Some(parameterType) =>
          expandType(parameterType).map(ValueParameter(parameter.name, _))
        case None => Result.Err(CpElaborationError.PatternParameterTypeUnavailable(
          constructorName,
          parameter.name
        ))
      }
    }
  }

  private def leadingArrowParameters(inputType: Type, maximum: Int): List[Type] = {
    if (maximum == 0) {
      Nil
    } else {
      inputType match {
        case Type.Arrow(parameterType, resultType) =>
          parameterType :: leadingArrowParameters(resultType, maximum - 1)
        case _ => Nil
      }
    }
  }

  private def inferLet(
    name: String,
    declaredType: Option[Type],
    initializer: Expression,
    body: Expression
  ): Result[ElaboratedExpression, CpElaborationError] = {
    // Δ ; Γ ⊢ E₁ ⇐ A ↝ e₁    Δ ; Γ, x : A ⊢ E₂ ⇒ B ↝ e₂
    // ─────────────────────────────────────────────────────── E-Let
    // Δ ; Γ ⊢ let x : A = E₁ in E₂ ⇒ B
    //   ↝ ((λx. e₂) : ⟦A⟧ ⇾ ⟦B⟧) e₁
    declaredType match {
      case Some(inputType) =>
        expandType(inputType).flatMap { bindingType =>
          for {
            elaboratedInitializer <- check(initializer, bindingType)
            typedBody <- CpElaborator(context.withLocalTerm(name, bindingType)).infer(body)
            elaboratedBinding <- bindingExpression(
              name,
              bindingType,
              elaboratedInitializer,
              typedBody.expression,
              typedBody.inferredType
            )
          } yield ElaboratedExpression(
            elaboratedBinding,
            typedBody.inferredType
          )
        }
      case None =>
        // Δ ; Γ ⊢ E₁ ⇒ A ↝ e₁    Δ ; Γ, x : A ⊢ E₂ ⇒ B ↝ e₂
        // ─────────────────────────────────────────────────── E-Let-Infer
        // Δ ; Γ ⊢ let x = E₁ in E₂ ⇒ B
        //   ↝ ((λx. e₂) : ⟦A⟧ ⇾ ⟦B⟧) e₁
        infer(initializer).flatMap { typedInitializer =>
          for {
            typedBody <- CpElaborator(context.withLocalTerm(name, typedInitializer.inferredType)).infer(body)
            elaboratedBinding <- bindingExpression(
              name,
              typedInitializer.inferredType,
              typedInitializer.expression,
              typedBody.expression,
              typedBody.inferredType
            )
          } yield ElaboratedExpression(
            elaboratedBinding,
            typedBody.inferredType
          )
        }
    }
  }

  private def inferRecursiveLet(
    name: String,
    declaredType: Type,
    initializer: Expression,
    body: Expression
  ): Result[ElaboratedExpression, CpElaborationError] = {
    // Δ ; Γ, x : A ⊢ E₁ ⇐ A ↝ e₁    Δ ; Γ, x : A ⊢ E₂ ⇒ B ↝ e₂
    // ─────────────────────────────────────────────────────────────── E-LetRec
    // Δ ; Γ ⊢ let rec x : A = E₁ in E₂ ⇒ B
    //   ↝ ((λx. e₂) : ⟦A⟧ ⇾ ⟦B⟧) (fix(x : ⟦A⟧). e₁)
    expandType(declaredType).flatMap { bindingType =>
      val recursiveElaborator = CpElaborator(context.withLocalTerm(name, bindingType))
      for {
        elaboratedInitializer <- recursiveElaborator.check(initializer, bindingType)
        typedBody <- recursiveElaborator.infer(body)
        elaboratedBinding <- recursiveBindingExpression(
          name,
          bindingType,
          elaboratedInitializer,
          typedBody.expression,
          typedBody.inferredType
        )
      } yield ElaboratedExpression(elaboratedBinding, typedBody.inferredType)
    }
  }

  private def inferOpen(
    record: Expression,
    body: Expression
  ): Result[ElaboratedExpression, CpElaborationError] = {
    // Δ ; Γ ⊢ E₁ ⇒ {ℓᵢ : Aᵢ}ⁱ∈ᴵ ↝ e₁
    // Δ ; Γ, ℓ̄ᵢ : Aᵢ ⊢ E₂ ⇒ B ↝ e₂
    // ───────────────────────────────────────────────────────── E-Open
    // Δ ; Γ ⊢ open E₁ in E₂ ⇒ B
    //   ↝ openTerm_⟦B⟧(e₁; ℓ̄ᵢ : ⟦Aᵢ⟧; e₂)
    infer(record).flatMap { typedRecord =>
      val fields = typedRecord.inferredType.recordFields.toList.sortBy(_._1)
      if (fields.isEmpty && typedRecord.inferredType != Type.Top) {
        Result.Err(CpElaborationError.ExpectedRecord(record, typedRecord.inferredType))
      } else {
        val recordName = freshTermName("openedRecord")
        val bodyContext = context.withLocalTerms(fields)
        CpElaborator(bodyContext).infer(body).flatMap { typedBody =>
          val fieldBindings = fields.foldRight(
            Result.Ok(typedBody.expression): Result[FiobsExpression, CpElaborationError]
          ) { case ((label, fieldType), accumulatedBody) =>
            accumulatedBody.flatMap { currentBody =>
              for {
                translatedFieldType <- surfaceType(fieldType)
                binding <- bindingExpression(
                  label,
                  fieldType,
                  FiobsExpression.Annotation(
                    FiobsExpression.Projection(FiobsExpression.Variable(recordName), label),
                    translatedFieldType
                  ),
                  currentBody,
                  typedBody.inferredType
                )
              } yield binding
            }
          }
          fieldBindings.flatMap { bindings =>
            bindingExpression(
              recordName,
              typedRecord.inferredType,
              typedRecord.expression,
              bindings,
              typedBody.inferredType
            ).map(ElaboratedExpression(_, typedBody.inferredType))
          }
        }
      }
    }
  }

  private def inferTrait(
    selfName: String,
    selfRequirement: Type,
    declaredProvided: Type,
    inheritedTrait: Expression,
    body: Expression
  ): Result[ElaboratedExpression, CpElaborationError] = {
    inheritedTrait match {
      case Expression.Top => inferParentlessTrait(selfName, selfRequirement, declaredProvided, body)
      case Expression.Located(Expression.Top, _) =>
        inferParentlessTrait(selfName, selfRequirement, declaredProvided, body)
      case parent => inferInheritedTrait(selfName, selfRequirement, declaredProvided, parent, body)
    }
  }

  private def inferParentlessTrait(
    selfName: String,
    selfRequirement: Type,
    declaredProvided: Type,
    body: Expression
  ): Result[ElaboratedExpression, CpElaborationError] = {
    // Δ ; Γ, self : A ⊢ open self in E ⇒ C ↝ e    C <: B
    // ───────────────────────────────────────────────────── E-Trait-Empty
    // Δ ; Γ ⊢ trait [self : A] implements B ⇒ E
    //   ⇒ Trait[A, C] ↝ ((λself. e) : ⟦A⟧ ⇾ ⟦C⟧)
    for {
      expandedSelfRequirement <- expandType(selfRequirement)
      expandedDeclaredProvided <- expandType(declaredProvided)
      selfContext = context.withLocalTerm(selfName, expandedSelfRequirement)
      bodyContext = selfContext.withPatternConstructors(expandedDeclaredProvided)
      bodyExpression = openKnownSelfFields(selfName, expandedSelfRequirement, body)
      typedBody <- CpElaborator(bodyContext).infer(bodyExpression)
      implementsDeclared <- context.isSubtype(typedBody.inferredType, expandedDeclaredProvided)
      checkedImplementation <- if (implementsDeclared) {
        Result.Ok(())
      } else {
        Result.Err(CpElaborationError.TraitImplementationMismatch(
          typedBody.inferredType,
          expandedDeclaredProvided
        ))
      }
      translatedFunctionType <- surfaceType(Type.Arrow(
        expandedSelfRequirement,
        typedBody.inferredType
      ))
    } yield ElaboratedExpression(
      FiobsExpression.Annotation(
        FiobsExpression.Lambda(selfName, typedBody.expression),
        translatedFunctionType
      ),
      Type.Trait(expandedSelfRequirement, typedBody.inferredType)
    )
  }

  private def inferInheritedTrait(
    selfName: String,
    selfRequirement: Type,
    declaredProvided: Type,
    inheritedTrait: Expression,
    body: Expression
  ): Result[ElaboratedExpression, CpElaborationError] = {
    // Δ ; Γ, self : A₁ ⊢ₚ E₁ ⇒ Trait[A₂, B₂] ↝ e₁    A₁ ≤ A₂
    // Δ ; Γ, self : A₁, super : B₂ ⊢ E₂ ⇒ C ↝ e₂
    // Δ ⊢ C ∗ B₂    C ∧ B₂ ≤ B₁
    // ─────────────────────────────────────────────────────────────── E-Trait
    // Δ ; Γ ⊢ trait [self : A₁] implements B₁ inherits E₁ ⇒ E₂
    //   ⇒ Trait[A₁, C ∧ B₂]
    //   ↝ ((λself. bind super = e₁ self in e₂ ,, super)
    //       : ⟦A₁⟧ ⇾ ⟦C ∧ B₂⟧)
    for {
      expandedSelfRequirement <- expandType(selfRequirement)
      expandedDeclaredProvided <- expandType(declaredProvided)
      selfContext = context.withLocalTerm(selfName, expandedSelfRequirement)
      typedParent <- inferParent(inheritedTrait, selfContext)
      result <- typedParent.inferredType match {
        case Type.Trait(parentRequirement, parentProvided) =>
          for {
            requirementSubtype <- context.isSubtype(expandedSelfRequirement, parentRequirement)
            checkedRequirement <- if (requirementSubtype) {
              Result.Ok(())
            } else {
              Result.Err(CpElaborationError.TraitRequirementNotSatisfied(
                expandedSelfRequirement,
                parentRequirement
              ))
            }
            // trait [self : A] implements B inherits E₁ ⇒ E₂
            // ───────────────────────────────────────────────── Desugar-Trait-Body
            // trait [self : A] implements B inherits E₁
            //   ⇒ (open self in E₂)
            bodyExpression = openKnownSelfFields(selfName, expandedSelfRequirement, body)
            bodyContext = selfContext
              .withLocalTerm("super", parentProvided)
              .withPatternConstructors(expandedDeclaredProvided)
            typedBody <- CpElaborator(bodyContext).infer(bodyExpression)
            disjoint <- context.areDisjoint(typedBody.inferredType, parentProvided)
            checkedDisjoint <- if (disjoint) {
              Result.Ok(())
            } else {
              Result.Err(CpElaborationError.TypesAreNotDisjoint(
                typedBody.inferredType,
                parentProvided
              ))
            }
            completeProvided = Type.Intersection(typedBody.inferredType, parentProvided)
            implementsDeclared <- context.isSubtype(completeProvided, expandedDeclaredProvided)
            checkedImplementation <- if (implementsDeclared) {
              Result.Ok(())
            } else {
              Result.Err(CpElaborationError.TraitImplementationMismatch(
                completeProvided,
                expandedDeclaredProvided
              ))
            }
            parentApplication = FiobsExpression.Application(
              typedParent.expression,
              FiobsExpression.Variable(selfName)
            )
            mergedBody = FiobsExpression.Merge(
              typedBody.expression,
              FiobsExpression.Variable("super")
            )
            boundBody <- bindingExpression(
              "super",
              parentProvided,
              parentApplication,
              mergedBody,
              completeProvided
            )
            translatedFunctionType <- surfaceType(Type.Arrow(
              expandedSelfRequirement,
              completeProvided
            ))
          } yield ElaboratedExpression(
            FiobsExpression.Annotation(
              FiobsExpression.Lambda(selfName, boundBody),
              translatedFunctionType
            ),
            Type.Trait(expandedSelfRequirement, completeProvided)
          )
        case actualType =>
          Result.Err(CpElaborationError.ExpectedTrait(inheritedTrait, actualType))
      }
    } yield result
  }

  private def openKnownSelfFields(
    selfName: String,
    selfRequirement: Type,
    body: Expression
  ): Expression = {
    // An abstract self remains available explicitly even though it has no
    // statically enumerable fields to introduce as unqualified bindings.
    if (selfRequirement.recordFields.isEmpty) {
      body
    } else {
      Expression.Open(Expression.variable(selfName), body)
    }
  }

  private def inferParent(
    inheritedTrait: Expression,
    parentContext: ElaborationContext
  ): Result[ElaboratedExpression, CpElaborationError] = {
    // E ≢ top    Δ ; Γ ⊢ E ⇒ Trait[A, B] ↝ e
    // ───────────────────────────────────────── E-Parent
    // Δ ; Γ ⊢ₚ E ⇒ Trait[A, B] ↝ e
    CpElaborator(parentContext).infer(inheritedTrait).flatMap { typedParent =>
      typedParent.inferredType match {
        case Type.Trait(_, _) => Result.Ok(typedParent)
        case actualType => Result.Err(CpElaborationError.ExpectedTrait(inheritedTrait, actualType))
      }
    }
  }

  private def inferPrimitiveOperation(
    completeExpression: Expression,
    operator: BinaryOperator,
    left: Expression,
    right: Expression
  ): Result[ElaboratedExpression, CpElaborationError] = {
    val successfulSignature = operator.signatures.iterator.map { signature =>
      val leftType = Type.Primitive(signature.leftArgument)
      val rightType = Type.Primitive(signature.rightArgument)
      for {
        elaboratedLeft <- check(left, leftType)
        elaboratedRight <- check(right, rightType)
      } yield ElaboratedExpression(
        FiobsExpression.Binary(operator, elaboratedLeft, elaboratedRight),
        Type.Primitive(signature.result)
      )
    }.collectFirst { case result @ Result.Ok(_) => result }

    successfulSignature.getOrElse(Result.Err(
      CpElaborationError.PrimitiveSignatureNotFound(operator, completeExpression)
    ))
  }

  private def expandType(inputType: Type): Result[Type, CpElaborationError] = {
    TypeExpansion.expand(
      inputType,
      context.typeExpansion,
      boundVariables = context.typeScope.toSet
    )
      .mapError(CpElaborationError.TypeExpansion(_))
  }

  private def surfaceType(inputType: Type): Result[SurfaceType, CpElaborationError] = {
    TypeTranslation.toSurfaceType(inputType)
      .mapError(CpElaborationError.TypeTranslation(_))
  }

  private def bindingExpression(
    name: String,
    bindingType: Type,
    initializer: FiobsExpression,
    body: FiobsExpression,
    bodyType: Type
  ): Result[FiobsExpression, CpElaborationError] = {
    ElaboratedTermBinding(name, initializer, bindingType, TermBindingRecursion.Ordinary)
      .scopeOver(ElaboratedExpression(body, bodyType))
      .map(_.expression)
  }

  private def recursiveBindingExpression(
    name: String,
    bindingType: Type,
    initializer: FiobsExpression,
    body: FiobsExpression,
    bodyType: Type
  ): Result[FiobsExpression, CpElaborationError] = {
    ElaboratedTermBinding(name, initializer, bindingType, TermBindingRecursion.Recursive)
      .scopeOver(ElaboratedExpression(body, bodyType))
      .map(_.expression)
  }

  private def freshTermName(baseName: String): String = {
    if (!context.termScope.contains(baseName)) {
      baseName
    } else {
      Iterator.from(1).map(index => s"$baseName$index")
        .find(name => !context.termScope.contains(name))
        .getOrElse(throw new IllegalStateException("the infinite fresh-name stream was exhausted"))
    }
  }
}

object CpElaborator {
  private[elaboration] def apply(context: ElaborationContext): CpElaborator = {
    new CpElaborator(context)
  }

  def elaborate(
    module: Module,
    namespace: Namespace,
    importedHeaders: Map[Namespace, ElaboratedModuleHeader]
  ): Result[ElaboratedModule, CpElaborationError] = {
    validateUniqueDefinitions(module).flatMap { _ =>
      ModuleScope.create(module, namespace, importedHeaders)
        .mapError(CpElaborationError.NameResolution(_))
        .flatMap { moduleScope =>
          val initialTypeExpansion = TypeExpansionContext(
            moduleScope,
            moduleScope.importedTypeDefinitions
          )
          val initialContext = ElaborationContext(
            moduleScope,
            initialTypeExpansion,
            Nil,
            TypeContext.empty,
            Nil,
            Map.empty,
            moduleScope.importedTermSignatures,
            Nil
          )
          elaborateTypeDefinitions(module, namespace, initialContext).flatMap {
            case (contextWithTypes, typeDefinitions) =>
              preRegisterAnnotatedTerms(module, namespace, contextWithTypes).flatMap { termContext =>
                elaborateTermDefinitions(module, namespace, termContext).flatMap {
                  case (termDefinitions, finalContext) =>
                    elaborateTopLevelMethods(module, namespace, finalContext, termDefinitions).map {
                      case (completeDefinitions, _) =>
                        val exportedSignatures = completeDefinitions.collect {
                          case (identifier, definition)
                              if definition.visibility == ModuleDefinitionVisibility.Exported =>
                            identifier -> definition.definitionType
                        }
                        ElaboratedModule(
                          ElaboratedModuleHeader(
                            namespace,
                            typeDefinitions,
                            exportedSignatures,
                            module.imports.map(_.targetNamespace).toSet
                          ),
                          completeDefinitions
                        )
                    }
                }
              }
          }
        }
    }
  }

  private def validateUniqueDefinitions(module: Module): Result[Unit, CpElaborationError] = {
    module.duplicateTermDefinitionNames.toList.sorted match {
      case duplicateName :: _ => Result.Err(CpElaborationError.DuplicateTermDefinition(duplicateName))
      case Nil => module.duplicateTypeDefinitionNames.toList.sorted match {
        case duplicateName :: _ => Result.Err(CpElaborationError.DuplicateTypeDefinition(duplicateName))
        case Nil => Result.Ok(())
      }
    }
  }

  private def elaborateTypeDefinitions(
    module: Module,
    namespace: Namespace,
    initialContext: ElaborationContext
  ): Result[(ElaborationContext, Map[Identifier, SignatureDefinition]), CpElaborationError] = {
    orderedTypeDefinitions(module, namespace, initialContext.moduleScope).flatMap { definitions =>
      val initialResult: Result[
        (ElaborationContext, Map[Identifier, SignatureDefinition]),
        CpElaborationError
      ] = Result.Ok(initialContext -> Map.empty)
      definitions.foldLeft(initialResult) { (accumulated, declaration) =>
        accumulated.flatMap { case (context, registeredDefinitions) =>
          val identifier = namespace.identifier(declaration.name)
          // β̄ fresh
          // Δ ; ᾱ ↦ β̄ ⊢ A ⇒ A₁    Δ ; ᾱ ↦ β̄ ⊢ B ⇒ B₁    ᾱ ↦ β̄ ⊢⁺false B₁ ⇒ B₂
          // ───────────────────────────────────────────────────────────────── E-TypeDecl
          // Δ ⊢ type X⟨ᾱ⟩ extends A = B ⇒ Δ, X⟨ᾱ, β̄⟩ ↦ A₁ ∧ B₂
          TypeExpansion.registerSignature(declaration, identifier, context.typeExpansion)
            .mapError(CpElaborationError.TypeExpansion(_))
            .map { expandedContext =>
              val definition = expandedContext.lookup(identifier).getOrElse {
                throw new IllegalStateException(s"registered type definition is unavailable: $identifier")
              }
              context.withTypeExpansion(expandedContext) -> registeredDefinitions.updated(identifier, definition)
            }
        }
      }
    }
  }

  private def orderedTypeDefinitions(
    module: Module,
    namespace: Namespace,
    moduleScope: ModuleScope
  ): Result[List[Declaration.TypeSignature], CpElaborationError] = {
    val definitions = module.definitions.collect { case declaration: Declaration.TypeSignature => declaration }
    val definitionsByIdentifier = definitions.map { declaration =>
      namespace.identifier(declaration.name) -> declaration
    }.toMap
    val initialGraph = Graph.directed[Identifier].addVertices(definitionsByIdentifier.keys)

    definitions.foldLeft(
      Result.Ok(initialGraph): Result[Graph[Identifier], CpElaborationError]
    ) { (accumulatedGraph, declaration) =>
      accumulatedGraph.flatMap { graph =>
        val identifier = namespace.identifier(declaration.name)
        val boundVariables = declaration.sortParameters.toSet
        val references =
          declaration.requiredInterface.referencedNames(boundVariables) ++
            declaration.providedInterface.referencedNames(boundVariables)
        Result.traverse(references.toList.sortBy(_.render)) { reference =>
          moduleScope.resolveType(reference).mapError(CpElaborationError.NameResolution(_))
        }.map { referencedIdentifiers =>
          referencedIdentifiers.filter(_.scope == namespace).foldLeft(graph) {
            case (currentGraph, dependency) => currentGraph.addEdge(dependency, identifier)
          }
        }
      }
    }.flatMap { graph =>
      val components = graph.stronglyConnectedComponents.toList
      components.find(component => component.size > 1 || component.exists(graph.isSelfLoop)) match {
        case Some(component) =>
          Result.Err(CpElaborationError.RecursiveTypeDefinitions(component.toList.sortBy(_.render)))
        case None => Result.Ok(components.flatMap(_.toList).map(definitionsByIdentifier))
      }
    }
  }

  private def preRegisterAnnotatedTerms(
    module: Module,
    namespace: Namespace,
    context: ElaborationContext
  ): Result[ElaborationContext, CpElaborationError] = {
    Result.traverse(module.definitions.collect { case definition: Declaration.Term => definition }) {
      definition => annotatedDeclarationType(definition.initializer) match {
        case Some(declaredType) =>
          CpElaborator(context).expandType(declaredType)
            .map(bindingType => Some(namespace.identifier(definition.name) -> bindingType))
        case None => Result.Ok(None)
      }
    }.map { optionalBindings =>
      context.withGlobalTerms(optionalBindings.flatten)
    }
  }

  private def elaborateTermDefinitions(
    module: Module,
    namespace: Namespace,
    initialContext: ElaborationContext
  ): Result[(Map[Identifier, ElaboratedTermDefinition], ElaborationContext), CpElaborationError] = {
    val mutualRecursionPlan = MutualRecursionPlan(module, namespace)
    val initialResult: Result[
      (Map[Identifier, ElaboratedTermDefinition], ElaborationContext),
      CpElaborationError
    ] = Result.Ok(Map.empty -> initialContext)
    module.termDefinitionComponents(namespace).foldLeft(initialResult) { (accumulated, component) =>
      accumulated.flatMap { case (definitions, context) =>
        if (component.isMutuallyRecursive) {
          elaborateMutualTermDefinitions(
            component,
            mutualRecursionPlan.generatedNamesFor(component),
            namespace,
            context
          ).map { case (componentDefinitions, nextContext) =>
            (definitions ++ componentDefinitions.map(definition => definition.identifier -> definition)) -> nextContext
          }
        } else {
          val definition = component.definitions.head
          elaborateTermDefinition(definition.name, definition.initializer, namespace, context).map {
            case (elaboratedDefinition, nextContext) =>
              definitions.updated(elaboratedDefinition.identifier, elaboratedDefinition) -> nextContext
          }
        }
      }
    }
  }

  private def elaborateMutualTermDefinitions(
    component: TermDefinitionComponent,
    generatedNames: GeneratedMutualNames,
    namespace: Namespace,
    context: ElaborationContext
  ): Result[(List[ElaboratedTermDefinition], ElaborationContext), CpElaborationError] = {
    // C = {xᵢ : Aᵢ = Eᵢ}ⁱ∈ᴵ    |I| > 1    R = ⋀ᵢ{xᵢ : Aᵢ}
    // Δ ; Γ, x̄ᵢ : Aᵢ ⊢ Eᵢ ⇐ Aᵢ ↝ eᵢ    eᵢ′ = eᵢ[xⱼ ↦ (s.xⱼ : ⟦Aⱼ⟧)]ʲ∈ᴵ
    // ───────────────────────────────────────────────────────────────────── E-TermDecl-Mutual
    // Δ ; Γ ⊢ C ⇒ m : R = fix(s : ⟦R⟧). {x̄ᵢ = eᵢ′}ⁱ∈ᴵ ; x̄ᵢ : Aᵢ = (m.xᵢ : ⟦Aᵢ⟧)ⁱ∈ᴵ
    Result.traverse(component.definitions) { definition =>
      annotatedDeclarationType(definition.initializer) match {
        case Some(declaredType) =>
          CpElaborator(context).expandType(declaredType).map(definition -> _)
        case None => Result.Err(CpElaborationError.RecursiveDeclarationRequiresType(definition.name))
      }
    }.flatMap { typedDefinitions =>
      val componentBindings = typedDefinitions.map { case (definition, bindingType) =>
        definition.name -> bindingType
      }
      val componentIdentifiers = componentBindings.map { case (name, bindingType) =>
        (namespace.identifier(name), name, bindingType)
      }
      val componentContext = context.withLocalTerms(componentBindings)
        .withLocalGlobalTermAliases(componentIdentifiers)
      val mutualRecordType = Type.records(componentBindings.head, componentBindings.tail)

      Result.traverse(componentBindings) { case (definitionName, bindingType) =>
        TypeTranslation.toSurfaceType(bindingType)
          .mapError(CpElaborationError.TypeTranslation(_))
          .map(translatedType => (definitionName, bindingType, translatedType))
      }.flatMap { translatedBindings =>
        val translatedTypesByName = translatedBindings.map { case (definitionName, _, translatedType) =>
          definitionName -> translatedType
        }.toMap
        val componentReferences = component.definitionNames.map { definitionName =>
          definitionName -> FiobsExpression.Annotation(
            FiobsExpression.Projection(
              FiobsExpression.Variable(generatedNames.selfName),
              definitionName
            ),
            translatedTypesByName(definitionName)
          )
        }.toMap

        Result.traverse(typedDefinitions) { case (definition, bindingType) =>
          CpElaborator(componentContext).check(definition.initializer, bindingType).map { initializer =>
            (definition.name, bindingType, initializer.substituteFreeTermVariables(componentReferences))
          }
        }.flatMap { elaboratedMembers =>
          TypeTranslation.toSurfaceType(mutualRecordType)
            .mapError(CpElaborationError.TypeTranslation(_))
            .map { translatedRecordType =>
              val accessorDefinitions = elaboratedMembers.map { case (definitionName, bindingType, _) =>
                ElaboratedTermDefinition(
                  namespace.identifier(definitionName),
                  FiobsExpression.Annotation(
                    FiobsExpression.Projection(
                      FiobsExpression.Global(namespace.identifier(generatedNames.definitionName)),
                      definitionName
                    ),
                    translatedTypesByName(definitionName)
                  ),
                  bindingType,
                  ModuleDefinitionVisibility.Exported
                )
              }
              val recordInitializer = elaboratedMembers.map { case (definitionName, _, initializer) =>
                FiobsExpression.Record(definitionName, initializer)
              }.reduceLeft(FiobsExpression.Merge(_, _))
              val mutualDefinition = ElaboratedTermDefinition(
                namespace.identifier(generatedNames.definitionName),
                FiobsExpression.Fix(generatedNames.selfName, translatedRecordType, recordInitializer),
                mutualRecordType,
                ModuleDefinitionVisibility.Internal
              )
              val globalBindings = componentBindings.map { case (name, bindingType) =>
                namespace.identifier(name) -> bindingType
              } :+ (namespace.identifier(generatedNames.definitionName) -> mutualRecordType)
              (mutualDefinition :: accessorDefinitions) -> context.withGlobalTerms(globalBindings)
            }
        }
      }
    }
  }

  private def elaborateTopLevelMethods(
    module: Module,
    namespace: Namespace,
    initialContext: ElaborationContext,
    initialDefinitions: Map[Identifier, ElaboratedTermDefinition]
  ): Result[(Map[Identifier, ElaboratedTermDefinition], ElaborationContext), CpElaborationError] = {
    val initialResult: Result[
      (Map[Identifier, ElaboratedTermDefinition], ElaborationContext),
      CpElaborationError
    ] = Result.Ok(initialDefinitions -> initialContext)
    module.definitions.collect { case Declaration.Method(member) => member }
      .foldLeft(initialResult) { (accumulated, member) =>
        accumulated.flatMap { case (definitions, context) =>
          CpElaborator(context).normalizeMember(member).flatMap {
            case Member.Field(name, initializer) =>
              elaborateTermDefinition(name, initializer, namespace, context).map {
                case (definition, nextContext) =>
                  definitions.updated(definition.identifier, definition) -> nextContext
              }
            case method: Member.MethodPattern =>
              Result.Err(CpElaborationError.CannotInfer(Expression.Record(List(method))))
          }
        }
      }
  }

  private def elaborateTermDefinition(
    name: String,
    initializer: Expression,
    namespace: Namespace,
    context: ElaborationContext
  ): Result[(ElaboratedTermDefinition, ElaborationContext), CpElaborationError] = {
    val identifier = namespace.identifier(name)
    val selfReferences = Set(
      NameReference.Unqualified(name),
      NameReference.Qualified(identifier)
    )
    val isRecursive = initializer.freeTermVariables.exists(selfReferences.contains)
    annotatedDeclarationType(initializer) match {
      // Ω, g : A ; Δ ; x : A ⊢ E ⇐ A ↝ e    r = fix(x : ⟦A⟧). e if g ∈ fv(E), otherwise e
      // ───────────────────────────────────────────────────────────────────────────────── E-TermDecl
      // Ω ; Δ ⊢ def g : A = E ⇒ Ω, g : A = r
      case Some(declaredType) =>
        CpElaborator(context).expandType(declaredType).flatMap { bindingType =>
          val definitionContext = if (isRecursive) {
            context.withLocalTerm(name, bindingType)
              .withLocalGlobalTermAliases(List((identifier, name, bindingType)))
          } else {
            context.withGlobalTerm(identifier, bindingType)
          }
          CpElaborator(definitionContext).check(initializer, bindingType).flatMap { elaboratedInitializer =>
            TypeTranslation.toSurfaceType(bindingType)
              .mapError(CpElaborationError.TypeTranslation(_))
              .map { translatedType =>
                val completeInitializer = if (isRecursive) {
                  FiobsExpression.Fix(name, translatedType, elaboratedInitializer)
                } else {
                  elaboratedInitializer
                }
                ElaboratedTermDefinition(
                  identifier,
                  completeInitializer,
                  bindingType,
                  ModuleDefinitionVisibility.Exported
                ) -> context.withGlobalTerm(identifier, bindingType)
              }
          }
        }

      case None if isRecursive =>
        Result.Err(CpElaborationError.RecursiveDeclarationRequiresType(name))

      // Ω ; Δ ; Γ ⊢ E ⇒ A ↝ e
      // ─────────────────────────── E-TermDecl-Infer
      // Ω ; Δ ⊢ def g = E ⇒ Ω, g : A = e
      case None =>
        CpElaborator(context).infer(initializer).map { typedInitializer =>
          val definition = ElaboratedTermDefinition(
            identifier,
            typedInitializer.expression,
            typedInitializer.inferredType,
            ModuleDefinitionVisibility.Exported
          )
          definition -> context.withGlobalTerm(identifier, typedInitializer.inferredType)
        }
    }
  }

  private def annotatedDeclarationType(initializer: Expression): Option[Type] = initializer match {
    case Expression.Located(inner, _) => annotatedDeclarationType(inner)
    case Expression.Annotation(_, declaredType) => Some(declaredType)
    case Expression.TypeLambda(binder, body) =>
      annotatedDeclarationType(body).map { bodyType =>
        Type.ForAll(binder.name, binder.disjointBound, bodyType)
      }
    case _ => None
  }
}
