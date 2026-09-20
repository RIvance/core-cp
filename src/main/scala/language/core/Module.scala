package cp.language.core

import cp.naming.{Identifier, Namespace}

enum ImportDeclaration {
  /** Authorizes qualified access to a module without introducing unqualified members. */
  case Module(namespace: Namespace)

  /** Introduces one module member for unqualified access and also authorizes its module. */
  case Member(identifier: Identifier)

  /** Introduces every member of a module for unqualified access and also authorizes its module. */
  case All(namespace: Namespace)

  def targetNamespace: Namespace = this match {
    case ImportDeclaration.Module(namespace) => namespace
    case ImportDeclaration.Member(identifier) => identifier.scope
    case ImportDeclaration.All(namespace) => namespace
  }
}

/** A parsed CP compilation unit; file-based module identity is assigned after parsing. */
final case class Module(
  declaredNamespace: Option[Namespace],
  imports: List[ImportDeclaration],
  definitions: List[Declaration]
) {
  /** Surface declaration spellings share one semantic member boundary. */
  def termMembers: List[Member] = definitions.flatMap {
    case Declaration.Term(name, initializer, declaredType) => Some(Member.Field(name, initializer, declaredType))
    case Declaration.Method(member) => Some(member)
    case _: Declaration.TypeSignature => None
  }

  def duplicateTermDefinitionNames: Set[String] = {
    termMembers.map(_.definitionName).groupBy(identity).collect {
      case (name, occurrences) if occurrences.size > 1 => name
    }.toSet
  }

  def duplicateTypeDefinitionNames: Set[String] = {
    definitions.collect { case declaration: Declaration.TypeSignature => declaration }
      .groupBy(_.name)
      .collect { case (name, occurrences) if occurrences.size > 1 => name }
      .toSet
  }

  def withoutSourceSpans: Module = copy(definitions = definitions.map(_.withoutSourceSpans))
}

object Module {
  def apply(definitions: List[Declaration]): Module = Module(None, Nil, definitions)
}

enum Declaration {
  /** The complete declared signature is independent of expression-level ascriptions. */
  case Term(name: String, initializer: Expression, declaredType: Option[TypeSyntax])
  case Method(member: Member)
  case TypeSignature(
    name: String,
    sortParameters: List[String],
    requiredInterface: TypeSyntax,
    providedInterface: TypeSyntax
  )

  def definitionName: String = this match {
    case Term(name, _, _) => name
    case Method(member) => member.definitionName
    case TypeSignature(name, _, _, _) => name
  }

  def withoutSourceSpans: Declaration = this match {
    case Term(name, initializer, declaredType) => Term(name, initializer.withoutSourceSpans, declaredType)
    case Method(member) => Method(member.withoutSourceSpans)
    case signature: TypeSignature => signature
  }
}
