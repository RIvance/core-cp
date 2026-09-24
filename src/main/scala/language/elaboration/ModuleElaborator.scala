package cp.language.elaboration

import cp.language.core.*
import cp.language.analysis.CompletionSite
import cp.language.parser.SourceSyntax
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
    prepare(module, namespace, importedHeaders, None).mapError(_.error).flatMap(_.elaborateModule)
  }

  /** Analysis retains established facts from failed definitions and does not compile a runtime target. */
  private[language] def analyze(
    module: Module,
    namespace: Namespace,
    importedHeaders: Map[Namespace, ElaboratedModuleHeader],
    syntax: SourceSyntax
  ): ModuleSourceAnalysis = {
    val inspection = new SourceInspection(syntax)
    prepare(module, namespace, importedHeaders, Some(inspection)) match {
      case Result.Err(failure) =>
        val completions = failure.establishedContext.toList.flatMap { context =>
          inspection.completions(context.moduleScope, context.moduleScope.importedTermSignatures, context.signatures)
        }
        ModuleSourceAnalysis(None, completions, Some(failure.error))
      case Result.Ok(session) =>
        val result = session.elaborateModule
        ModuleSourceAnalysis(
          Some(session.establishedHeader),
          session.completions(inspection),
          result match {
            case Result.Err(error) => Some(error)
            case Result.Ok(_) => None
          }
        )
    }
  }

  private def prepare(
    module: Module,
    namespace: Namespace,
    importedHeaders: Map[Namespace, ElaboratedModuleHeader],
    inspection: Option[SourceInspection]
  ): Result[DefinitionElaboration, PreparationFailure] = {
    for {
      _ <- validateUniqueDefinitions(module).mapError(PreparationFailure(_, None))
      scope <- ModuleScope.create(module, namespace, importedHeaders)
        .mapError(error => PreparationFailure(CpElaborationError.NameResolution(error), None))
      initial = TypeExpansionContext(scope, scope.importedTypeDefinitions, inspection)
      expansion <- elaborateTypes(module, namespace, initial).mapError(PreparationFailure(_, Some(initial)))
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
      }.map(_.toMap).mapError(PreparationFailure(_, Some(expansion)))
    } yield new DefinitionElaboration(expansion, definitions, module.imports.map(_.targetNamespace).toSet)
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

/** A failed annotation does not invalidate the namespace and signatures already established before it. */
private final case class PreparationFailure(
  error: CpElaborationError,
  establishedContext: Option[TypeExpansionContext]
)

private[language] final case class ModuleSourceAnalysis(
  header: Option[ElaboratedModuleHeader],
  completions: List[CompletionSite],
  error: Option[CpElaborationError]
)

/**
 * Owns one module's resolution session. The expression elaborator requests global types
 * through this explicit dependency; resolving a dependency continues the current traversal.
 * Each local definition owns its signature and state, and its body is elaborated once.
 * State is confined to this session and never shared across module compilations.
 */
private final class DefinitionElaboration(
  expansion: TypeExpansionContext,
  definitions: Map[Identifier, Definition],
  dependencies: Set[Namespace]
) extends GlobalTypeResolver {
  private val importedTypes = expansion.moduleScope.importedTermSignatures
  private val context = ElaborationContext.module(expansion, this)

  def establishedHeader: ElaboratedModuleHeader = {
    val namespace = expansion.moduleScope.namespace
    ElaboratedModuleHeader(
      namespace,
      expansion.signatures.filter(_._1.scope == namespace),
      definitions.toList.flatMap { case (identifier, definition) => definition.knownType.map(identifier -> _) }.toMap,
      dependencies
    )
  }

  def completions(inspection: SourceInspection): List[CompletionSite] = {
    inspection.completions(
      expansion.moduleScope, importedTypes ++ establishedHeader.termSignatures, expansion.signatures
    )
  }

  def elaborateModule: Result[ElaboratedModule, CpElaborationError] = {
    elaborateAll.flatMap { elaborated =>
      val annotated = definitions.collect {
        case (identifier, definition) if definition.declaredType.nonEmpty => identifier
      }.toSet
      RecursiveDefinitions.lower(elaborated, annotated).map { lowered =>
        ElaboratedModule(establishedHeader, lowered)
      }
    }
  }

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

  def knownType: Option[Type] = declaredType.orElse(state match {
    case DefinitionState.Complete(definition) => Some(definition.definitionType)
    case _ => None
  })

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
