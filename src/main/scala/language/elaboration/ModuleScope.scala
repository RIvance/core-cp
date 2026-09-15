package cp.language.elaboration

import cp.language.core.{Declaration, ImportDeclaration, Module}
import cp.naming.{Identifier, NameReference, Namespace}
import cp.util.Result

enum NameResolutionError {
  case UnknownModule(namespace: Namespace)
  case ImportingCurrentModule(namespace: Namespace)
  case UnknownImportedMember(identifier: Identifier)
  case ModuleNotImported(namespace: Namespace)
  case UnknownTerm(reference: NameReference)
  case UnknownType(reference: NameReference)
  case AmbiguousTerm(name: String, candidates: List[Identifier])
  case AmbiguousType(name: String, candidates: List[Identifier])
}

/** Owns the absolute and unqualified visibility rules for one module. */
final case class ModuleScope private (
  namespace: Namespace,
  currentTermIdentifiers: Map[String, Identifier],
  currentTypeIdentifiers: Map[String, Identifier],
  importedHeaders: Map[Namespace, ElaboratedModuleHeader],
  authorizedModules: Set[Namespace],
  explicitTermImports: Map[String, Set[Identifier]],
  explicitTypeImports: Map[String, Set[Identifier]],
  wildcardTermImports: Map[String, Set[Identifier]],
  wildcardTypeImports: Map[String, Set[Identifier]]
) {
  def resolveTerm(reference: NameReference): Result[Identifier, NameResolutionError] = reference match {
    case NameReference.Unqualified(name) =>
      currentTermIdentifiers.get(name) match {
        case Some(identifier) => Result.Ok(identifier)
        case None => resolveImportedTerm(name)
      }
    case NameReference.Qualified(identifier) => resolveQualifiedTerm(identifier)
  }

  def resolveType(reference: NameReference): Result[Identifier, NameResolutionError] = reference match {
    case NameReference.Unqualified(name) =>
      currentTypeIdentifiers.get(name) match {
        case Some(identifier) => Result.Ok(identifier)
        case None => resolveImportedType(name)
      }
    case NameReference.Qualified(identifier) => resolveQualifiedType(identifier)
  }

  def importedTermSignatures: Map[Identifier, cp.language.core.Type] = {
    importedHeaders.valuesIterator.flatMap(_.termSignatures).toMap
  }

  def importedTypeDefinitions: Map[Identifier, SignatureDefinition] = {
    importedHeaders.valuesIterator.flatMap(_.typeDefinitions).toMap
  }

  private def resolveImportedTerm(name: String): Result[Identifier, NameResolutionError] = {
    explicitTermImports.get(name).filter(_.nonEmpty) match {
      case Some(candidates) => uniqueTerm(name, candidates)
      case None => wildcardTermImports.get(name) match {
        case Some(candidates) => uniqueTerm(name, candidates)
        case None => Result.Err(NameResolutionError.UnknownTerm(NameReference.Unqualified(name)))
      }
    }
  }

  private def resolveImportedType(name: String): Result[Identifier, NameResolutionError] = {
    explicitTypeImports.get(name).filter(_.nonEmpty) match {
      case Some(candidates) => uniqueType(name, candidates)
      case None => wildcardTypeImports.get(name) match {
        case Some(candidates) => uniqueType(name, candidates)
        case None => Result.Err(NameResolutionError.UnknownType(NameReference.Unqualified(name)))
      }
    }
  }

  private def resolveQualifiedTerm(identifier: Identifier): Result[Identifier, NameResolutionError] = {
    if (identifier.scope == namespace) {
      if (currentTermIdentifiers.contains(identifier.name)) {
        Result.Ok(identifier)
      } else {
        Result.Err(NameResolutionError.UnknownTerm(NameReference.Qualified(identifier)))
      }
    } else if (!authorizedModules.contains(identifier.scope)) {
      Result.Err(NameResolutionError.ModuleNotImported(identifier.scope))
    } else if (importedHeaders(identifier.scope).termSignatures.contains(identifier)) {
      Result.Ok(identifier)
    } else {
      Result.Err(NameResolutionError.UnknownTerm(NameReference.Qualified(identifier)))
    }
  }

  private def resolveQualifiedType(identifier: Identifier): Result[Identifier, NameResolutionError] = {
    if (identifier.scope == namespace) {
      if (currentTypeIdentifiers.contains(identifier.name)) {
        Result.Ok(identifier)
      } else {
        Result.Err(NameResolutionError.UnknownType(NameReference.Qualified(identifier)))
      }
    } else if (!authorizedModules.contains(identifier.scope)) {
      Result.Err(NameResolutionError.ModuleNotImported(identifier.scope))
    } else if (importedHeaders(identifier.scope).typeDefinitions.contains(identifier)) {
      Result.Ok(identifier)
    } else {
      Result.Err(NameResolutionError.UnknownType(NameReference.Qualified(identifier)))
    }
  }

  private def uniqueTerm(
    name: String,
    candidates: Set[Identifier]
  ): Result[Identifier, NameResolutionError] = {
    candidates.toList.sortBy(_.render) match {
      case identifier :: Nil => Result.Ok(identifier)
      case ambiguous => Result.Err(NameResolutionError.AmbiguousTerm(name, ambiguous))
    }
  }

  private def uniqueType(
    name: String,
    candidates: Set[Identifier]
  ): Result[Identifier, NameResolutionError] = {
    candidates.toList.sortBy(_.render) match {
      case identifier :: Nil => Result.Ok(identifier)
      case ambiguous => Result.Err(NameResolutionError.AmbiguousType(name, ambiguous))
    }
  }
}

object ModuleScope {
  def create(
    module: Module,
    namespace: Namespace,
    importedHeaders: Map[Namespace, ElaboratedModuleHeader]
  ): Result[ModuleScope, NameResolutionError] = {
    val currentTermIdentifiers = module.definitions.collect {
      case Declaration.Term(name, _) => name -> namespace.identifier(name)
      case declaration: Declaration.Method =>
        declaration.definitionName -> namespace.identifier(declaration.definitionName)
    }.toMap
    val currentTypeIdentifiers = module.definitions.collect {
      case Declaration.TypeSignature(name, _, _, _) => name -> namespace.identifier(name)
    }.toMap

    validateImports(module.imports, namespace, importedHeaders).map { _ =>
      val authorizedModules = module.imports.map(_.targetNamespace).toSet
      val explicitTermImports = importedMembers(module.imports, importedHeaders, _.termSignatures.keySet)
      val explicitTypeImports = importedMembers(module.imports, importedHeaders, _.typeDefinitions.keySet)
      val wildcardTermImports = wildcardMembers(module.imports, importedHeaders, _.termSignatures.keySet)
      val wildcardTypeImports = wildcardMembers(module.imports, importedHeaders, _.typeDefinitions.keySet)
      ModuleScope(
        namespace,
        currentTermIdentifiers,
        currentTypeIdentifiers,
        importedHeaders,
        authorizedModules,
        explicitTermImports,
        explicitTypeImports,
        wildcardTermImports,
        wildcardTypeImports
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
