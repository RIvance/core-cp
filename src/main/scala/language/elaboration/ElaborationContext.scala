package cp.language.elaboration

import cp.fiobs.{Term, TypeContext}
import cp.fiobs.binding.Binding.*
import cp.fiobs.typing.Disjointness
import cp.language.core.TypeSyntax
import cp.language.typing.Type
import cp.naming.{Identifier, NameReference}
import cp.util.Result

private final case class LocalBinding(name: String, value: ElaboratedExpression) {
  def underTermBinder: LocalBinding = copy(value = value.copy(expression = value.expression.shiftTermVariables(1)))

  def underTypeBinder: LocalBinding = copy(value = ElaboratedExpression(
    value.expression.shiftTypeVariables(1),
    value.inferredType.shiftTypeVariables(1)
  ))
}

/** A resolved global's type may be declared already or require elaborating its definition. */
private[elaboration] trait GlobalTypeResolver {
  def typeOf(identifier: Identifier): ElaborationResult[Type]
}

/** Owns lexical bindings, including projections introduced by open, independently of source spelling. */
private[elaboration] final case class ElaborationContext private (
  typeExpansion: TypeExpansionContext,
  typeScope: TypeScope,
  typeContext: TypeContext,
  localBindings: List[LocalBinding],
  globalTypes: GlobalTypeResolver,
  patternConstructorTypes: List[(String, Type)]
) {
  def resolveTerm(reference: NameReference): ElaborationResult[ElaboratedExpression] = {
    localTerm(reference) match {
      case Some(value) => Result.Ok(value)
      case None =>
        typeExpansion.moduleScope.resolveTerm(reference)
          .mapError(CpElaborationError.NameResolution(_)).flatMap(globalTerm)
    }
  }

  /** Optional term lookup, used when a constructor signature is an inference source. */
  def findTerm(reference: NameReference): ElaborationResult[Option[ElaboratedExpression]] = {
    localTerm(reference) match {
      case Some(value) => Result.Ok(Some(value))
      case None => typeExpansion.moduleScope.findTerm(reference)
        .mapError(CpElaborationError.NameResolution(_)).flatMap {
          case Some(identifier) => globalTerm(identifier).map(Some(_))
          case None => Result.Ok(None)
        }
    }
  }

  private def localTerm(reference: NameReference): Option[ElaboratedExpression] = reference match {
    case NameReference.Unqualified(name) => localBindings.find(_.name == name).map(_.value)
    case NameReference.Qualified(_) => None
  }

  private def globalTerm(identifier: Identifier): ElaborationResult[ElaboratedExpression] = {
    globalTypes.typeOf(identifier).map(ElaboratedExpression(Term.Global(identifier), _))
  }

  def withTerm(name: Option[String], inputType: Type): ElaborationContext = {
    val shifted = localBindings.map(_.underTermBinder)
    val bindings = name match {
      case Some(sourceName) => LocalBinding(sourceName, ElaboratedExpression(Term.Variable(0), inputType)) :: shifted
      case None => shifted
    }
    copy(localBindings = bindings)
  }

  def withTerm(name: String, inputType: Type): ElaborationContext = withTerm(Some(name), inputType)

  /** A lazy local definition substitutes its value without introducing a target binder. */
  def withValue(name: String, value: ElaboratedExpression): ElaborationContext = {
    copy(localBindings = LocalBinding(name, value) :: localBindings)
  }

  def withOpenedFields(receiver: Term, inputType: Type): ElaborationContext = {
    val fields = inputType.recordFields.toList.sortBy(_._1).map { case (label, fieldType) =>
      LocalBinding(label, ElaboratedExpression(
        Term.Annotation(Term.Projection(receiver, label), TypeTranslation.toFiobs(fieldType)),
        fieldType
      ))
    }
    copy(localBindings = fields ++ localBindings)
  }

  def withType(name: String, bound: Type): ElaborationContext = copy(
    typeScope = typeScope.withType(name),
    typeContext = typeContext.extend(TypeTranslation.toFiobs(bound)),
    localBindings = localBindings.map(_.underTypeBinder),
    patternConstructorTypes = patternConstructorTypes.map { case (label, inputType) =>
      label -> inputType.shiftTypeVariables(1)
    }
  )

  def withPatternConstructors(interfaceType: Type): ElaborationContext = {
    copy(patternConstructorTypes = interfaceType.recordFields.toList ++ patternConstructorTypes)
  }

  def lookupPatternConstructor(name: String): Option[Type] = {
    patternConstructorTypes.collectFirst { case (`name`, constructorType) => constructorType }
  }

  def expand(inputType: TypeSyntax): ElaborationResult[Type] = {
    TypeExpansion.expand(inputType, typeExpansion, typeScope)
      .mapError(CpElaborationError.TypeExpansion(_))
  }

  def isSubtype(sourceType: Type, targetType: Type): Boolean = {
    TypeTranslation.toFiobs(sourceType).isSubtypeOf(TypeTranslation.toFiobs(targetType), typeContext)
  }

  def areDisjoint(leftType: Type, rightType: Type): Boolean = {
    Disjointness(typeContext).relates(TypeTranslation.toFiobs(leftType), TypeTranslation.toFiobs(rightType))
  }
}

private[elaboration] object ElaborationContext {
  def module(expansion: TypeExpansionContext, globalTypes: GlobalTypeResolver): ElaborationContext = {
    ElaborationContext(expansion, TypeScope.empty, TypeContext.empty, Nil, globalTypes, Nil)
  }
}
