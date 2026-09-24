package cp.language.elaboration

import cp.language.core.{Declaration, SortArgument, TypeSyntax}
import cp.language.typing.Type
import cp.naming.{Identifier, NameReference}
import cp.util.Result

enum TypeExpansionError {
  case NameResolution(error: NameResolutionError)
  case UnknownSignature(identifier: Identifier)
  case SignatureArityMismatch(identifier: Identifier, expected: Int, actual: Int)
  case DuplicateSortParameter(name: String)
}

final case class SortTypes(negative: Type, positive: Type)

/** A signature body is closed over adjacent negative/positive slots for each sort. */
final case class SignatureDefinition(parameters: List[String], bodyType: Type) {
  require(bodyType.isWellScoped(parameters.size * 2), "a signature body must be closed over its sort parameters")

  def instantiate(identifier: Identifier, arguments: List[SortTypes]): Result[Type, TypeExpansionError] = {
    if (arguments.size != parameters.size) {
      Result.Err(TypeExpansionError.SignatureArityMismatch(identifier, parameters.size, arguments.size))
    } else {
      Result.Ok(bodyType.instantiate(arguments.flatMap(argument => List(argument.negative, argument.positive))))
    }
  }
}

private enum TypeBinding {
  case Ordinary(name: String)
  case Sort(name: String)
  case PositiveSort

  def sourceName: Option[String] = this match {
    case Ordinary(name) => Some(name)
    case Sort(name) => Some(name)
    case PositiveSort => None
  }
}

/** Owns the lexical interpretation of source type names and paired sort binders. */
final class TypeScope private (bindings: List[TypeBinding]) {
  def withType(name: String): TypeScope = new TypeScope(TypeBinding.Ordinary(name) :: bindings)

  def lookup(name: String): Option[Type] = {
    bindings.indexWhere(_.sourceName.contains(name)) match {
      case -1 => None
      case index => Some(Type.Variable(index))
    }
  }

  def lookupSort(name: String): Option[SortTypes] = {
    bindings.zipWithIndex.find(_._1.sourceName.contains(name)).collect {
      case (TypeBinding.Sort(_), index) => SortTypes(Type.Variable(index), Type.Variable(index + 1))
    }
  }

  private[elaboration] def sourceNames: List[String] = bindings.flatMap(_.sourceName).distinct

  private[elaboration] def displayNames: List[String] = bindings.zipWithIndex.map {
    case (binding, index) => binding.sourceName.getOrElse(s"α$index")
  }

  private[elaboration] def sortCompanions: Map[Int, Int] = {
    bindings.zipWithIndex.collect { case (TypeBinding.Sort(_), index) => index -> (index + 1) }.toMap
  }
}

object TypeScope {
  val empty: TypeScope = new TypeScope(Nil)

  def sorts(parameters: List[String]): TypeScope = {
    new TypeScope(parameters.flatMap(name => List(TypeBinding.Sort(name), TypeBinding.PositiveSort)))
  }
}

final case class TypeExpansionContext(
  moduleScope: ModuleScope,
  signatures: Map[Identifier, SignatureDefinition],
  private[elaboration] val inspection: Option[SourceInspection] = None
) {
  def register(identifier: Identifier, definition: SignatureDefinition): TypeExpansionContext = {
    copy(signatures = signatures.updated(identifier, definition))
  }

  def resolve(reference: NameReference): Result[(Identifier, SignatureDefinition), TypeExpansionError] = {
    moduleScope.resolveType(reference).mapError(TypeExpansionError.NameResolution(_)).flatMap { identifier =>
      signatures.get(identifier) match {
        case Some(definition) => Result.Ok(identifier -> definition)
        case None => Result.Err(TypeExpansionError.UnknownSignature(identifier))
      }
    }
  }
}

private enum TypePolarity {
  case Positive, Negative

  def flipped: TypePolarity = this match {
    case Positive => Negative
    case Negative => Positive
  }
}

object TypeExpansion {
  def signature(
    declaration: Declaration.TypeSignature,
    context: TypeExpansionContext
  ): Result[SignatureDefinition, TypeExpansionError] = {
    declaration.sortParameters.groupBy(identity).collectFirst {
      case (name, occurrences) if occurrences.size > 1 => name
    } match {
      case Some(name) => Result.Err(TypeExpansionError.DuplicateSortParameter(name))
      case None =>
        val scope = TypeScope.sorts(declaration.sortParameters)
        // β̄ fresh    Δ ; ᾱ ↦ β̄ ⊢ A ⇒ A₁    Δ ; ᾱ ↦ β̄ ⊢ B ⇒ B₁
        // ᾱ ↦ β̄ ⊢⁺false B₁ ⇒ B₂
        // ───────────────────────────────────────────────────────────── E-TypeDecl
        // Δ ⊢ type X⟨ᾱ⟩ extends A = B ⇒ Δ, X⟨ᾱ, β̄⟩ ↦ A₁ ∧ B₂
        for {
          required <- expand(declaration.requiredInterface, context, scope)
          provided <- expand(declaration.providedInterface, context, scope)
        } yield SignatureDefinition(
          declaration.sortParameters,
          Type.intersection(required, transformSorts(provided, scope.sortCompanions, TypePolarity.Positive, false))
        )
    }
  }

  /** Resolves all references and eliminates all signature applications in one traversal. */
  def expand(
    inputType: TypeSyntax,
    context: TypeExpansionContext,
    scope: TypeScope = TypeScope.empty
  ): Result[Type, TypeExpansionError] = {
    context.inspection.foreach(_.observeType(inputType, scope))
    expandType(inputType, context, scope)
  }

  private def expandType(
    inputType: TypeSyntax,
    context: TypeExpansionContext,
    scope: TypeScope
  ): Result[Type, TypeExpansionError] = inputType match {
    case TypeSyntax.Primitive(kind) => Result.Ok(Type.Primitive(kind))
    case TypeSyntax.Top => Result.Ok(Type.Top)
    case TypeSyntax.Bottom => Result.Ok(Type.Bottom)
    case TypeSyntax.Reference(reference) =>
      val local = reference match {
        case NameReference.Unqualified(name) => scope.lookup(name)
        case NameReference.Qualified(_) => None
      }
      // α ∈ Ξ                         X⟨⟩ ↦ C ∈ Δ
      // ───────────── Expand-Var       ──────────────── Expand-Alias
      // Δ, Σ, Ξ ⊢ α ⇒ α               Δ, Σ, Ξ ⊢ X ⇒ C
      local match {
        case Some(variable) => Result.Ok(variable)
        case None => context.resolve(reference).flatMap { case (identifier, definition) =>
          definition.instantiate(identifier, Nil)
        }
      }
    case TypeSyntax.Arrow(parameterType, resultType) =>
      for {
        parameter <- expand(parameterType, context, scope)
        result <- expand(resultType, context, scope)
      } yield Type.Arrow(parameter, result)
    case TypeSyntax.ForAll(name, disjointBound, bodyType) =>
      for {
        bound <- expand(disjointBound, context, scope)
        body <- expand(bodyType, context, scope.withType(name))
      } yield Type.ForAll(bound, body)
    // Δ, Σ, Ξ, α ⊢ A ⇒ B
    // ────────────────────────── Expand-Rec
    // Δ, Σ, Ξ ⊢ μ α. A ⇒ μ α. B
    case TypeSyntax.Recursive(name, bodyType) =>
      expand(bodyType, context, scope.withType(name)).map(Type.Recursive(_))
    case TypeSyntax.Intersection(leftType, rightType) =>
      for {
        left <- expand(leftType, context, scope)
        right <- expand(rightType, context, scope)
      } yield Type.intersection(left, right)
    case TypeSyntax.Record(label, fieldType) =>
      expand(fieldType, context, scope).map(Type.Record(label, _))
    case TypeSyntax.Trait(requiredInterface, providedInterface) =>
      for {
        required <- expand(requiredInterface, context, scope)
        provided <- expand(providedInterface, context, scope)
      } yield Type.Trait(required, provided)
    case TypeSyntax.SignatureApplication(reference, arguments) =>
      // X⟨ᾱ, β̄⟩ ↦ C ∈ Δ    Σ ⊢ S̄ ⇒ (Ā, B̄)
      // ───────────────────────────────────────────── Expand-Sig
      // Δ, Σ ⊢ X⟨S̄⟩ ⇒ [Ā/ᾱ, B̄/β̄]C
      context.resolve(reference).flatMap { case (identifier, definition) =>
        Result.traverse(arguments)(expandSortArgument(_, context, scope))
          .flatMap(definition.instantiate(identifier, _))
      }
  }

  private def expandSortArgument(
    argument: SortArgument,
    context: TypeExpansionContext,
    scope: TypeScope
  ): Result[SortTypes, TypeExpansionError] = argument match {
    case SortArgument.TypeArgument(argumentType) =>
      val sort = argumentType match {
        case TypeSyntax.Reference(NameReference.Unqualified(name)) => scope.lookupSort(name)
        case _ => None
      }
      // α ↦ β ∈ Σ                     Δ, Σ ⊢ A ⇒ A′    A ∉ dom(Σ)
      // ───────────────── Sort-Var     ─────────────────────────── Sort-Type
      // Σ ⊢ α ⇒ (α, β)                 Σ ⊢ A ⇒ (A′, A′)
      sort match {
        case Some(pair) => Result.Ok(pair)
        case None => expand(argumentType, context, scope).map(expanded => SortTypes(expanded, expanded))
      }
    case SortArgument.Dependency(negativeType, positiveType) =>
      // Δ, Σ ⊢ A ⇒ A′    Δ, Σ ⊢ B ⇒ B′
      // ─────────────────────────────────────── Sort-Pair
      // Σ ⊢ A % B ⇒ (A′ ∧ B′, B′)
      for {
        negative <- expand(negativeType, context, scope)
        positive <- expand(positiveType, context, scope)
      } yield SortTypes(Type.intersection(negative, positive), positive)
  }

  private def transformSorts(
    inputType: Type,
    companions: Map[Int, Int],
    polarity: TypePolarity,
    insideConstructorField: Boolean
  ): Type = inputType match {
    // α ↦ β ∈ Σ                     α ↦ β ∈ Σ
    // ───────────────── ST-Positive  ────────────────────────── ST-CtorPositive
    // Σ ⊢⁺false α ⇒ β                Σ ⊢⁺true α ⇒ Trait[α, β]
    case Type.Variable(index) if polarity == TypePolarity.Positive && companions.contains(index) =>
      val positive = Type.Variable(companions(index))
      if (insideConstructorField) Type.Trait(inputType, positive) else positive
    case Type.Primitive(_) | Type.Top | Type.Bottom | Type.Variable(_) => inputType
    // Σ ⊢ᶠˡⁱᵖ⁽ᵖ⁾ A ⇒ A′    Σ ⊢ᵖ B ⇒ B′
    // ─────────────────────────────────────── ST-Arrow / ST-Trait
    // Σ ⊢ᵖ A ⇾ B ⇒ A′ ⇾ B′    Σ ⊢ᵖ Trait[A, B] ⇒ Trait[A′, B′]
    case Type.Arrow(parameter, result) => Type.Arrow(
        transformSorts(parameter, companions, polarity.flipped, insideConstructorField),
        transformSorts(result, companions, polarity, insideConstructorField)
      )
    case Type.Trait(required, provided) => Type.Trait(
        transformSorts(required, companions, polarity.flipped, insideConstructorField),
        transformSorts(provided, companions, polarity, insideConstructorField)
      )
    // Σ ⊢ᵖ A ⇒ A′    Σ↑ ⊢ᵖ B ⇒ B′
    // ────────────────────────────────── ST-All
    // Σ ⊢ᵖ ∀(α ∗ A). B ⇒ ∀(α ∗ A′). B′
    // Σ↑ shifts the sort slots beneath the new, distinct binder.
    case Type.ForAll(bound, body) => Type.ForAll(
        transformSorts(bound, companions, polarity, insideConstructorField),
        transformSorts(body, companions.map { case (negative, positive) =>
          (negative + 1) -> (positive + 1)
        }, polarity, insideConstructorField)
      )
    // Σ↑ ⊢ᵖ A ⇒ A′
    // ─────────────────────── ST-Rec
    // Σ ⊢ᵖ μ α. A ⇒ μ α. A′
    case Type.Recursive(body) => Type.Recursive(
        transformSorts(body, companions.map { case (negative, positive) =>
          (negative + 1) -> (positive + 1)
        }, polarity, insideConstructorField)
      )
    // Σ ⊢ᵖ A ⇒ A′    Σ ⊢ᵖ B ⇒ B′
    // ─────────────────────────── ST-And
    // Σ ⊢ᵖ A ∧ B ⇒ A′ ∧ B′
    case Type.Intersection(left, right) => Type.intersection(
        transformSorts(left, companions, polarity, insideConstructorField),
        transformSorts(right, companions, polarity, insideConstructorField)
      )
    // Σ ⊢ᵖᶜᵃᵖ⁽ℓ⁾ A ⇒ A′
    // ─────────────────────── ST-Record
    // Σ ⊢ᵖ {ℓ : A} ⇒ {ℓ : A′}
    case Type.Record(label, fieldType) =>
      Type.Record(label, transformSorts(fieldType, companions, polarity, label.headOption.exists(_.isUpper)))
  }
}
