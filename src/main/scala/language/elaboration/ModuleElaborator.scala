package cp.language.elaboration

import cp.language.core.*
import cp.language.typing.Type
import cp.naming.{Identifier, Namespace}
import cp.util.{Graph, Result}

/** Owns module preparation, demand-driven type inference, and recursive component lowering. */
object CpElaborator {
  def elaborate(
    module: Module,
    namespace: Namespace,
    importedHeaders: Map[Namespace, ElaboratedModuleHeader]
  ): Result[ElaboratedModule, CpElaborationError] = {
    for {
      _ <- validateUniqueDefinitions(module)
      scope <- ModuleScope.create(module, namespace, importedHeaders).mapError(CpElaborationError.NameResolution(_))
      expansion <- elaborateTypes(module, namespace, TypeExpansionContext(scope, scope.importedTypeDefinitions))
      definitions <- Result.traverse(module.termMembers) { member =>
        val annotation = member.declaredType match {
          case None => Result.Ok(None)
          case Some(inputType) => TypeExpansion.expand(inputType, expansion)
            .mapError(CpElaborationError.TypeExpansion(_)).map(Some(_))
        }
        annotation.map { declaredType =>
          val identifier = namespace.identifier(member.definitionName)
          identifier -> new Definition(identifier, member, declaredType)
        }
      }.map(_.toMap)
      elaborated <- DefinitionElaboration(expansion, definitions).elaborateAll
      annotated = definitions.collect {
        case (identifier, definition) if definition.declaredType.nonEmpty => identifier
      }
      lowered <- RecursiveDefinitions.lower(elaborated, annotated.toSet)
    } yield ElaboratedModule(
      ElaboratedModuleHeader(
        namespace,
        expansion.signatures.filter(_._1.scope == namespace),
        elaborated.view.mapValues(_.definitionType).toMap,
        module.imports.map(_.targetNamespace).toSet
      ),
      lowered
    )
  }

  private def validateUniqueDefinitions(module: Module): Result[Unit, CpElaborationError] = {
    module.duplicateTermDefinitionNames.toList.sorted match {
      case name :: _ => Result.Err(CpElaborationError.DuplicateTermDefinition(name))
      case Nil => module.duplicateTypeDefinitionNames.toList.sorted match {
        case name :: _ => Result.Err(CpElaborationError.DuplicateTypeDefinition(name))
        case Nil => Result.Ok(())
      }
    }
  }

  private def elaborateTypes(
    module: Module,
    namespace: Namespace,
    initial: TypeExpansionContext
  ): Result[TypeExpansionContext, CpElaborationError] = {
    val declarations = module.definitions.collect { case declaration: Declaration.TypeSignature => declaration }
      .map(declaration => namespace.identifier(declaration.name) -> declaration).toMap
    val initialGraph = Graph.directed[Identifier].addVertices(declarations.keys)
    Result.traverse(declarations.toList) { case (identifier, declaration) =>
      val bound = declaration.sortParameters.toSet
      val references = declaration.requiredInterface.referencedNames(bound) ++
        declaration.providedInterface.referencedNames(bound)
      Result.traverse(references.toList) { reference =>
        initial.moduleScope.resolveType(reference).mapError(CpElaborationError.NameResolution(_))
      }.map(dependencies => identifier -> dependencies.filter(_.scope == namespace))
    }.flatMap { dependencies =>
      val graph = dependencies.foldLeft(initialGraph) { case (graph, (identifier, references)) =>
        references.foldLeft(graph)((current, reference) => current.addEdge(reference, identifier))
      }
      val components = graph.stronglyConnectedComponents.toList
      components.find(component => component.size > 1 || component.exists(graph.isSelfLoop)) match {
        case Some(cycle) => Result.Err(CpElaborationError.RecursiveTypeDefinitions(cycle.toList.sortBy(_.name)))
        case None => components.flatten.foldLeft(Result.Ok(initial): Result[TypeExpansionContext, CpElaborationError]) {
          (accumulated, identifier) => accumulated.flatMap { context =>
            TypeExpansion.signature(declarations(identifier), context)
              .mapError(CpElaborationError.TypeExpansion(_))
              .map(context.register(identifier, _))
          }
        }
      }
    }
  }
}

/**
 * Owns one module's resolution session. The expression elaborator requests global types
 * through this explicit dependency; resolving a dependency continues the current traversal.
 * Each local definition owns its signature and state, and its body is elaborated once.
 * State is confined to this session and never shared across module compilations.
 */
private final class DefinitionElaboration(
  expansion: TypeExpansionContext,
  definitions: Map[Identifier, Definition]
) extends GlobalTypeResolver {
  private val importedTypes = expansion.moduleScope.importedTermSignatures
  private val context = ElaborationContext.module(expansion, this)

  override def typeOf(identifier: Identifier): ElaborationResult[Type] = definitions.get(identifier) match {
    case Some(definition) => definition.declaredType match {
      case Some(inputType) => Result.Ok(inputType)
      case None => definition.elaborate(context).map(_.definitionType)
    }
    case None =>
      require(importedTypes.contains(identifier), "a resolved global must have a local or imported definition")
      Result.Ok(importedTypes(identifier))
  }

  def elaborateAll: Result[Map[Identifier, ElaboratedTermDefinition], CpElaborationError] = {
    Result.traverse(definitions.toList.sortBy(_._1.name)) { case (identifier, definition) =>
      definition.elaborate(context).map(identifier -> _)
    }.map(_.toMap)
  }
}

private enum DefinitionState {
  case Pending
  case Resolving
  case Complete(definition: ElaboratedTermDefinition)
  case Failed(error: CpElaborationError)
}

private final class Definition(
  identifier: Identifier,
  member: Member,
  val declaredType: Option[Type]
) {
  private var state: DefinitionState = DefinitionState.Pending

  def elaborate(context: ElaborationContext): ElaborationResult[ElaboratedTermDefinition] = state match {
    case DefinitionState.Complete(definition) => Result.Ok(definition)
    case DefinitionState.Failed(error) => Result.Err(error)
    case DefinitionState.Resolving => Result.Err(CpElaborationError.RecursiveDeclarationRequiresType(identifier.name))
    case DefinitionState.Pending =>
      state = DefinitionState.Resolving
      val result = ExpressionElaborator(context).inferMember(member).map { elaborated =>
        ElaboratedTermDefinition(
          identifier, elaborated.value.expression, elaborated.value.inferredType, ModuleDefinitionVisibility.Exported
        )
      }
      state = result match {
        case Result.Ok(definition) => DefinitionState.Complete(definition)
        case Result.Err(error) => DefinitionState.Failed(error)
      }
      result
  }
}
