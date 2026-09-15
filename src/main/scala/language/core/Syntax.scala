package cp.language.core

import cp.naming.NameReference
import cp.primitive.{BinaryOperator, PrimitiveType, PrimitiveValue}
import cp.source.SourceSpan

final case class TypeBinder(name: String, disjointBound: Type)

final case class ValueParameter(name: String, parameterType: Type)

final case class PatternParameter(name: String, declaredType: Option[Type])

enum ApplicativeView[+Application] {
  case Blocked
  case Inert
  case Applicable(application: Application)
}

final case class TermApplicationView(parameterType: Type, resultType: Type)

final case class TraitComposition(requiredInterface: Type, providedInterface: Type)

final case class UniversalApplicationView(
  typeParameter: String,
  disjointBound: Type,
  bodyType: Type
)

enum SortArgument {
  case TypeArgument(argumentType: Type)
  case Dependency(negativeType: Type, positiveType: Type)

  def freeVariables: Set[String] = this match {
    case TypeArgument(argumentType) => argumentType.freeVariables
    case Dependency(negativeType, positiveType) =>
      negativeType.freeVariables ++ positiveType.freeVariables
  }

  def substituteVariables(replacements: Map[String, Type]): SortArgument = this match {
    case TypeArgument(argumentType) =>
      TypeArgument(argumentType.substituteVariables(replacements))
    case Dependency(negativeType, positiveType) =>
      Dependency(
        negativeType.substituteVariables(replacements),
        positiveType.substituteVariables(replacements)
      )
  }
}

enum Type {
  case Primitive(kind: PrimitiveType)
  case Variable(name: String)
  case Named(reference: NameReference)
  case Top
  case Bottom
  case Arrow(parameterType: Type, resultType: Type)
  case ForAll(typeParameter: String, disjointBound: Type, bodyType: Type)
  case Intersection(leftType: Type, rightType: Type)
  case Record(label: String, fieldType: Type)
  case Trait(requiredInterface: Type, providedInterface: Type)
  case SignatureApplication(reference: NameReference, arguments: List[SortArgument])

  def render: String = CpTypeRendering.render(this)

  def freeVariables: Set[String] = this match {
    case Primitive(_) | Named(_) | Top | Bottom => Set.empty
    case Variable(name) => Set(name)
    case Arrow(parameterType, resultType) => parameterType.freeVariables ++ resultType.freeVariables
    case ForAll(typeParameter, disjointBound, bodyType) =>
      disjointBound.freeVariables ++ (bodyType.freeVariables - typeParameter)
    case Intersection(leftType, rightType) => leftType.freeVariables ++ rightType.freeVariables
    case Record(_, fieldType) => fieldType.freeVariables
    case Trait(requiredInterface, providedInterface) =>
      requiredInterface.freeVariables ++ providedInterface.freeVariables
    case SignatureApplication(_, arguments) => arguments.flatMap(_.freeVariables).toSet
  }

  /** Names that still require module-level type resolution. */
  def referencedNames(boundVariables: Set[String] = Set.empty): Set[NameReference] = this match {
    case Primitive(_) | Top | Bottom => Set.empty
    case Variable(name) if boundVariables.contains(name) => Set.empty
    case Variable(name) => Set(NameReference.Unqualified(name))
    case Named(reference) => Set(reference)
    case Arrow(parameterType, resultType) =>
      parameterType.referencedNames(boundVariables) ++ resultType.referencedNames(boundVariables)
    case ForAll(typeParameter, disjointBound, bodyType) =>
      disjointBound.referencedNames(boundVariables) ++
        bodyType.referencedNames(boundVariables + typeParameter)
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

  def substituteVariables(replacements: Map[String, Type]): Type = this match {
    case Primitive(_) | Named(_) | Top | Bottom => this
    case Variable(name) => replacements.getOrElse(name, this)
    case Arrow(parameterType, resultType) =>
      Arrow(
        parameterType.substituteVariables(replacements),
        resultType.substituteVariables(replacements)
      )
    case ForAll(typeParameter, disjointBound, bodyType) =>
      val replacementsBelowBinder = replacements - typeParameter
      val replacementVariables = replacementsBelowBinder.values.flatMap(_.freeVariables).toSet
      if (replacementVariables.contains(typeParameter)) {
        val unavailableNames = bodyType.freeVariables ++ replacementVariables ++ replacements.keySet
        val freshParameter = Type.freshName(typeParameter, unavailableNames)
        val renamedBody = bodyType.substituteVariables(Map(typeParameter -> Variable(freshParameter)))
        ForAll(
          freshParameter,
          disjointBound.substituteVariables(replacements),
          renamedBody.substituteVariables(replacementsBelowBinder)
        )
      } else {
        ForAll(
          typeParameter,
          disjointBound.substituteVariables(replacements),
          bodyType.substituteVariables(replacementsBelowBinder)
        )
      }
    case Intersection(leftType, rightType) =>
      Intersection(
        leftType.substituteVariables(replacements),
        rightType.substituteVariables(replacements)
      )
    case Record(label, fieldType) => Record(label, fieldType.substituteVariables(replacements))
    case Trait(requiredInterface, providedInterface) =>
      Trait(
        requiredInterface.substituteVariables(replacements),
        providedInterface.substituteVariables(replacements)
      )
    case SignatureApplication(name, arguments) =>
      SignatureApplication(name, arguments.map(_.substituteVariables(replacements)))
  }

  def recordFields: Map[String, Type] = this match {
    case Record(label, fieldType) => Map(label -> fieldType)
    case Intersection(leftType, rightType) =>
      rightType.recordFields.foldLeft(leftType.recordFields) {
        case (fields, (label, fieldType)) =>
          fields.updatedWith(label) {
            case Some(existingType) => Some(Intersection(existingType, fieldType))
            case None => Some(fieldType)
          }
      }
    case _ => Map.empty
  }

  def traitComposition: Option[TraitComposition] = this match {
    // ───────────────────── Trait-View
    // Trait[A, B] ▹ᵗ Trait[A, B]
    case Trait(requiredInterface, providedInterface) =>
      Some(TraitComposition(requiredInterface, providedInterface))

    // F₁ ▹ᵗ Trait[A₁, B₁]    F₂ ▹ᵗ Trait[A₂, B₂]
    // ────────────────────────────────────────────── Trait-View-And
    // F₁ ∧ F₂ ▹ᵗ Trait[A₁ ∧ A₂, B₁ ∧ B₂]
    case Intersection(leftType, rightType) =>
      for {
        leftComposition <- leftType.traitComposition
        rightComposition <- rightType.traitComposition
      } yield TraitComposition(
        Intersection(leftComposition.requiredInterface, rightComposition.requiredInterface),
        Intersection(leftComposition.providedInterface, rightComposition.providedInterface)
      )
    case _ => None
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
    case Primitive(_) | Top | Record(_, _) => ApplicativeView.Inert

    // Fiobs has no applicative-distribution rule for ⊥ or an opaque α.
    case Bottom | Variable(_) | Named(_) | SignatureApplication(_, _) => ApplicativeView.Blocked

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
    case ForAll(_, _, _) => ApplicativeView.Inert

    case Intersection(leftType, rightType) =>
      Type.combineApplicativeViews(
        leftType.termApplicationView,
        rightType.termApplicationView
      ) { (leftView, rightView) =>
        // F₁ ▹ᵃ A₁ ⇾ B₁    F₂ ▹ᵃ A₂ ⇾ B₂
        // ───────────────────────────────── AD-And-Arr
        // F₁ ∧ F₂ ▹ᵃ (A₁ ∧ A₂) ⇾ (B₁ ∧ B₂)
        TermApplicationView(
          Intersection(leftView.parameterType, rightView.parameterType),
          Intersection(leftView.resultType, rightView.resultType)
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
    case Primitive(_) | Top | Record(_, _) => ApplicativeView.Inert

    // Fiobs has no applicative-distribution rule for ⊥ or an opaque α.
    case Bottom | Variable(_) | Named(_) | SignatureApplication(_, _) => ApplicativeView.Blocked

    // ───────────── AD-Arr-Top
    // A ⇾ B ▹ᵘ ⊤
    //
    // ⟦Trait[A, B]⟧ = ⟦A⟧ ⇾ ⟦B⟧
    case Arrow(_, _) | Trait(_, _) => ApplicativeView.Inert

    // ───────────────────────────── AD-All
    // ∀(α ∗ A). B ▹ᵘ ∀(α ∗ A). B
    case ForAll(typeParameter, disjointBound, bodyType) =>
      ApplicativeView.Applicable(UniversalApplicationView(
        typeParameter,
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
        val unavailableNames =
          leftView.disjointBound.freeVariables ++
            rightView.disjointBound.freeVariables ++
            leftView.bodyType.freeVariables ++
            rightView.bodyType.freeVariables
        val sharedParameter = Type.freshName("TypeArgument", unavailableNames)
        val sharedVariable = Variable(sharedParameter)
        UniversalApplicationView(
          sharedParameter,
          Intersection(leftView.disjointBound, rightView.disjointBound),
          Intersection(
            leftView.bodyType.substituteVariables(Map(
              leftView.typeParameter -> sharedVariable
            )),
            rightView.bodyType.substituteVariables(Map(
              rightView.typeParameter -> sharedVariable
            ))
          )
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

  /** Multi-field record syntax is normalized to intersections of core records. */
  def records(firstField: (String, Type), remainingFields: List[(String, Type)]): Type = {
    remainingFields.foldLeft(Record(firstField._1, firstField._2): Type) {
      case (recordType, (label, fieldType)) =>
        Intersection(recordType, Record(label, fieldType))
    }
  }

  private[language] def freshName(baseName: String, unavailableNames: Set[String]): String = {
    if (!unavailableNames.contains(baseName)) {
      baseName
    } else {
      Iterator.from(1).map(index => s"$baseName$index")
        .find(name => !unavailableNames.contains(name))
        .getOrElse(throw new IllegalStateException("the infinite fresh-name stream was exhausted"))
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

enum Member {
  case Field(label: String, value: Expression)
  case MethodPattern(
    constructorName: String,
    constructorParameters: List[PatternParameter],
    selfRequirement: Option[Type],
    label: String,
    valueParameters: List[ValueParameter],
    body: Expression
  )

  def definitionName: String = this match {
    case Field(label, _) => label
    case MethodPattern(_, _, _, label, _, _) => label
  }

  def freeTermVariables: Set[NameReference] = this match {
    case Field(_, value) => value.freeTermVariables
    case MethodPattern(_, constructorParameters, selfRequirement, _, valueParameters, body) =>
      val boundNames =
        constructorParameters.map(_.name).toSet ++
          valueParameters.map(_.name).toSet ++
          selfRequirement.map(_ => "self")
      body.freeTermVariables -- boundNames.map(NameReference.Unqualified.apply)
  }

  def withoutSourceSpans: Member = this match {
    case Field(label, value) => Field(label, value.withoutSourceSpans)
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
  case TypeApplication(function: Expression, argumentType: Type)
  case Merge(left: Expression, right: Expression)
  case Record(members: List[Member])
  case Projection(record: Expression, label: String)
  case Annotation(expression: Expression, annotatedType: Type)
  case Let(
    name: String,
    declaredType: Option[Type],
    initializer: Expression,
    body: Expression
  )
  case RecursiveLet(
    name: String,
    declaredType: Type,
    initializer: Expression,
    body: Expression
  )
  case Open(record: Expression, body: Expression)
  case New(traitExpression: Expression)
  case Forward(traitExpression: Expression, selfArgument: Expression)
  case Trait(
    selfName: String,
    selfRequirement: Type,
    providedInterface: Type,
    inheritedTrait: Expression,
    body: Expression
  )
  case Binary(operator: BinaryOperator, left: Expression, right: Expression)
  case If(condition: Expression, whenTrue: Expression, whenFalse: Expression)
  case Located(expression: Expression, sourceSpan: SourceSpan)

  def freeTermVariables: Set[NameReference] = this match {
    case Literal(_) | Top => Set.empty
    case Variable(reference) => Set(reference)
    case Lambda(parameter, body) => body.freeTermVariables - NameReference.Unqualified(parameter.name)
    case Application(function, argument) => function.freeTermVariables ++ argument.freeTermVariables
    case TypeLambda(_, body) => body.freeTermVariables
    case TypeApplication(function, _) => function.freeTermVariables
    case Merge(left, right) => left.freeTermVariables ++ right.freeTermVariables
    case Record(members) => members.flatMap(_.freeTermVariables).toSet
    case Projection(record, _) => record.freeTermVariables
    case Annotation(expression, _) => expression.freeTermVariables
    case Let(name, _, initializer, body) =>
      initializer.freeTermVariables ++ (body.freeTermVariables - NameReference.Unqualified(name))
    case RecursiveLet(name, _, initializer, body) =>
      (initializer.freeTermVariables ++ body.freeTermVariables) - NameReference.Unqualified(name)
    case Open(record, body) => record.freeTermVariables ++ body.freeTermVariables
    case New(traitExpression) => traitExpression.freeTermVariables
    case Forward(traitExpression, selfArgument) =>
      traitExpression.freeTermVariables ++ selfArgument.freeTermVariables
    case Trait(selfName, _, _, inheritedTrait, body) =>
      (inheritedTrait.freeTermVariables - NameReference.Unqualified(selfName)) ++
        (body.freeTermVariables -- Set(
          NameReference.Unqualified(selfName),
          NameReference.Unqualified("super")
        ))
    case Binary(_, left, right) => left.freeTermVariables ++ right.freeTermVariables
    case If(condition, whenTrue, whenFalse) =>
      condition.freeTermVariables ++ whenTrue.freeTermVariables ++ whenFalse.freeTermVariables
    case Located(expression, _) => expression.freeTermVariables
  }

  /** Removes parser-owned diagnostic metadata while preserving the semantic tree. */
  def withoutSourceSpans: Expression = this match {
    case Literal(_) | Variable(_) | Top => this
    case Lambda(parameter, body) => Lambda(parameter, body.withoutSourceSpans)
    case Application(function, argument) =>
      Application(function.withoutSourceSpans, argument.withoutSourceSpans)
    case TypeLambda(binder, body) => TypeLambda(binder, body.withoutSourceSpans)
    case TypeApplication(function, argumentType) =>
      TypeApplication(function.withoutSourceSpans, argumentType)
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
        inheritedTrait.withoutSourceSpans,
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

  def typeApplications(function: Expression, arguments: List[Type]): Expression = {
    arguments.foldLeft(function: Expression)(TypeApplication(_, _))
  }
}
