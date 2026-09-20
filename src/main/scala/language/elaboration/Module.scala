package cp.language.elaboration

import cp.fiobs.Term
import cp.language.typing.Type
import cp.naming.{Identifier, Namespace}

/** The resolved Fiobs expression synthesizes the translation of inferredType in its lexical context. */
final case class ElaboratedExpression(expression: Term, inferredType: Type)

enum ModuleDefinitionVisibility {
  case Exported, Internal
}

final case class ElaboratedTermDefinition(
  identifier: Identifier,
  initializer: Term,
  definitionType: Type,
  visibility: ModuleDefinitionVisibility
)

/** Information sufficient to elaborate modules that depend on this module. */
final case class ElaboratedModuleHeader(
  namespace: Namespace,
  typeDefinitions: Map[Identifier, SignatureDefinition],
  termSignatures: Map[Identifier, Type],
  dependencies: Set[Namespace]
)

/** Elaborated definitions are addressable independently and have no declaration-order semantics. */
final case class ElaboratedModule(
  header: ElaboratedModuleHeader,
  termDefinitions: Map[Identifier, ElaboratedTermDefinition]
) {
  def namespace: Namespace = header.namespace

  def globalTermTypes: Map[Identifier, Type] = {
    termDefinitions.view.mapValues(_.definitionType).toMap
  }
}
