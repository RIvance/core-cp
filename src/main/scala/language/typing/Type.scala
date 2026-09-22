package cp.language.typing

import cp.language.core.TypeSyntax
import cp.naming.NameReference
import cp.primitive.PrimitiveType

enum ApplicativeView[+Application] {
  case Blocked
  case Inert
  case Applicable(application: Application)
}

final case class TermApplicationView(parameterType: Type, resultType: Type)

final case class TraitComposition(requiredInterface: Type, providedInterface: Type)

final case class UniversalApplicationView(
  disjointBound: Type,
  bodyType: Type
)

/**
 * Expanded CP types. Variables are de Bruijn indices; forall bodies add one
 * binder, while their disjointness bounds remain in the enclosing scope.
 * Recursive bodies bind one variable, distinct from enclosing forall and sort binders.
 * Aliases and signature applications cannot occur in this representation.
 * Structural equality therefore includes alpha-equivalence.
 * Merge inference retains the intersection synthesized by its target term.
 * [[Type.intersection]] implements the exact-top unit law when expanding names
 * and computing derived interfaces.
 */
enum Type {
  case Primitive(kind: PrimitiveType)
  case Variable(index: Int)
  case Top
  case Bottom
  case Arrow(parameterType: Type, resultType: Type)
  case ForAll(disjointBound: Type, bodyType: Type)
  case Recursive(bodyType: Type)
  case Intersection(leftType: Type, rightType: Type)
  case Record(label: String, fieldType: Type)
  case Trait(requiredInterface: Type, providedInterface: Type)

  def shiftTypeVariables(by: Int, cutoff: Int = 0): Type = mapVariables(cutoff) { (index, depth) =>
    if (index < depth) Variable(index)
    else {
      require(index + by >= 0, "a type-variable shift cannot produce a negative index")
      Variable(index + by)
    }
  }

  /** Simultaneously substitutes and removes the innermost free binders, then normalizes exact-top units. */
  def instantiate(arguments: List[Type]): Type = substituteBinders(arguments).normalized

  private def substituteBinders(arguments: List[Type]): Type = {
    mapVariables(0) { (index, depth) =>
      if (index < depth) Variable(index)
      else arguments.lift(index - depth) match {
        case Some(argument) => argument.shiftTypeVariables(depth)
        case None => Variable(index - arguments.size)
      }
    }
  }

  /** Normalizes type interfaces by A ∧ ⊤ ≃ A; it does not construct or cast a term. */
  def normalized: Type = this match {
    case Primitive(_) | Variable(_) | Top | Bottom => this
    case Arrow(parameter, result) => Arrow(parameter.normalized, result.normalized)
    case ForAll(bound, body) => ForAll(bound.normalized, body.normalized)
    case Recursive(body) => Recursive(body.normalized)
    case Intersection(left, right) => Type.intersection(left.normalized, right.normalized)
    case Record(label, fieldType) => Record(label, fieldType.normalized)
    case Trait(required, provided) => Trait(required.normalized, provided.normalized)
  }

  def isWellScoped(depth: Int = 0): Boolean = this match {
    case Primitive(_) | Top | Bottom => true
    case Variable(index) => index >= 0 && index < depth
    case Arrow(parameterType, resultType) => parameterType.isWellScoped(depth) && resultType.isWellScoped(depth)
    case ForAll(bound, body) => bound.isWellScoped(depth) && body.isWellScoped(depth + 1)
    case Recursive(body) => body.isWellScoped(depth + 1)
    case Intersection(left, right) => left.isWellScoped(depth) && right.isWellScoped(depth)
    case Record(_, fieldType) => fieldType.isWellScoped(depth)
    case Trait(required, provided) => required.isWellScoped(depth) && provided.isWellScoped(depth)
  }

  private def mapVariables(depth: Int)(variable: (Int, Int) => Type): Type = this match {
    case Primitive(_) | Top | Bottom => this
    case Variable(index) => variable(index, depth)
    case Arrow(parameterType, resultType) =>
      Arrow(parameterType.mapVariables(depth)(variable), resultType.mapVariables(depth)(variable))
    case ForAll(bound, body) =>
      ForAll(bound.mapVariables(depth)(variable), body.mapVariables(depth + 1)(variable))
    case Recursive(body) => Recursive(body.mapVariables(depth + 1)(variable))
    case Intersection(left, right) =>
      Intersection(left.mapVariables(depth)(variable), right.mapVariables(depth)(variable))
    case Record(label, fieldType) => Record(label, fieldType.mapVariables(depth)(variable))
    case Trait(required, provided) =>
      Trait(required.mapVariables(depth)(variable), provided.mapVariables(depth)(variable))
  }

  def render: String = diagnosticSyntax(Nil).render

  /** One explicit unfolding: U(μ α. A) = A[α ↦ μ α. A]. */
  def unfolded: Option[Type] = this match {
    case Recursive(body) => Some(body.substituteBinders(List(this)))
    case _ => None
  }

  private def diagnosticSyntax(scope: List[String]): TypeSyntax = this match {
    case Primitive(kind) => TypeSyntax.Primitive(kind)
    case Variable(index) =>
      TypeSyntax.Reference(NameReference.Unqualified(scope.lift(index).getOrElse(s"α${index - scope.size}")))
    case Top => TypeSyntax.Top
    case Bottom => TypeSyntax.Bottom
    case Arrow(parameterType, resultType) =>
      TypeSyntax.Arrow(parameterType.diagnosticSyntax(scope), resultType.diagnosticSyntax(scope))
    case ForAll(bound, body) =>
      val parameter = s"T${scope.size}"
      TypeSyntax.ForAll(parameter, bound.diagnosticSyntax(scope), body.diagnosticSyntax(parameter :: scope))
    case Recursive(body) =>
      val parameter = s"T${scope.size}"
      TypeSyntax.Recursive(parameter, body.diagnosticSyntax(parameter :: scope))
    case Intersection(left, right) =>
      TypeSyntax.Intersection(left.diagnosticSyntax(scope), right.diagnosticSyntax(scope))
    case Record(label, fieldType) => TypeSyntax.Record(label, fieldType.diagnosticSyntax(scope))
    case Trait(required, provided) =>
      TypeSyntax.Trait(required.diagnosticSyntax(scope), provided.diagnosticSyntax(scope))
  }

  def recordFields: Map[String, Type] = this match {
    case Record(label, fieldType) => Map(label -> fieldType)
    case Intersection(leftType, rightType) =>
      rightType.recordFields.foldLeft(leftType.recordFields) {
        case (fields, (label, fieldType)) =>
          fields.updatedWith(label) {
            case Some(existingType) => Some(Type.intersection(existingType, fieldType))
            case None => Some(fieldType)
          }
      }
    case _ => Map.empty
  }

  def traitComposition: Option[TraitComposition] = traitApplicationView match {
    case ApplicativeView.Applicable(composition) => Some(composition)
    case _ => None
  }

  /**
   * Fiobs arrow distribution restricted to F ::= Trait[A, B] | ⊤ | F ∧ F.
   * ⟦Trait[A, B]⟧ = ⟦A⟧ ⇾ ⟦B⟧, and F ▹ᵗ Trait[A, B] abbreviates
   * ⟦F⟧ ▹ᵃ (⟦A⟧ ⇾ ⟦B⟧). The restriction preserves CP's distinction between traits and other functions.
   */
  private def traitApplicationView: ApplicativeView[TraitComposition] = this match {
    // ───────────────────────── AD-Arr
    // (⟦A⟧ ⇾ ⟦B⟧) ▹ᵃ (⟦A⟧ ⇾ ⟦B⟧)
    case Trait(requiredInterface, providedInterface) =>
      ApplicativeView.Applicable(TraitComposition(requiredInterface, providedInterface))

    // ─────── AD-Top
    // ⊤ ▹ᵃ ⊤
    case Top => ApplicativeView.Inert

    // ⟦F₁⟧ ▹ᵃ (⟦A₁⟧ ⇾ ⟦B₁⟧)    ⟦F₂⟧ ▹ᵃ (⟦A₂⟧ ⇾ ⟦B₂⟧)
    // ─────────────────────────────────────────────────────── AD-And-Arr
    // ⟦F₁⟧ ∧ ⟦F₂⟧ ▹ᵃ (⟦A₁⟧ ∧ ⟦A₂⟧) ⇾ (⟦B₁⟧ ∧ ⟦B₂⟧)
    case Intersection(leftType, rightType) =>
      Type.combineApplicativeViews(leftType.traitApplicationView, rightType.traitApplicationView) {
        (leftComposition, rightComposition) => TraitComposition(
          Intersection(leftComposition.requiredInterface, rightComposition.requiredInterface),
          Intersection(leftComposition.providedInterface, rightComposition.providedInterface)
        )
      }
    case _ => ApplicativeView.Blocked
  }

  def termApplicationView: ApplicativeView[TermApplicationView] = this match {
    // ───────────── AD-Primitive
    // p ▹ᵃ ⊤
    //
    // ─────── AD-Top
    // ⊤ ▹ᵃ ⊤
    //
    // ───────────── AD-Rcd
    // {ℓ : A} ▹ᵃ ⊤
    // ───────────────── AD-Rec
    // μ α. A ▹ᵃ ⊤
    case Primitive(_) | Top | Record(_, _) | Recursive(_) => ApplicativeView.Inert

    // Fiobs has no applicative-distribution rule for ⊥ or an opaque α.
    case Bottom | Variable(_) => ApplicativeView.Blocked

    // ─────────────── AD-Arr
    // A ⇾ B ▹ᵃ A ⇾ B
    case Arrow(parameterType, resultType) => ApplicativeView.Applicable(TermApplicationView(parameterType, resultType))

    // ⟦Trait[A, B]⟧ = ⟦A⟧ ⇾ ⟦B⟧
    // ─────────────────────────────── Translate-Trait-App
    // Trait[A, B] ▹ᵃ A ⇾ B
    case Trait(requiredInterface, providedInterface) =>
      ApplicativeView.Applicable(TermApplicationView(requiredInterface, providedInterface))

    // ───────────────────────── AD-All-Top
    // ∀(α ∗ A). B ▹ᵃ ⊤
    case ForAll(_, _) => ApplicativeView.Inert

    case Intersection(leftType, rightType) =>
      Type.combineApplicativeViews(
        leftType.termApplicationView,
        rightType.termApplicationView
      ) { (leftView, rightView) =>
        // F₁ ▹ᵃ A₁ ⇾ B₁    F₂ ▹ᵃ A₂ ⇾ B₂
        // ───────────────────────────────── AD-And-Arr
        // F₁ ∧ F₂ ▹ᵃ (A₁ ∧ A₂) ⇾ (B₁ ∧ B₂)
        TermApplicationView(
          Type.intersection(leftView.parameterType, rightView.parameterType),
          Type.intersection(leftView.resultType, rightView.resultType)
        )
      }
  }

  def universalApplicationView: ApplicativeView[UniversalApplicationView] = this match {
    // ───────────── AD-Primitive
    // p ▹ᵘ ⊤
    //
    // ─────── AD-Top
    // ⊤ ▹ᵘ ⊤
    //
    // ───────────── AD-Rcd
    // {ℓ : A} ▹ᵘ ⊤
    // ───────────────── AD-Rec
    // μ α. A ▹ᵘ ⊤
    case Primitive(_) | Top | Record(_, _) | Recursive(_) => ApplicativeView.Inert

    // Fiobs has no applicative-distribution rule for ⊥ or an opaque α.
    case Bottom | Variable(_) => ApplicativeView.Blocked

    // ───────────── AD-Arr-Top
    // A ⇾ B ▹ᵘ ⊤
    //
    // ⟦Trait[A, B]⟧ = ⟦A⟧ ⇾ ⟦B⟧
    case Arrow(_, _) | Trait(_, _) => ApplicativeView.Inert

    // ───────────────────────────── AD-All
    // ∀(α ∗ A). B ▹ᵘ ∀(α ∗ A). B
    case ForAll(disjointBound, bodyType) =>
      ApplicativeView.Applicable(UniversalApplicationView(
        disjointBound,
        bodyType
      ))

    case Intersection(leftType, rightType) =>
      Type.combineApplicativeViews(
        leftType.universalApplicationView,
        rightType.universalApplicationView
      ) { (leftView, rightView) =>
        // F₁ ▹ᵘ ∀(α₁ ∗ A₁). B₁    F₂ ▹ᵘ ∀(α₂ ∗ A₂). B₂
        // ─────────────────────────────────────────────── AD-And-All
        // F₁ ∧ F₂ ▹ᵘ ∀(α ∗ (A₁ ∧ A₂)). (B₁[α₁ ↦ α] ∧ B₂[α₂ ↦ α])
        UniversalApplicationView(
          Type.intersection(leftView.disjointBound, rightView.disjointBound),
          Type.intersection(leftView.bodyType, rightView.bodyType)
        )
      }
  }
}

object Type {
  val Integer: Type = Primitive(PrimitiveType.Integer)
  val Decimal: Type = Primitive(PrimitiveType.Decimal)
  val Boolean: Type = Primitive(PrimitiveType.Boolean)
  val Text: Type = Primitive(PrimitiveType.Text)
  val Unit: Type = Primitive(PrimitiveType.Unit)

  /**
   * Constructs an intersection modulo its unit. This uses only exact ⊤;
   * route-silent arrows, records, and universals retain their constructors.
   *
   * A ∧ ⊤ ⤇ A ‖ ⊤    Δ ⊢ A <: A    Δ ⊢ A <: ⊤
   * ─────────────────────────────────────────── S-Split
   * Δ ⊢ A <: A ∧ ⊤
   *
   * Δ ⊢ A <: A
   * ─────────────── S-AndL
   * Δ ⊢ A ∧ ⊤ <: A
   *
   * Thus A ∧ ⊤ ≃ A, and symmetrically ⊤ ∧ A ≃ A.
   */
  def intersection(left: Type, right: Type): Type = (left, right) match {
    case (Top, _) => right
    case (_, Top) => left
    case _ => Intersection(left, right)
  }

  /** Multi-field record syntax is normalized to intersections of core records. */
  def records(firstField: (String, Type), remainingFields: List[(String, Type)]): Type = {
    remainingFields.foldLeft(Record(firstField._1, firstField._2): Type) {
      case (recordType, (label, fieldType)) =>
        intersection(recordType, Record(label, fieldType))
    }
  }

  private def combineApplicativeViews[Application](
    leftView: ApplicativeView[Application],
    rightView: ApplicativeView[Application]
  )(
    combineApplications: (Application, Application) => Application
  ): ApplicativeView[Application] = (leftView, rightView) match {
    // F₁ ▹[κ] ⊤    F₂ ▹[κ] ⊤
    // ─────────────────────── AD-And-Top
    // F₁ ∧ F₂ ▹[κ] ⊤
    case (ApplicativeView.Inert, ApplicativeView.Inert) => ApplicativeView.Inert

    // F₁ ▹[κ] V    F₂ ▹[κ] ⊤
    // ───────────────────────── AD-And-Left
    // F₁ ∧ F₂ ▹[κ] V
    case (applicable: ApplicativeView.Applicable[Application], ApplicativeView.Inert) => applicable

    // F₁ ▹[κ] ⊤    F₂ ▹[κ] V
    // ────────────────────────── AD-And-Right
    // F₁ ∧ F₂ ▹[κ] V
    case (ApplicativeView.Inert, applicable: ApplicativeView.Applicable[Application]) => applicable

    case (
      ApplicativeView.Applicable(leftApplication),
      ApplicativeView.Applicable(rightApplication)
    ) => ApplicativeView.Applicable(combineApplications(leftApplication, rightApplication))

    // An intersection cannot skip a contributor for which no distribution
    // rule exists, so an opaque variable or ⊥ blocks the complete view.
    case _ => ApplicativeView.Blocked
  }
}
