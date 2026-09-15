package cp.fitrie.elaboration

import cp.fiobs.*
import cp.fiobs.runtime.RuntimeTerm
import cp.fiobs.typing.{TypeChecker, TypeError, TypingContext}
import cp.fitrie.*
import cp.naming.{FieldLabel, Identifier}
import cp.primitive.BinaryOperator
import cp.util.Result

final case class TypedFiTrie(trie: FiTrie, inferredType: Type)

enum FiTrieElaborationError {
  case Typing(error: TypeError)
  case Coercion(error: CoercionError)
  case Merge(error: FiTrieMergeError)
  case UnboundDecoratedTermVariable(index: Int)
  case UnboundDecoratedGlobal(identifier: Identifier)
  case ExpectedArrow(term: RuntimeTerm, actualType: Type)
  case ExpectedUniversal(term: RuntimeTerm, actualType: Type)
  case ExpectedRecord(term: RuntimeTerm, label: FieldLabel, actualType: Type)
  case UnexpectedType(term: RuntimeTerm, actualType: Type, expectedType: Type)
  case NoPrimitiveSignature(operator: BinaryOperator, leftType: Type, rightType: Type)
}

/**
 * Elaborates the existing checker's decorated Fiobs term into a type-erased
 * FiTrie. Reusing that decoration keeps one source typing relation while this
 * target remains isolated from the existing evaluator.
 */
object Elaborator {
  def infer(
    term: Term,
    globalTypes: Map[Identifier, Type] = Map.empty
  ): Result[TypedFiTrie, FiTrieElaborationError] = {
    val typingContext = TypingContext.withGlobals(globalTypes)
    TypeChecker(typingContext).infer(term)
      .mapError(FiTrieElaborationError.Typing(_))
      .flatMap { typedTerm =>
        translate(typedTerm.runtimeTerm, TranslationContext.withGlobals(globalTypes)).flatMap { translated =>
          requireType(translated, typedTerm.inferredType, typedTerm.runtimeTerm)
        }
      }
  }

  def check(
    term: Term,
    expectedType: Type,
    globalTypes: Map[Identifier, Type] = Map.empty
  ): Result[TypedFiTrie, FiTrieElaborationError] = {
    val typingContext = TypingContext.withGlobals(globalTypes)
    TypeChecker(typingContext).check(term, expectedType)
      .mapError(FiTrieElaborationError.Typing(_))
      .flatMap { runtimeTerm =>
        translate(runtimeTerm, TranslationContext.withGlobals(globalTypes)).flatMap { translated =>
          requireType(translated, expectedType, runtimeTerm)
        }
      }
  }

  private def translate(
    term: RuntimeTerm,
    context: TranslationContext
  ): Result[TypedFiTrie, FiTrieElaborationError] = term match {
    // x : A ∈ Γ
    // ───────────────────────────────── Elab-Var
    // Δ ; Γ ⊢ x ⇒ A ⇝ { x ; · ; · }
    case RuntimeTerm.Variable(index) =>
      context.lookupTerm(index) match {
        case Some(variableType) => Result.Ok(TypedFiTrie(
          FiTrie.response(ResponseComputation.LocalVariable(TermVariableIndex(index))),
          variableType
        ))
        case None => Result.Err(FiTrieElaborationError.UnboundDecoratedTermVariable(index))
      }

    // Ω(g) = A
    // ───────────────────────────────────────── Elab-Global
    // Ω ; Δ ; Γ ⊢ global g ⇒ A ⇝ { global g ; · ; · }
    case RuntimeTerm.Global(identifier) =>
      context.lookupGlobal(identifier) match {
        case Some(globalType) => Result.Ok(TypedFiTrie(
          FiTrie.response(ResponseComputation.Global(identifier)),
          globalType
        ))
        case None => Result.Err(FiTrieElaborationError.UnboundDecoratedGlobal(identifier))
      }

    // ───────────────────────────────── Elab-Primitive
    // Δ ; Γ ⊢ value ⇒ p ⇝ { · ; · ; p ↦ value }
    case RuntimeTerm.Literal(value) =>
      Result.Ok(TypedFiTrie(FiTrie.termination(value), Type.Primitive(value.primitiveType)))

    // ───────────────────────── Elab-Top
    // Δ ; Γ ⊢ top ⇒ ⊤ ⇝ { · ; · ; · }
    case RuntimeTerm.Top => Result.Ok(TypedFiTrie(FiTrie.empty, Type.Top))

    /*
     * Δ ; Γ, x : A ⊢ e ⇐ B ⇝ t    Δ ⊢ B ⇛ₖ 𝒦
     * ───────────────────────────────────────────────── Elab-Lam
     * Δ ; Γ ⊢ λx.e ⇐ A ⇾ B ⇝ {· ; appₓ ↦ {t ▷ 𝒦 ; · ; ·} ; ·}
     */
    case RuntimeTerm.Lambda(parameterType, body, resultType) =>
      translate(body, context.withTerm(parameterType)).flatMap { translatedBody =>
        requireType(translatedBody, resultType, body).map { checkedBody =>
          TypedFiTrie(
            FiTrie.route(
              RouteKey.Application,
              suspendedFilter(checkedBody.trie, RootKeyCompilation.compile(resultType))
            ),
            Type.Arrow(parameterType, resultType)
          )
        }
      }

    /*
     * Δ ; Γ, x : A ⊢ e ⇐ A ⇝ t    Δ ⊢ A ⇛ₚ 𝒜
     * Ξ ; 𝒜 ; x ; 0 ⊢ t ↓ u
     * ─────────────────────────────────────────── Elab-Fix
     * Δ ; Γ ⊢ fix(x : A).e ⇒ A ⇝ u
     */
    case RuntimeTerm.Fix(annotatedType, body) =>
      translate(body, context.withTerm(annotatedType)).flatMap { translatedBody =>
        requireType(translatedBody, annotatedType, body).map { checkedBody =>
          TypedFiTrie(
            checkedBody.trie.tieFixedPoint(
              TermVariableIndex(0),
              RootKeyCompilation.compile(annotatedType)
            ),
            annotatedType
          )
        }
      }

    /*
     * Δ ; Γ ⊢ e₁ ⇒ A ⇾ B ⇝ t₁    Δ ; Γ ⊢ e₂ ⇐ A ⇝ t₂
     * ───────────────────────────────────────────────── Elab-App
     * Δ ; Γ ⊢ e₁ e₂ ⇒ B ⇝ {t₁ ◁ ⟨app[t₂]⟩ ; · ; ·}
     */
    case RuntimeTerm.Application(function, argument) =>
      translate(function, context).flatMap { translatedFunction =>
        translatedFunction.inferredType match {
          case Type.Arrow(parameterType, resultType) =>
            translate(argument, context).flatMap { translatedArgument =>
              requireType(translatedArgument, parameterType, argument).map { checkedArgument =>
                TypedFiTrie(
                  FiTrie.response(ResponseComputation.Index(
                    translatedFunction.trie,
                    RequestSet.one(Request.Application(checkedArgument.trie))
                  )),
                  resultType
                )
              }
            }
          case actualType =>
            Result.Err(FiTrieElaborationError.ExpectedArrow(function, actualType))
        }
      }

    /*
     * Δ ; Γ ⊢ e₁ ⇒ A ⇝ t₁    Δ ; Γ ⊢ e₂ ⇒ B ⇝ t₂
     * Δ ⊢ A ∗ B    Δ ⊢ A ⇛ₚ 𝒜    Δ ⊢ B ⇛ₚ ℬ
     * Ξ ⊢ t₁ ▶[𝒜] u₁    Ξ ⊢ t₂ ▶[ℬ] u₂    u₁ ⊕ u₂ = u
     * ───────────────────────────────────────────────────────── Elab-Merge-Stable-Paths
     * Δ ; Γ ⊢ e₁ ,, e₂ ⇒ A & B ⇝ u
     */
    case RuntimeTerm.Merge(left, right) =>
      for {
        translatedLeft <- translate(left, context)
        translatedRight <- translate(right, context)
        merged <- FiTrie.merge(translatedLeft.trie, translatedRight.trie)
          .mapError(FiTrieElaborationError.Merge(_))
      } yield TypedFiTrie(
        merged,
        Type.Intersection(translatedLeft.inferredType, translatedRight.inferredType)
      )

    /*
     * Δ ; Γ ⊢ e ⇒ A ⇝ t    Δ ⊢ t ↦ᴬ<:ᴮ u
     * ───────────────────────────────────────────── Elab-Sub
     * Δ ; Γ ⊢ (e : B) ⇐ B ⇝ u
     */
    case RuntimeTerm.Cast(inner, targetType) =>
      translate(inner, context).flatMap { translatedInner =>
        Coercion.coerce(
          translatedInner.trie,
          translatedInner.inferredType,
          targetType,
          context.typeContext
        ).mapError(FiTrieElaborationError.Coercion(_)).map { coerced =>
          TypedFiTrie(coerced, targetType)
        }
      }

    /*
     * Δ, α ∗ A ; Γ ⊢ e ⇐ B ⇝ t    Δ, α ∗ A ⊢ B ⇛ₖ 𝒦
     * ──────────────────────────────────────────────────────── Elab-TLam-Paths
     * Δ ; Γ ⊢ Λ(α ∗ A).e ⇐ ∀(α ∗ A).B
     *   ⇝ {· ; tappα ↦ {t ▷ 𝒦 ; · ; ·} ; ·}
     */
    case RuntimeTerm.TypeLambda(disjointBound, body, resultType) =>
      translate(body, context.withType(disjointBound)).flatMap { translatedBody =>
        requireType(translatedBody, resultType, body).map { checkedBody =>
          TypedFiTrie(
            FiTrie.route(
              RouteKey.TypeApplication,
              suspendedFilter(checkedBody.trie, RootKeyCompilation.compile(resultType))
            ),
            Type.ForAll(disjointBound, resultType)
          )
        }
      }

    /*
     * Δ ; Γ ⊢ e ⇒ ∀(α ∗ A).B ⇝ t    Δ ⊢ D ⇛ₚ 𝒟
     * ─────────────────────────────────────────────────── Elab-TApp-Paths
     * Δ ; Γ ⊢ e[D] ⇒ B[α ↦ D] ⇝ {t ◁ ⟨tapp[𝒟]⟩ ; · ; ·}
     */
    case RuntimeTerm.TypeApplication(function, argumentType) =>
      translate(function, context).flatMap { translatedFunction =>
        translatedFunction.inferredType match {
          case Type.ForAll(_, bodyType) => Result.Ok(TypedFiTrie(
            FiTrie.response(ResponseComputation.Index(
              translatedFunction.trie,
              RequestSet.one(Request.TypeApplication(
                ObservationPathCompilation.compile(argumentType)
              ))
            )),
            bodyType.substituteType(0, argumentType)
          ))
          case actualType =>
            Result.Err(FiTrieElaborationError.ExpectedUniversal(function, actualType))
        }
      }

    /*
     * Δ ; Γ ⊢ e ⇒ A ⇝ t
     * ────────────────────────────────────── Elab-Rcd
     * Δ ; Γ ⊢ {ℓ = e} ⇒ {ℓ : A} ⇝ { · ; projℓ ↦ t ; · }
    */
    case RuntimeTerm.Record(label, field, fieldType) =>
      val targetLabel = FieldLabel(label)
      translate(field, context).flatMap { translatedField =>
        requireType(translatedField, fieldType, field).map { checkedField =>
          TypedFiTrie(
            FiTrie.route(RouteKey.Projection(targetLabel), checkedField.trie),
            Type.Record(label, fieldType)
          )
        }
      }

    /*
     * Δ ; Γ ⊢ e ⇐ {ℓ : A} ⇝ t
     * ───────────────────────────────────── Elab-Proj
     * Δ ; Γ ⊢ e.ℓ ⇐ A ⇝ { t ◁ ⟨projℓ[]⟩ ; · ; · }
    */
    case RuntimeTerm.Projection(record, label) =>
      val targetLabel = FieldLabel(label)
      translate(record, context).flatMap { translatedRecord =>
        translatedRecord.inferredType match {
          case Type.Record(recordLabel, fieldType) if recordLabel == label =>
            Result.Ok(TypedFiTrie(
              FiTrie.response(ResponseComputation.Index(
                translatedRecord.trie,
                RequestSet.one(Request.Projection(targetLabel))
              )),
              fieldType
            ))
          case actualType =>
            Result.Err(FiTrieElaborationError.ExpectedRecord(record, targetLabel, actualType))
        }
      }

    /*
     * operator : p₁ × p₂ → p₃
     * Δ ; Γ ⊢ e₁ ⇒ p₁ ⇝ t₁    Δ ; Γ ⊢ e₂ ⇒ p₂ ⇝ t₂
     * ───────────────────────────────────────────────── Elab-PrimitiveOp
     * Δ ; Γ ⊢ e₁ operator e₂ ⇒ p₃ ⇝ { operator(t₁, t₂) ; · ; · }
     */
    case RuntimeTerm.Binary(operator, left, right) =>
      for {
        translatedLeft <- translate(left, context)
        translatedRight <- translate(right, context)
        resultType <- primitiveResultType(
          operator,
          translatedLeft.inferredType,
          translatedRight.inferredType
        )
      } yield TypedFiTrie(
        FiTrie.response(ResponseComputation.PrimitiveOperation(
          operator,
          translatedLeft.trie,
          translatedRight.trie
        )),
        resultType
      )

    /*
     * Δ ; Γ ⊢ c ⇒ Bool ⇝ t₀    Δ ; Γ ⊢ e₁ ⇒ A ⇝ t₁
     * Δ ; Γ ⊢ e₂ ⇒ A ⇝ t₂
     * ──────────────────────────────────────────────── Elab-If
     * Δ ; Γ ⊢ if c then e₁ else e₂ ⇒ A
     *   ⇝ { if t₀ then t₁ else t₂ ; · ; · }
     */
    case RuntimeTerm.If(condition, whenTrue, whenFalse) =>
      for {
        translatedCondition <- translate(condition, context)
        checkedCondition <- requireType(translatedCondition, Type.Boolean, condition)
        translatedTrue <- translate(whenTrue, context)
        translatedFalse <- translate(whenFalse, context)
        checkedFalse <- requireType(translatedFalse, translatedTrue.inferredType, whenFalse)
      } yield TypedFiTrie(
        FiTrie.response(ResponseComputation.Conditional(
          checkedCondition.trie,
          translatedTrue.trie,
          checkedFalse.trie
        )),
        translatedTrue.inferredType
      )
  }

  private def primitiveResultType(
    operator: BinaryOperator,
    leftType: Type,
    rightType: Type
  ): Result[Type, FiTrieElaborationError] = {
    operator.signatures.find { signature =>
      leftType == Type.Primitive(signature.leftArgument) &&
        rightType == Type.Primitive(signature.rightArgument)
    } match {
      case Some(signature) => Result.Ok(Type.Primitive(signature.result))
      case None => Result.Err(FiTrieElaborationError.NoPrimitiveSignature(
        operator,
        leftType,
        rightType
      ))
    }
  }

  private def requireType(
    typedTrie: TypedFiTrie,
    expectedType: Type,
    term: RuntimeTerm
  ): Result[TypedFiTrie, FiTrieElaborationError] = {
    if (typedTrie.inferredType == expectedType) {
      Result.Ok(typedTrie)
    } else {
      Result.Err(FiTrieElaborationError.UnexpectedType(
        term,
        typedTrie.inferredType,
        expectedType
      ))
    }
  }

  private def suspendedFilter(
    trie: FiTrie,
    selectedRootKeys: RootKeyExpression
  ): FiTrie = {
    FiTrie.response(ResponseComputation.Filter(trie, selectedRootKeys))
  }

}

private final case class TranslationContext(
  globalTypes: Map[Identifier, Type],
  typeContext: TypeContext,
  termTypes: List[Type]
) {
  def lookupGlobal(identifier: Identifier): Option[Type] = globalTypes.get(identifier)

  def lookupTerm(index: Int): Option[Type] = termTypes.lift(index)

  def withTerm(parameterType: Type): TranslationContext = copy(termTypes = parameterType :: termTypes)

  def withType(disjointBound: Type): TranslationContext = TranslationContext(
    globalTypes,
    typeContext.extend(disjointBound),
    termTypes.map(_.shiftTypeVariables(1))
  )
}

private object TranslationContext {
  def withGlobals(globalTypes: Map[Identifier, Type]): TranslationContext = {
    TranslationContext(globalTypes, TypeContext.empty, Nil)
  }
}
