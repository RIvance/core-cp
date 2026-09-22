package cp.language.elaboration

import cp.fiobs.Term
import cp.fiobs.binding.Binding.*
import cp.language.core.*
import cp.language.typing.*
import cp.naming.NameReference
import cp.util.Result

private[elaboration] final case class ElaboratedMember(label: String, value: ElaboratedExpression)
private final case class ResolvedParameter(name: String, parameterType: Type)

/** Type-directed CP elimination. All generated terms and inferred types already have lexical identities. */
private[elaboration] final class ExpressionElaborator(context: ElaborationContext) {
  def infer(expression: Expression): ElaborationResult[ElaboratedExpression] = expression match {
    case Expression.Located(inner, sourceSpan) => infer(inner).mapError(_.at(sourceSpan))

    // ───────────────────────── E-Primitive       ───────────────── E-Top
    // Δ ; Γ ⊢ value ⇒ p ↝ value                    Δ ; Γ ⊢ top ⇒ ⊤ ↝ top
    case Expression.Literal(value) =>
      Result.Ok(ElaboratedExpression(Term.Literal(value), Type.Primitive(value.primitiveType)))
    case Expression.Top => Result.Ok(ElaboratedExpression(Term.Top, Type.Top))

    // x : A ∈ Γ                    Ω(g) = A
    // ───────────────── E-Var      ───────────────────────── E-Global
    // Δ ; Γ ⊢ x ⇒ A ↝ x            Ω ; Δ ; Γ ⊢ g ⇒ A ↝ global g
    case Expression.Variable(reference) => context.resolveTerm(reference)

    // Δ ; Γ, x : A ⊢ E ⇒ B ↝ e
    // ───────────────────────────────────────── E-Abs-Synth
    // Δ ; Γ ⊢ λ(x : A). E ⇒ A ⇾ B ↝ (λx. e : ⟦A⟧ ⇾ ⟦B⟧)
    case Expression.Lambda(parameter, body) =>
      context.expand(parameter.parameterType).flatMap { parameterType =>
        ExpressionElaborator(context.withTerm(parameter.name, parameterType)).infer(body)
          .map(abstractTerm(parameterType, _))
      }

    // Δ ; Γ ⊢ E₁ ⇒ F ↝ e₁    F ▹ᵃ A ⇾ B    Δ ; Γ ⊢ E₂ ⇐ A ↝ e₂
    // ───────────────────────────────────────────────────────────── E-App
    // Δ ; Γ ⊢ E₁ E₂ ⇒ B ↝ e₁ e₂
    case Expression.Application(function, argument) =>
      infer(function).flatMap { typedFunction =>
        typedFunction.inferredType.termApplicationView match {
          case ApplicativeView.Applicable(TermApplicationView(parameterType, resultType)) =>
            check(argument, parameterType).map { elaboratedArgument =>
              annotated(Term.Application(typedFunction.expression, elaboratedArgument), resultType)
            }
          case _ => fail(CpElaborationError.ExpectedFunction(function, typedFunction.inferredType))
        }
      }

    // Δ, α ∗ A ; Γ ⊢ E ⇒ B ↝ e
    // ───────────────────────────────────────────────────────── E-TAbs
    // Δ ; Γ ⊢ Λ(α ∗ A). E ⇒ ∀(α ∗ A). B ↝ (Λ(α ∗ ⟦A⟧). e : ∀(α ∗ ⟦A⟧). ⟦B⟧)
    case Expression.TypeLambda(binder, body) =>
      for {
        bound <- context.expand(binder.disjointBound)
        typedBody <- ExpressionElaborator(context.withType(binder.name, bound)).infer(body)
      } yield annotated(
        Term.TypeLambda(TypeTranslation.toFiobs(bound), typedBody.expression),
        Type.ForAll(bound, typedBody.inferredType)
      )

    // Δ ; Γ ⊢ E ⇒ F ↝ e    F ▹ᵘ ∀(α ∗ A). B    Δ ⊢ C ∗ A
    // ──────────────────────────────────────────────────────── E-TApp
    // Δ ; Γ ⊢ E@C ⇒ B[α ↦ C] ↝ e[⟦C⟧]
    case Expression.TypeApplication(function, argumentType) =>
      infer(function).flatMap { typedFunction =>
        typedFunction.inferredType.universalApplicationView match {
          case ApplicativeView.Applicable(UniversalApplicationView(bound, body)) =>
            context.expand(argumentType).flatMap { argument =>
              if (context.areDisjoint(argument, bound)) {
                Result.Ok(annotated(
                  Term.TypeApplication(typedFunction.expression, TypeTranslation.toFiobs(argument)),
                  body.instantiate(List(argument))
                ))
              } else fail(CpElaborationError.TypeArgumentViolatesBound(argument, bound))
            }
          case _ => fail(CpElaborationError.ExpectedUniversal(function, typedFunction.inferredType))
        }
      }

    // R = μ α. A    Δ ; Γ ⊢ E ⇐ A[α ↦ R] ↝ e
    // ─────────────────────────────────────────── E-Fold
    // Δ ; Γ ⊢ fold[R] E ⇒ R ↝ fold[⟦R⟧] e
    case Expression.Fold(recursiveType, body) =>
      context.expand(recursiveType).flatMap { expandedType =>
        expandedType.unfolded match {
          case Some(bodyType) => check(body, bodyType).map { elaboratedBody =>
            ElaboratedExpression(Term.Fold(TypeTranslation.toFiobs(expandedType), elaboratedBody), expandedType)
          }
          case None => fail(CpElaborationError.ExpectedRecursiveType(expandedType))
        }
      }

    // R = μ α. A    Δ ; Γ ⊢ E ⇐ R ↝ e
    // ─────────────────────────────────────────────────── E-Unfold
    // Δ ; Γ ⊢ unfold[R] E ⇒ A[α ↦ R] ↝ unfold[⟦R⟧] e
    case Expression.Unfold(recursiveType, inner) =>
      context.expand(recursiveType).flatMap { expandedType =>
        expandedType.unfolded match {
          case Some(bodyType) => check(inner, expandedType).map { elaboratedInner =>
            ElaboratedExpression(Term.Unfold(TypeTranslation.toFiobs(expandedType), elaboratedInner), bodyType)
          }
          case None => fail(CpElaborationError.ExpectedRecursiveType(expandedType))
        }
      }

    case Expression.Merge(left, right) =>
      for {
        typedLeft <- infer(left)
        typedRight <- infer(right)
        merged <- (typedLeft.inferredType.traitComposition, typedRight.inferredType.traitComposition) match {
          case (Some(leftTrait), Some(rightTrait)) => mergeTraits(typedLeft, typedRight, leftTrait, rightTrait)
          case _ => merge(typedLeft, typedRight)
        }
      } yield merged

    // Δ ; Γ ⊢ E ⇐ A ↝ e
    // ───────────────────────── E-Ann
    // Δ ; Γ ⊢ E : A ⇒ A ↝ (e : ⟦A⟧)
    case Expression.Annotation(inner, annotatedType) =>
      for {
        expandedType <- context.expand(annotatedType)
        elaboratedInner <- check(inner, expandedType)
      } yield annotated(elaboratedInner, expandedType)

    // Δ ; Γ ⊢ Eᵢ ⇒ Aᵢ ↝ eᵢ    pairwise Δ ⊢ {ℓᵢ : Aᵢ} ∗ {ℓⱼ : Aⱼ}
    // ───────────────────────────────────────────────────────────── E-Rcd / E-Merge
    // Δ ; Γ ⊢ {ℓ̄ᵢ = Eᵢ} ⇒ ⋀ᵢ{ℓᵢ : Aᵢ} ↝ {ℓ₁ = e₁} ,, … ,, {ℓₙ = eₙ}
    case Expression.Record(members) =>
      Result.traverse(members)(inferMember).flatMap { fields =>
        val records = fields.map { case ElaboratedMember(label, value) =>
          ElaboratedExpression(Term.Record(label, value.expression), Type.Record(label, value.inferredType))
        }
        records match {
          case Nil => Result.Ok(ElaboratedExpression(Term.Top, Type.Top))
          case head :: tail => tail.foldLeft(Result.Ok(head): ElaborationResult[ElaboratedExpression]) {
            (accumulated, next) => accumulated.flatMap(merge(_, next))
          }
        }
      }

    // Δ ; Γ ⊢ E ⇒ {ℓ : A} ↝ e
    // ───────────────────────────────────── E-Proj
    // Δ ; Γ ⊢ E.ℓ ⇒ A ↝ (e.ℓ : ⟦A⟧)
    case Expression.Projection(record, label) =>
      infer(record).flatMap { typedRecord =>
        typedRecord.inferredType.recordFields.get(label) match {
          case Some(fieldType) => Result.Ok(annotated(Term.Projection(typedRecord.expression, label), fieldType))
          case None => fail(CpElaborationError.MissingRecordField(record, label, typedRecord.inferredType))
        }
      }

    // Δ ; Γ ⊢ E₁ ⇐ A ↝ e₁    Δ ; Γ, x : A ⊢ E₂ ⇒ B ↝ e₂
    // ─────────────────────────────────────────────────────── E-Let
    // Δ ; Γ ⊢ let x : A = E₁ in E₂ ⇒ B ↝ e₂[x ↦ (e₁ : ⟦A⟧)]
    // Without an annotation, the first premise synthesizes A.
    case Expression.Let(name, declaredType, initializer, body) =>
      inferInitializer(initializer, declaredType).flatMap { typedInitializer =>
        ExpressionElaborator(context.withValue(name, typedInitializer)).infer(body)
      }

    // Δ ; Γ, x : A ⊢ E₁ ⇐ A ↝ e₁    Δ ; Γ, x : A ⊢ E₂ ⇒ B ↝ e₂
    // ─────────────────────────────────────────────────────────────── E-LetRec
    // Δ ; Γ ⊢ let rec x : A = E₁ in E₂ ⇒ B
    //   ↝ e₂[x ↦ fix(x : ⟦A⟧). e₁]
    case Expression.RecursiveLet(name, declaredType, initializer, body) =>
      context.expand(declaredType).flatMap { bindingType =>
        val nested = ExpressionElaborator(context.withTerm(name, bindingType))
        for {
          elaboratedInitializer <- nested.check(initializer, bindingType)
          recursiveValue = ElaboratedExpression(
            Term.Fix(TypeTranslation.toFiobs(bindingType), elaboratedInitializer), bindingType
          )
          typedBody <- ExpressionElaborator(context.withValue(name, recursiveValue)).infer(body)
        } yield typedBody
      }

    // Δ ; Γ ⊢ E₁ ⇒ {ℓᵢ : Aᵢ}ⁱ∈ᴵ ↝ e₁    Δ ; Γ, ℓ̄ᵢ : Aᵢ ⊢ E₂ ⇒ B ↝ e₂
    // ─────────────────────────────────────────────────────────────────── E-Open
    // Δ ; Γ ⊢ open E₁ in E₂ ⇒ B ↝ e₂[ℓᵢ ↦ (e₁.ℓᵢ : ⟦Aᵢ⟧)]
    case Expression.Open(record, body) =>
      infer(record).flatMap { typedRecord =>
        val recordInterface = typedRecord.inferredType.normalized
        if (recordInterface.recordFields.isEmpty && recordInterface != Type.Top) {
          fail(CpElaborationError.ExpectedRecord(record, typedRecord.inferredType))
        } else {
          val bodyContext = context.withOpenedFields(typedRecord.expression, recordInterface)
          ExpressionElaborator(bodyContext).infer(body)
        }
      }

    // Δ ; Γ ⊢ E ⇒ F ↝ e    F ▹ᵗ Trait[A, B]    B ≤ A
    // ─────────────────────────────────────────────────── E-New
    // Δ ; Γ ⊢ new E ⇒ B ↝ fix(self : ⟦B⟧). e self
    case Expression.New(traitExpression) =>
      inferTraitValue(traitExpression).flatMap { case (typedTrait, composition) =>
        if (context.isSubtype(composition.providedInterface, composition.requiredInterface)) {
          Result.Ok(ElaboratedExpression(
            Term.Fix(
              TypeTranslation.toFiobs(composition.providedInterface),
              Term.Application(typedTrait.expression.shiftTermVariables(1), Term.Variable(0))
            ),
            composition.providedInterface
          ))
        } else fail(CpElaborationError.TraitRequirementNotSatisfied(
          composition.providedInterface, composition.requiredInterface
        ))
      }

    // Δ ; Γ ⊢ E₁ ⇒ F ↝ e₁    F ▹ᵗ Trait[A, B]    Δ ; Γ ⊢ E₂ ⇐ A ↝ e₂
    // ───────────────────────────────────────────────────────────────────── E-Forward
    // Δ ; Γ ⊢ E₁ ^ E₂ ⇒ B ↝ e₁ e₂
    case Expression.Forward(traitExpression, selfArgument) =>
      inferTraitValue(traitExpression).flatMap { case (typedTrait, composition) =>
        check(selfArgument, composition.requiredInterface).map { self =>
          annotated(Term.Application(typedTrait.expression, self), composition.providedInterface)
        }
      }

    case Expression.Trait(selfName, selfRequirement, providedInterface, inheritedTrait, body) =>
      for {
        required <- context.expand(selfRequirement)
        provided <- context.expand(providedInterface)
        result <- inferTrait(Some(selfName), required, provided, inheritedTrait, body)
      } yield result

    // Δ ; Γ ⊢ E₁ ⇒ A ↝ e₁    Δ ; Γ ⊢ E₂ ⇒ B ↝ e₂
    // operator : p₁ × p₂ ⇾ p    A ≤ p₁    B ≤ p₂
    // ─────────────────────────────────────────────────────────────────────── E-Primitive
    // Δ ; Γ ⊢ E₁ operator E₂ ⇒ p ↝ e₁ operator e₂
    case Expression.Binary(operator, left, right) =>
      for {
        typedLeft <- infer(left)
        typedRight <- infer(right)
        result <- operator.signatures.find { signature =>
          context.isSubtype(typedLeft.inferredType, Type.Primitive(signature.leftArgument)) &&
            context.isSubtype(typedRight.inferredType, Type.Primitive(signature.rightArgument))
        } match {
          case Some(signature) => Result.Ok(ElaboratedExpression(
            Term.Binary(operator, typedLeft.expression, typedRight.expression),
            Type.Primitive(signature.result)
          ))
          case None => fail(CpElaborationError.PrimitiveSignatureNotFound(operator, expression))
        }
      } yield result

    // Δ ; Γ ⊢ E₁ ⇐ Bool ↝ e₁    Δ ; Γ ⊢ E₂ ⇒ A ↝ e₂    Δ ; Γ ⊢ E₃ ⇐ A ↝ e₃
    // ───────────────────────────────────────────────────────────────────── E-If
    // Δ ; Γ ⊢ if E₁ then E₂ else E₃ ⇒ A ↝ if e₁ then e₂ else e₃
    case Expression.If(condition, whenTrue, whenFalse) =>
      for {
        elaboratedCondition <- check(condition, Type.Boolean)
        typedTrue <- infer(whenTrue)
        elaboratedFalse <- check(whenFalse, typedTrue.inferredType)
      } yield ElaboratedExpression(
        Term.If(elaboratedCondition, typedTrue.expression, elaboratedFalse), typedTrue.inferredType
      )
  }

  def check(expression: Expression, expectedType: Type): ElaborationResult[Term] = expression match {
    case Expression.Located(inner, sourceSpan) => check(inner, expectedType).mapError(_.at(sourceSpan))
    // Δ ; Γ, x : A ⊢ E ⇐ B ↝ e
    // ───────────────────────────────── E-Abs
    // Δ ; Γ ⊢ λ(x : A). E ⇐ A ⇾ B ↝ λx. e
    case Expression.Lambda(parameter, body) => expectedType match {
      case Type.Arrow(expectedParameter, expectedResult) =>
        context.expand(parameter.parameterType).flatMap { actualParameter =>
          if (actualParameter == expectedParameter) {
            ExpressionElaborator(context.withTerm(parameter.name, expectedParameter)).check(body, expectedResult)
              .map(Term.Lambda(_))
          } else fail(CpElaborationError.LambdaParameterMismatch(actualParameter, expectedParameter))
        }
      case _ => fail(CpElaborationError.CheckingShapeMismatch(expression, expectedType))
    }
    // Δ, α ∗ A ; Γ ⊢ E ⇐ B ↝ e
    // ─────────────────────────────────────────────── E-TAbs-Check
    // Δ ; Γ ⊢ Λ(α ∗ A). E ⇐ ∀(α ∗ A). B ↝ Λ(α ∗ ⟦A⟧). e
    case Expression.TypeLambda(binder, body) => expectedType match {
      case Type.ForAll(expectedBound, expectedBody) =>
        context.expand(binder.disjointBound).flatMap { actualBound =>
          if (actualBound == expectedBound) {
            ExpressionElaborator(context.withType(binder.name, expectedBound)).check(body, expectedBody)
              .map(Term.TypeLambda(TypeTranslation.toFiobs(expectedBound), _))
          } else fail(CpElaborationError.TypeLambdaBoundMismatch(actualBound, expectedBound))
        }
      case _ => fail(CpElaborationError.CheckingShapeMismatch(expression, expectedType))
    }
    // Δ ; Γ ⊢ E₁ ⇐ Bool ↝ e₁    Δ ; Γ ⊢ E₂ ⇐ A ↝ e₂    Δ ; Γ ⊢ E₃ ⇐ A ↝ e₃
    // ───────────────────────────────────────────────────────────────────── E-If-Check
    // Δ ; Γ ⊢ if E₁ then E₂ else E₃ ⇐ A ↝ if e₁ then e₂ else e₃
    case Expression.If(condition, whenTrue, whenFalse) =>
      for {
        elaboratedCondition <- check(condition, Type.Boolean)
        elaboratedTrue <- check(whenTrue, expectedType)
        elaboratedFalse <- check(whenFalse, expectedType)
      } yield Term.If(elaboratedCondition, elaboratedTrue, elaboratedFalse)
    // Δ ; Γ ⊢ E ⇒ B ↝ e    B ≤ A
    // ───────────────────────────── E-Sub
    // Δ ; Γ ⊢ E ⇐ A ↝ e
    case _ => infer(expression).flatMap { typedExpression =>
      if (context.isSubtype(typedExpression.inferredType, expectedType)) Result.Ok(typedExpression.expression)
      else fail(CpElaborationError.TypeMismatch(expression, typedExpression.inferredType, expectedType))
    }
  }

  def inferInitializer(
    expression: Expression,
    annotation: Option[TypeSyntax]
  ): ElaborationResult[ElaboratedExpression] = {
    annotation match {
      case None => infer(expression)
      case Some(inputType) => context.expand(inputType).flatMap { expectedType =>
        check(expression, expectedType).map(annotated(_, expectedType))
      }
    }
  }

  def inferMember(member: Member): ElaborationResult[ElaboratedMember] = member match {
    case Member.Field(label, value, annotation) =>
      inferInitializer(value, annotation).map(ElaboratedMember(label, _))
    // (L x̄ [self : B]).ℓ = E
    // ───────────────────────────────────────────────────────────── Desugar-Method
    // L = λx̄. trait [self : B] implements ⊤ ⇒ {ℓ = E}
    // Without a self clause the generated trait binder is anonymous, preserving outer self.
    case Member.MethodPattern(constructorName, parameters, selfRequirement, label, valueParameters, body) =>
      resolvePatternParameters(constructorName, parameters).flatMap { resolved =>
        inferConstructor(resolved, selfRequirement, Expression.Record(List(
          Member.Field(label, Expression.curriedLambda(valueParameters, body), None)
        ))).map(ElaboratedMember(constructorName, _))
      }
  }

  private def inferConstructor(
    parameters: List[ResolvedParameter],
    selfRequirement: Option[TypeSyntax],
    body: Expression
  ): ElaborationResult[ElaboratedExpression] = parameters match {
    case parameter :: remaining =>
      ExpressionElaborator(context.withTerm(parameter.name, parameter.parameterType))
        .inferConstructor(remaining, selfRequirement, body).map(abstractTerm(parameter.parameterType, _))
    case Nil =>
      context.expand(selfRequirement.getOrElse(TypeSyntax.Top)).flatMap { required =>
        inferTrait(selfRequirement.map(_ => "self"), required, Type.Top, None, body)
      }
  }

  private def resolvePatternParameters(
    constructorName: String,
    parameters: List[PatternParameter]
  ): ElaborationResult[List[ResolvedParameter]] = {
    def inferredConstructor: ElaborationResult[Option[Type]] = context.lookupPatternConstructor(constructorName) match {
      case Some(inputType) => Result.Ok(Some(inputType))
      case None => context.findTerm(NameReference.Unqualified(constructorName)).map(_.map(_.inferredType))
    }
    val constructor = if (parameters.forall(_.declaredType.nonEmpty)) Result.Ok(None) else inferredConstructor
    constructor.flatMap { inputType =>
      def leadingParameters(inputType: Type): List[Type] = inputType match {
        case Type.Arrow(parameter, result) => parameter :: leadingParameters(result)
        case _ => Nil
      }
      val inferred = inputType.toList.flatMap(leadingParameters)
      Result.traverse(parameters.zipWithIndex) { case (parameter, index) =>
        val parameterType = parameter.declaredType match {
          case Some(inputType) => context.expand(inputType)
          case None => inferred.lift(index) match {
            case Some(inputType) => Result.Ok(inputType)
            case None => fail(CpElaborationError.PatternParameterTypeUnavailable(constructorName, parameter.name))
          }
        }
        parameterType.map(ResolvedParameter(parameter.name, _))
      }
    }
  }

  private def inferTraitValue(expression: Expression): ElaborationResult[(ElaboratedExpression, TraitComposition)] = {
    expression match {
      case Expression.Located(inner, sourceSpan) => inferTraitValue(inner).mapError(_.at(sourceSpan))
      case _ => infer(expression).flatMap { value =>
        value.inferredType.traitComposition match {
          case Some(composition) => Result.Ok(value -> composition)
          case None => fail(CpElaborationError.ExpectedTrait(expression, value.inferredType))
        }
      }
    }
  }

  private def merge(
    left: ElaboratedExpression,
    right: ElaboratedExpression
  ): ElaborationResult[ElaboratedExpression] = {
    // Δ ; Γ ⊢ E₁ ⇒ A ↝ e₁    Δ ; Γ ⊢ E₂ ⇒ B ↝ e₂    Δ ⊢ A ∗ B
    // ───────────────────────────────────────────────────────────── E-Merge
    // Δ ; Γ ⊢ E₁ ,, E₂ ⇒ A ∧ B ↝ e₁ ,, e₂
    if (context.areDisjoint(left.inferredType, right.inferredType)) {
      Result.Ok(ElaboratedExpression(
        Term.Merge(left.expression, right.expression), Type.Intersection(left.inferredType, right.inferredType)
      ))
    } else fail(CpElaborationError.TypesAreNotDisjoint(left.inferredType, right.inferredType))
  }

  private def mergeTraits(
    left: ElaboratedExpression,
    right: ElaboratedExpression,
    leftTrait: TraitComposition,
    rightTrait: TraitComposition
  ): ElaborationResult[ElaboratedExpression] = {
    // Δ ; Γ ⊢ Eᵢ ⇒ Fᵢ ↝ eᵢ    Fᵢ ▹ᵗ Trait[Aᵢ, Bᵢ]    Δ ⊢ B₁ ∗ B₂
    // ─────────────────────────────────────────────────────────────── E-MergeTrait
    // Δ ; Γ ⊢ E₁ ,, E₂ ⇒ Trait[A₁ ∧ A₂, B₁ ∧ B₂]
    //   ↝ ((λself. e₁ self ,, e₂ self) : ⟦A₁ ∧ A₂⟧ ⇾ ⟦B₁ ∧ B₂⟧)
    if (context.areDisjoint(leftTrait.providedInterface, rightTrait.providedInterface)) {
      val mergedType = Type.Trait(
        Type.intersection(leftTrait.requiredInterface, rightTrait.requiredInterface),
        Type.intersection(leftTrait.providedInterface, rightTrait.providedInterface)
      )
      Result.Ok(annotated(Term.Lambda(Term.Merge(
        Term.Application(left.expression.shiftTermVariables(1), Term.Variable(0)),
        Term.Application(right.expression.shiftTermVariables(1), Term.Variable(0))
      )), mergedType))
    } else fail(CpElaborationError.TypesAreNotDisjoint(leftTrait.providedInterface, rightTrait.providedInterface))
  }

  private def inferTrait(
    selfName: Option[String],
    required: Type,
    declared: Type,
    inheritedTrait: Option[Expression],
    body: Expression
  ): ElaborationResult[ElaboratedExpression] = {
    val selfContext = context.withTerm(selfName, required).withPatternConstructors(declared)
    def checkProvided(value: ElaboratedExpression): ElaborationResult[ElaboratedExpression] = {
      if (context.isSubtype(value.inferredType, declared)) {
        val provided = value.inferredType.normalized
        Result.Ok(annotated(Term.Lambda(value.expression), Type.Trait(required, provided)))
      } else fail(CpElaborationError.TraitImplementationMismatch(value.inferredType, declared))
    }
    // Δ ; Γ, self : A, super : ⊤ ⊢ open self in E ⇒ C ↝ e
    // C ≤ B    P = N(C ∧ ⊤) ≃ C
    // ───────────────────────────────────────────────────────────────────── E-Trait-Base
    // Δ ; Γ ⊢ trait [self : A] implements B ⇒ E
    //   ⇒ Trait[A, P] ↝ ((λself. e[super ↦ top] ,, top) : ⟦A⟧ ⇾ ⟦P⟧)
    //
    // Δ ; Γ, self : A₁ ⊢ E₁ ⇒ F ↝ e₁    F ▹ᵗ Trait[A₂, B₂]    A₁ ≤ A₂
    // Δ ; Γ, self : A₁, super : B₂ ⊢ open self in E₂ ⇒ C ↝ e₂
    // Δ ⊢ C ∗ B₂    C ∧ B₂ ≤ B₁    P = N(C ∧ B₂) ≃ C ∧ B₂
    // ───────────────────────────────────────────────────────────────────── E-Trait-Inherits
    // Δ ; Γ ⊢ trait [self : A₁] implements B₁ inherits E₁ ⇒ E₂
    //   ⇒ Trait[A₁, P] ↝ ((λself. e₂[super ↦ e₁ self] ,, e₁ self) : ⟦A₁⟧ ⇾ ⟦P⟧)
    //
    // The base case uses C ∗ ⊤ and C ∧ ⊤ ≃ C; it still binds its own super.
    // An explicit parent always uses ordinary synthesis and trait elimination.
    // N removes exact-top intersection units at this existing introduction
    // annotation; E-Merge itself preserves both the term and its inferred intersection.
    val inherited = inheritedTrait match {
      case None => Result.Ok(ElaboratedExpression(Term.Top, Type.Top))
      case Some(expression) =>
        ExpressionElaborator(selfContext).inferTraitValue(expression).flatMap { case (parent, composition) =>
          if (context.isSubtype(required, composition.requiredInterface)) {
            Result.Ok(ElaboratedExpression(
              Term.Application(parent.expression, Term.Variable(0)), composition.providedInterface
            ))
          } else fail(CpElaborationError.TraitRequirementNotSatisfied(required, composition.requiredInterface))
        }
    }
    inherited.flatMap { parentValue =>
      val bodyContext = selfContext.withValue("super", parentValue).withOpenedFields(Term.Variable(0), required)
      ExpressionElaborator(bodyContext).infer(body).flatMap { typedBody =>
        merge(typedBody, parentValue).flatMap(checkProvided)
      }
    }
  }

  private def abstractTerm(parameterType: Type, body: ElaboratedExpression): ElaboratedExpression = {
    annotated(Term.Lambda(body.expression), Type.Arrow(parameterType, body.inferredType))
  }

  /** Re-establishes the synthesized interface after type normalization, including substitution of ⊤. */
  private def annotated(expression: Term, inputType: Type): ElaboratedExpression = {
    ElaboratedExpression(Term.Annotation(expression, TypeTranslation.toFiobs(inputType)), inputType)
  }

  private def fail[A](error: CpElaborationError): ElaborationResult[A] = {
    Result.Err(error)
  }
}
