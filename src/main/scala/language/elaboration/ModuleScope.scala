package cp.language.elaboration

import cp.language.core.{Declaration, ImportDeclaration, Module}
import cp.language.typing.Type
import cp.naming.{Identifier, NameReference, Namespace}
import cp.util.Result

enum NameResolutionError {
  case UnknownModule(namespace: Namespace)
  case ImportingCurrentModule(namespace: Namespace)
  case UnknownImportedMember(identifier: Identifier)
  case ModuleNotImported(namespace: Namespace)
  case UnknownTerm(reference: NameReference)
  case UnknownType(reference: NameReference)
  case KindMismatch(reference: NameReference, expected: NameKind, actual: NameKind, candidates: List[Identifier])
  case AmbiguousTerm(name: String, candidates: List[Identifier])
  case AmbiguousType(name: String, candidates: List[Identifier])
}

enum NameKind {
  case Term, Type

  def other: NameKind = this match {
    case Term => Type
    case Type => Term
  }
}

/** Visibility within one of CP's two independent name spaces. */
private final case class NameBindings(
  current: Map[String, Identifier],
  explicitImports: Map[String, Set[Identifier]],
  wildcardImports: Map[String, Set[Identifier]],
  importedIdentifiers: Set[Identifier]
) {
  def unqualified(name: String): Set[Identifier] = {
    current.get(name).map(Set(_))
      .orElse(explicitImports.get(name).filter(_.nonEmpty))
      .orElse(wildcardImports.get(name)).getOrElse(Set.empty)
  }

  def contains(identifier: Identifier, currentNamespace: Namespace): Boolean = {
    if (identifier.scope == currentNamespace) current.get(identifier.name).contains(identifier)
    else importedIdentifiers.contains(identifier)
  }

  def unqualifiedNames: Set[String] = current.keySet ++ explicitImports.keySet ++ wildcardImports.keySet

  def qualifiedNames(namespace: Namespace): Set[String] = {
    (current.valuesIterator ++ importedIdentifiers.iterator).filter(_.scope == namespace).map(_.name).toSet
  }
}

/** Owns visibility, ambiguity, and kind checking for module names. */
final class ModuleScope private (
  val namespace: Namespace,
  importedHeaders: Map[Namespace, ElaboratedModuleHeader],
  authorizedModules: Set[Namespace],
  terms: NameBindings,
  types: NameBindings
) {
  def resolveTerm(reference: NameReference): Result[Identifier, NameResolutionError] = {
    resolve(reference, NameKind.Term)
  }

  def resolveType(reference: NameReference): Result[Identifier, NameResolutionError] = {
    resolve(reference, NameKind.Type)
  }

  /** Optional lookup does not reinterpret a name from the other name space. */
  def findTerm(reference: NameReference): Result[Option[Identifier], NameResolutionError] = {
    candidates(reference, NameKind.Term).flatMap { found =>
      if (found.isEmpty) Result.Ok(None)
      else unique(reference, NameKind.Term, found).map(Some(_))
    }
  }

  def importedTermSignatures: Map[Identifier, Type] = {
    importedHeaders.valuesIterator.flatMap(_.termSignatures).toMap
  }

  def importedTypeDefinitions: Map[Identifier, SignatureDefinition] = {
    importedHeaders.valuesIterator.flatMap(_.typeDefinitions).toMap
  }

  /** Enumerates only names that ordinary lookup resolves uniquely with the requested spelling. */
  def visibleNames(kind: NameKind, qualifier: Option[Namespace]): List[(String, Identifier)] = {
    val bindings = kind match {
      case NameKind.Term => terms
      case NameKind.Type => types
    }
    val names = qualifier.fold(bindings.unqualifiedNames)(bindings.qualifiedNames)
    names.toList.sorted.flatMap { name =>
      val reference = qualifier.fold[NameReference](NameReference.Unqualified(name)) { namespace =>
        NameReference.Qualified(namespace.identifier(name))
      }
      resolve(reference, kind).toOption.map(name -> _)
    }
  }

  private def resolve(reference: NameReference, expected: NameKind): Result[Identifier, NameResolutionError] = {
    candidates(reference, expected).flatMap { found =>
      if (found.nonEmpty) unique(reference, expected, found)
      else candidates(reference, expected.other).flatMap { alternatives =>
        if (alternatives.nonEmpty) {
          Result.Err(NameResolutionError.KindMismatch(
            reference, expected, expected.other, alternatives.toList.sorted
          ))
        } else Result.Err(expected match {
          case NameKind.Term => NameResolutionError.UnknownTerm(reference)
          case NameKind.Type => NameResolutionError.UnknownType(reference)
        })
      }
    }
  }

  private def candidates(reference: NameReference, kind: NameKind): Result[Set[Identifier], NameResolutionError] = {
    val bindings = kind match {
      case NameKind.Term => terms
      case NameKind.Type => types
    }
    reference match {
      case NameReference.Unqualified(name) => Result.Ok(bindings.unqualified(name))
      case NameReference.Qualified(identifier) =>
        if (identifier.scope != namespace && !authorizedModules.contains(identifier.scope)) {
          Result.Err(NameResolutionError.ModuleNotImported(identifier.scope))
        } else Result.Ok(if (bindings.contains(identifier, namespace)) Set(identifier) else Set.empty)
    }
  }

  private def unique(
    reference: NameReference,
    kind: NameKind,
    found: Set[Identifier]
  ): Result[Identifier, NameResolutionError] = found.toList.sorted match {
    case identifier :: Nil => Result.Ok(identifier)
    case ambiguous =>
      val name = reference match {
        case NameReference.Unqualified(name) => name
        case NameReference.Qualified(identifier) => identifier.name
      }
      Result.Err(kind match {
        case NameKind.Term => NameResolutionError.AmbiguousTerm(name, ambiguous)
        case NameKind.Type => NameResolutionError.AmbiguousType(name, ambiguous)
      })
  }
}

object ModuleScope {
  def create(
    module: Module,
    namespace: Namespace,
    importedHeaders: Map[Namespace, ElaboratedModuleHeader]
  ): Result[ModuleScope, NameResolutionError] = {
    val currentTermIdentifiers = module.termMembers.map { member =>
      member.definitionName -> namespace.identifier(member.definitionName)
    }.toMap
    val currentTypeIdentifiers = module.definitions.collect {
      case Declaration.TypeSignature(name, _, _, _) => name -> namespace.identifier(name)
    }.toMap

    validateImports(module.imports, namespace, importedHeaders).map { _ =>
      val authorizedModules = module.imports.map(_.targetNamespace).toSet
      def bindings(
        current: Map[String, Identifier],
        members: ElaboratedModuleHeader => Set[Identifier]
      ): NameBindings = NameBindings(
        current,
        importedMembers(module.imports, importedHeaders, members),
        wildcardMembers(module.imports, importedHeaders, members),
        importedHeaders.valuesIterator.flatMap(members).toSet
      )
      new ModuleScope(
        namespace,
        importedHeaders,
        authorizedModules,
        bindings(currentTermIdentifiers, _.termSignatures.keySet),
        bindings(currentTypeIdentifiers, _.typeDefinitions.keySet)
      )
    }
  }

  private def validateImports(
    imports: List[ImportDeclaration],
    currentNamespace: Namespace,
    importedHeaders: Map[Namespace, ElaboratedModuleHeader]
  ): Result[Unit, NameResolutionError] = {
    imports.foldLeft(Result.Ok(()): Result[Unit, NameResolutionError]) { (validated, declaration) =>
      validated.flatMap { _ =>
        if (declaration.targetNamespace == currentNamespace) {
          Result.Err(NameResolutionError.ImportingCurrentModule(currentNamespace))
        } else {
          importedHeaders.get(declaration.targetNamespace) match {
            case None => Result.Err(NameResolutionError.UnknownModule(declaration.targetNamespace))
            case Some(header) => declaration match {
              case ImportDeclaration.Member(identifier)
                  if !header.termSignatures.contains(identifier) &&
                    !header.typeDefinitions.contains(identifier) =>
                Result.Err(NameResolutionError.UnknownImportedMember(identifier))
              case _ => Result.Ok(())
            }
          }
        }
      }
    }
  }

  private def importedMembers(
    imports: List[ImportDeclaration],
    headers: Map[Namespace, ElaboratedModuleHeader],
    members: ElaboratedModuleHeader => Set[Identifier]
  ): Map[String, Set[Identifier]] = {
    imports.collect { case ImportDeclaration.Member(identifier) => identifier }
      .filter(identifier => members(headers(identifier.scope)).contains(identifier))
      .groupMap(_.name)(identity)
      .view.mapValues(_.toSet).toMap
  }

  private def wildcardMembers(
    imports: List[ImportDeclaration],
    headers: Map[Namespace, ElaboratedModuleHeader],
    members: ElaboratedModuleHeader => Set[Identifier]
  ): Map[String, Set[Identifier]] = {
    imports.collect { case ImportDeclaration.All(namespace) => namespace }
      .flatMap(namespace => members(headers(namespace)))
      .groupMap(_.name)(identity)
      .view.mapValues(_.toSet).toMap
  }
}
