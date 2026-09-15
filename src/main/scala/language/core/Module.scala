package cp.language.core

import cp.naming.{Identifier, NameReference, Namespace}
import cp.util.Graph

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
  private val indexedTermDefinitions = definitions.collect {
    case definition: Declaration.Term => definition
  }.zipWithIndex

  def duplicateTermDefinitionNames: Set[String] = {
    definitions.flatMap(_.termDefinitionName).groupBy(identity).collect {
      case (name, occurrences) if occurrences.size > 1 => name
    }.toSet
  }

  def duplicateTypeDefinitionNames: Set[String] = {
    definitions.collect { case declaration: Declaration.TypeSignature => declaration }
      .groupBy(_.name)
      .collect { case (name, occurrences) if occurrences.size > 1 => name }
      .toSet
  }

  /** Strong components are ordered so every inter-component dependency is elaborated first. */
  def termDefinitionComponents(namespace: Namespace): List[TermDefinitionComponent] = {
    val termDefinitionsByName = indexedTermDefinitions.map { case (definition, index) =>
      definition.name -> (definition, index)
    }.toMap
    val termNames = termDefinitionsByName.keySet
    val dependencyGraph = indexedTermDefinitions.foldLeft(Graph.directed[String]) {
      case (graph, (definition, _)) =>
        val graphWithDefinition = graph.addVertex(definition.name)
        definition.initializer.freeTermVariables.flatMap {
          case NameReference.Unqualified(name) if termNames.contains(name) => Some(name)
          case NameReference.Qualified(Identifier(`namespace`, name)) if termNames.contains(name) => Some(name)
          case _ => None
        }.foldLeft(graphWithDefinition) { case (currentGraph, dependencyName) =>
          currentGraph.addEdge(dependencyName, definition.name)
        }
    }

    dependencyGraph.stronglyConnectedComponents.map { componentNames =>
      val orderedDefinitions = componentNames.toList.map(termDefinitionsByName).sortBy(_._2).map(_._1)
      TermDefinitionComponent(orderedDefinitions)
    }.toList
  }

  def withoutSourceSpans: Module = copy(definitions = definitions.map(_.withoutSourceSpans))
}

object Module {
  def apply(definitions: List[Declaration]): Module = Module(None, Nil, definitions)
}

final case class TermDefinitionComponent(definitions: List[Declaration.Term]) {
  require(definitions.nonEmpty, "a term-definition component cannot be empty")

  def definitionNames: Set[String] = definitions.map(_.name).toSet

  def isMutuallyRecursive: Boolean = definitions.size > 1
}

enum Declaration {
  case Term(name: String, initializer: Expression)
  case Method(member: Member)
  case TypeSignature(
    name: String,
    sortParameters: List[String],
    requiredInterface: Type,
    providedInterface: Type
  )

  def definitionName: String = this match {
    case Term(name, _) => name
    case Method(Member.Field(label, _)) => label
    case Method(Member.MethodPattern(constructorName, _, _, _, _, _)) => constructorName
    case TypeSignature(name, _, _, _) => name
  }

  def termDefinitionName: Option[String] = this match {
    case TypeSignature(_, _, _, _) => None
    case _ => Some(definitionName)
  }

  def withoutSourceSpans: Declaration = this match {
    case Term(name, initializer) => Term(name, initializer.withoutSourceSpans)
    case Method(member) => Method(member.withoutSourceSpans)
    case signature: TypeSignature => signature
  }
}
