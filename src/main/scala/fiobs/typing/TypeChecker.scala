package cp.fiobs.typing

import cp.fiobs.*
import cp.fiobs.runtime.RuntimeTerm
import cp.naming.Identifier
import cp.primitive.{BinaryOperator, PrimitiveSignature}
import cp.util.Result

final case class TypedTerm(runtimeTerm: RuntimeTerm, inferredType: Type)

enum PrimitiveOperand {
  case Left, Right
}

final case class PrimitiveCandidateFailure(
  signature: PrimitiveSignature,
  operand: PrimitiveOperand,
  cause: TypeError
)

enum TypeError {
  case UnboundTermVariable(index: Int)
  case UnboundGlobal(identifier: Identifier)
  case IllFormedType(inputType: Type)
  case CannotInfer(term: Term)
  case CheckingShapeMismatch(term: Term, expectedType: Type)
  case TypeMismatch(term: Term, actualType: Type, expectedType: Type)
  case NotApplicable(term: Term, actualType: Type, applicableForm: ApplicableForm)
  case TypesAreNotDisjoint(leftType: Type, rightType: Type)
  case TypeArgumentViolatesBound(argumentType: Type, disjointBound: Type)
  case TypeLambdaBoundMismatch(actualBound: Type, expectedBound: Type)
  case NoPrimitiveSignature(operator: BinaryOperator, term: Term, candidates: List[PrimitiveCandidateFailure])
}

final case class TypingContext(
  globalTypes: Map[Identifier, Type],
  typeContext: TypeContext,
  termTypes: List[Type]
) {
  def lookupGlobal(identifier: Identifier): Option[Type] = globalTypes.get(identifier)

  def lookupTerm(index: Int): Option[Type] = termTypes.lift(index)

  // Δ ⊢ Γ ✔ᵉ    Δ ⊢ A ✔
  // ───────────────────── WF-Ctx-Bind
  // Δ ⊢ Γ, x : A ✔ᵉ
  def withTerm(parameterType: Type): TypingContext = copy(termTypes = parameterType :: termTypes)

  // ⊢ Δ ✔    Δ ⊢ A ✔
  // ─────────────────── WF-TCtx-Bind
  // ⊢ Δ, α ∗ A ✔
  //
  // Extending Δ shifts every type in Γ beneath the new type binder.
  def withType(disjointBound: Type): TypingContext = TypingContext(
    globalTypes,
    typeContext.extend(disjointBound),
    termTypes.map(_.shiftTypeVariables(1))
  )
}

object TypingContext {
  // ⊢ ∅ᵀ ✔
  // ─────────── WF-Ctx-Empty
  // ∅ᵀ ⊢ ∅ˣ ✔ᵉ
  val empty: TypingContext = TypingContext(Map.empty, TypeContext.empty, Nil)

  def withGlobals(globalTypes: Map[Identifier, Type]): TypingContext = {
    TypingContext(globalTypes, TypeContext.empty, Nil)
  }
}

/**
 * Executable bidirectional surface typing and type-directed runtime decoration.
 * The checker is immutable: recursive rules derive a checker with an extended
 * context instead of mutating shared state.
 */
final class TypeChecker private (context: TypingContext) {
  private val disjointness = Disjointness(context.typeContext)

  def infer(term: Term): Result[TypedTerm, TypeError] = term match {
    // ⊢ Δ ✔    Δ ⊢ Γ ✔ᵉ    x : A ∈ Γ
    // ───────────────────────────────── T-Var / Dec-Var
    // Δ ; Γ ⊢ x ⇒ A ↝ x
    case Term.Variable(index) =>
      context.lookupTerm(index)
        .map(variableType => Result.Ok(TypedTerm(RuntimeTerm.Variable(index), variableType)))
        .getOrElse(Result.Err(TypeError.UnboundTermVariable(index)))

    // Ω(g) = A
    // ───────────────── T-Global / Dec-Global
    // Ω ; Δ ; Γ ⊢ global g ⇒ A ↝ global g
    case Term.Global(identifier) =>
      context.lookupGlobal(identifier)
        .map(globalType => Result.Ok(TypedTerm(RuntimeTerm.Global(identifier), globalType)))
        .getOrElse(Result.Err(TypeError.UnboundGlobal(identifier)))

    // ⊢ Δ ✔    Δ ⊢ Γ ✔ᵉ    value : p
    // ───────────────────────────────── T-Primitive / Dec-Primitive
    // Δ ; Γ ⊢ value ⇒ p ↝ value
    case Term.Literal(value) =>
      Result.Ok(TypedTerm(RuntimeTerm.Literal(value), Type.Primitive(value.primitiveType)))

    // ⊢ Δ ✔    Δ ⊢ Γ ✔ᵉ
    // ───────────────────── T-Top / Dec-Top
    // Δ ; Γ ⊢ top ⇒ ⊤ ↝ top
    case Term.Top => Result.Ok(TypedTerm(RuntimeTerm.Top, Type.Top))

    // T-Lam has only a checking-mode conclusion; its complete rule is in check.
    case Term.Lambda(_) => Result.Err(TypeError.CannotInfer(term))

    // Δ ⊢ Γ, x : A ✔ᵉ    Δ ; Γ, x : A ⊢ e ⇐ A ↝ r
    // ─────────────────────────────────────────────── T-Fix / Dec-Fix
    // Δ ; Γ ⊢ fix(x : A). e ⇒ A ↝ fix(x : A). r
    case Term.Fix(annotatedType, body) =>
      if (!annotatedType.isWellFormed(context.typeContext)) {
        Result.Err(TypeError.IllFormedType(annotatedType))
      } else {
        TypeChecker(context.withTerm(annotatedType)).check(body, annotatedType).map { runtimeBody =>
          TypedTerm(RuntimeTerm.Fix(annotatedType, runtimeBody), annotatedType)
        }
      }

    // Δ ; Γ ⊢ e₁ ⇒ F ↝ r₁    F ▹ᵃ A ⇾ B    Δ ; Γ ⊢ e₂ ⇐ A ↝ r₂
    // ───────────────────────────────────────────────────────────── T-App / Dec-App
    // Δ ; Γ ⊢ e₁ e₂ ⇒ B ↝ (r₁ : A ⇾ B) r₂
    case Term.Application(function, argument) =>
      infer(function).flatMap { typedFunction =>
        typedFunction.inferredType.applicativeView(ApplicableForm.Arrow) match {
          case Some(completeArrow @ Type.Arrow(parameterType, resultType)) =>
            check(argument, parameterType).map { runtimeArgument =>
              TypedTerm(
                RuntimeTerm.Application(
                  RuntimeTerm.Cast(typedFunction.runtimeTerm, completeArrow),
                  runtimeArgument
                ),
                resultType
              )
            }
          case _ => Result.Err(TypeError.NotApplicable(
            function,
            typedFunction.inferredType,
            ApplicableForm.Arrow
          ))
        }
      }

    // Δ ; Γ ⊢ e₁ ⇒ A ↝ r₁    Δ ; Γ ⊢ e₂ ⇒ B ↝ r₂    Δ ⊢ A ∗ B
    // ─────────────────────────────────────────────────────────── T-Merge / Dec-Merge
    // Δ ; Γ ⊢ e₁ ,, e₂ ⇒ A & B ↝ r₁ ,, r₂
    case Term.Merge(left, right) =>
      infer(left).flatMap { typedLeft =>
        infer(right).flatMap { typedRight =>
          if (disjointness.relates(typedLeft.inferredType, typedRight.inferredType)) {
            Result.Ok(TypedTerm(
              RuntimeTerm.Merge(typedLeft.runtimeTerm, typedRight.runtimeTerm),
              Type.Intersection(typedLeft.inferredType, typedRight.inferredType)
            ))
          } else {
            Result.Err(TypeError.TypesAreNotDisjoint(
              typedLeft.inferredType,
              typedRight.inferredType
            ))
          }
        }
      }

    // Δ ; Γ ⊢ e ⇐ A ↝ r
    // ───────────────────── T-Ann / Dec-Ann
    // Δ ; Γ ⊢ (e : A) ⇒ A ↝ r
    case Term.Annotation(inner, annotatedType) =>
      if (!annotatedType.isWellFormed(context.typeContext)) {
        Result.Err(TypeError.IllFormedType(annotatedType))
      } else {
        check(inner, annotatedType).map(TypedTerm(_, annotatedType))
      }

    // T-TLam has only a checking-mode conclusion; its complete rule is in check.
    case Term.TypeLambda(_, _) => Result.Err(TypeError.CannotInfer(term))

    // Δ ; Γ ⊢ e ⇒ F ↝ r    F ▹ᵘ ∀(α ∗ A). B    Δ ⊢ C ✔    Δ ⊢ C ∗ A
    // ──────────────────────────────────────────────────────────────── T-TApp / Dec-TApp
    // Δ ; Γ ⊢ e[C] ⇒ B[α ↦ C] ↝ (r : ∀(α ∗ A). B)[C]
    case Term.TypeApplication(function, argumentType) =>
      infer(function).flatMap { typedFunction =>
        typedFunction.inferredType.applicativeView(ApplicableForm.Universal) match {
          case Some(completeUniversal @ Type.ForAll(disjointBound, bodyType)) =>
            if (!argumentType.isWellFormed(context.typeContext)) {
              Result.Err(TypeError.IllFormedType(argumentType))
            } else if (!disjointness.relates(argumentType, disjointBound)) {
              Result.Err(TypeError.TypeArgumentViolatesBound(argumentType, disjointBound))
            } else {
              Result.Ok(TypedTerm(
                RuntimeTerm.TypeApplication(
                  RuntimeTerm.Cast(typedFunction.runtimeTerm, completeUniversal),
                  argumentType
                ),
                bodyType.substituteType(0, argumentType)
              ))
            }
          case _ => Result.Err(TypeError.NotApplicable(
            function,
            typedFunction.inferredType,
            ApplicableForm.Universal
          ))
        }
      }

    // Δ ; Γ ⊢ e ⇒ A ↝ r
    // ─────────────────────────────── T-Rcd / Dec-Rcd
    // Δ ; Γ ⊢ {ℓ = e} ⇒ {ℓ : A} ↝ ⟨{ℓ = r}⟩^{ℓ : A}
    case Term.Record(label, field) =>
      infer(field).map { typedField =>
        TypedTerm(
          RuntimeTerm.Record(label, typedField.runtimeTerm, typedField.inferredType),
          Type.Record(label, typedField.inferredType)
        )
      }

    // T-Proj has only a checking-mode conclusion; its complete rule is in check.
    case Term.Projection(_, _) => Result.Err(TypeError.CannotInfer(term))

    // operator : p₁ × p₂ → p₃
    // Δ ; Γ ⊢ e₁ ⇐ p₁ ↝ r₁    Δ ; Γ ⊢ e₂ ⇐ p₂ ↝ r₂
    // ───────────────────────────────────────────────── T-PrimitiveOp / Dec-PrimitiveOp
    // Δ ; Γ ⊢ e₁ operator e₂ ⇒ p₃ ↝ r₁ operator r₂
    case Term.Binary(operator, left, right) => inferPrimitiveOperation(term, operator, left, right)

    // Δ ; Γ ⊢ c ⇐ Bool ↝ r₁    Δ ; Γ ⊢ e₁ ⇒ A ↝ r₂    Δ ; Γ ⊢ e₂ ⇐ A ↝ r₃
    // ───────────────────────────────────────────────────────────────────── T-If / Dec-If
    // Δ ; Γ ⊢ if c then e₁ else e₂ ⇒ A ↝ if r₁ then r₂ else r₃
    case Term.If(condition, whenTrue, whenFalse) =>
      check(condition, Type.Boolean).flatMap { runtimeCondition =>
        infer(whenTrue).flatMap { typedTrueBranch =>
          check(whenFalse, typedTrueBranch.inferredType).map { runtimeFalseBranch =>
            TypedTerm(
              RuntimeTerm.If(runtimeCondition, typedTrueBranch.runtimeTerm, runtimeFalseBranch),
              typedTrueBranch.inferredType
            )
          }
        }
      }
  }

  def check(term: Term, expectedType: Type): Result[RuntimeTerm, TypeError] = {
    if (!expectedType.isWellFormed(context.typeContext)) {
      Result.Err(TypeError.IllFormedType(expectedType))
    } else {
      (term, expectedType) match {
        // Δ ⊢ Γ, x : A ✔ᵉ    Δ ; Γ, x : A ⊢ e ⇐ B ↝ r
        // ─────────────────────────────────────────────── T-Lam / Dec-Lam
        // Δ ; Γ ⊢ λx. e ⇐ A ⇾ B ↝ ⟨λx. r⟩^{A ⇾ B}
        case (Term.Lambda(body), Type.Arrow(parameterType, resultType)) =>
          TypeChecker(context.withTerm(parameterType)).check(body, resultType).map { runtimeBody =>
            RuntimeTerm.Lambda(parameterType, runtimeBody, resultType)
          }

        case (Term.Lambda(_), _) =>
          Result.Err(TypeError.CheckingShapeMismatch(term, expectedType))

        // Δ ⊢ Γ ✔ᵉ    Δ ⊢ A ✔    Δ, α ∗ A ; Γ ⊢ e ⇐ B ↝ r
        // ───────────────────────────────────────────────── T-TLam / Dec-TLam
        // Δ ; Γ ⊢ Λ(α ∗ A). e ⇐ ∀(α ∗ A). B ↝ ⟨Λ(α ∗ A). r⟩^{∀(α ∗ A).B}
        case (Term.TypeLambda(actualBound, body), Type.ForAll(expectedBound, resultType)) =>
          if (actualBound != expectedBound) {
            Result.Err(TypeError.TypeLambdaBoundMismatch(actualBound, expectedBound))
          } else if (!actualBound.isWellFormed(context.typeContext)) {
            Result.Err(TypeError.IllFormedType(actualBound))
          } else {
            TypeChecker(context.withType(actualBound)).check(body, resultType).map { runtimeBody =>
              RuntimeTerm.TypeLambda(actualBound, runtimeBody, resultType)
            }
          }

        case (Term.TypeLambda(_, _), _) =>
          Result.Err(TypeError.CheckingShapeMismatch(term, expectedType))

        // Δ ; Γ ⊢ e ⇐ {ℓ : A} ↝ r
        // ───────────────────────── T-Proj / Dec-Proj
        // Δ ; Γ ⊢ e.ℓ ⇐ A ↝ r.ℓ
        case (Term.Projection(record, label), _) =>
          check(record, Type.Record(label, expectedType)).map(RuntimeTerm.Projection(_, label))

        // Δ ; Γ ⊢ c ⇐ Bool ↝ r₁    Δ ; Γ ⊢ e₁ ⇐ A ↝ r₂    Δ ; Γ ⊢ e₂ ⇐ A ↝ r₃
        // ────────────────────────────────────────────────────────────────── T-If-Chk / Dec-If-Chk
        // Δ ; Γ ⊢ if c then e₁ else e₂ ⇐ A ↝ if r₁ then r₂ else r₃
        case (Term.If(condition, whenTrue, whenFalse), _) =>
          for {
            runtimeCondition <- check(condition, Type.Boolean)
            runtimeTrueBranch <- check(whenTrue, expectedType)
            runtimeFalseBranch <- check(whenFalse, expectedType)
          } yield RuntimeTerm.If(runtimeCondition, runtimeTrueBranch, runtimeFalseBranch)

        // Δ ; Γ ⊢ e ⇒ A ↝ r    Δ ⊢ B ✔    Δ ⊢ A <: B
        // ─────────────────────────────────────────── T-Sub / Dec-Sub
        // Δ ; Γ ⊢ e ⇐ B ↝ (r : B)
        case _ => infer(term).flatMap { typedTerm =>
          if (typedTerm.inferredType.isSubtypeOf(expectedType, context.typeContext)) {
            Result.Ok(RuntimeTerm.Cast(typedTerm.runtimeTerm, expectedType))
          } else {
            Result.Err(TypeError.TypeMismatch(term, typedTerm.inferredType, expectedType))
          }
        }
      }
    }
  }

  private def inferPrimitiveOperation(
    completeTerm: Term,
    operator: BinaryOperator,
    left: Term,
    right: Term
  ): Result[TypedTerm, TypeError] = {
    def select(
      signatures: List[PrimitiveSignature],
      failures: List[PrimitiveCandidateFailure]
    ): Result[TypedTerm, TypeError] = signatures match {
      case Nil => Result.Err(TypeError.NoPrimitiveSignature(operator, completeTerm, failures.reverse))
      case signature :: remaining =>
        val candidate = for {
          runtimeLeft <- check(left, Type.Primitive(signature.leftArgument))
            .mapError(PrimitiveCandidateFailure(signature, PrimitiveOperand.Left, _))
          runtimeRight <- check(right, Type.Primitive(signature.rightArgument))
            .mapError(PrimitiveCandidateFailure(signature, PrimitiveOperand.Right, _))
        } yield TypedTerm(
          RuntimeTerm.Binary(operator, runtimeLeft, runtimeRight),
          Type.Primitive(signature.result)
        )
        candidate match {
          case Result.Ok(typed) => Result.Ok(typed)
          case Result.Err(failure) => select(remaining, failure :: failures)
        }
    }
    select(operator.signatures, Nil)
  }
}

object TypeChecker {
  def apply(context: TypingContext = TypingContext.empty): TypeChecker = new TypeChecker(context)
}
