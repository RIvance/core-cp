package cp.language.elaboration

import cp.fiobs.{Expr as FiobsExpression, SurfaceType}
import cp.language.core.Type
import cp.naming.{Identifier, Namespace}
import cp.util.Result

enum TermBindingRecursion {
  case Ordinary, Recursive
}

/** A local binding used while CP constructs are lowered to Fiobs expressions. */
final case class ElaboratedTermBinding(
  name: String,
  initializer: FiobsExpression,
  bindingType: Type,
  recursion: TermBindingRecursion
) {
  def scopeOver(
    body: ElaboratedExpression
  ): Result[ElaboratedExpression, CpElaborationError] = {
    for {
      translatedBindingType <- TypeTranslation.toSurfaceType(bindingType)
        .mapError(CpElaborationError.TypeTranslation(_))
      translatedBodyType <- TypeTranslation.toSurfaceType(body.inferredType)
        .mapError(CpElaborationError.TypeTranslation(_))
    } yield {
      val bindingInitializer = recursion match {
        case TermBindingRecursion.Ordinary => initializer
        case TermBindingRecursion.Recursive =>
          FiobsExpression.Fix(name, translatedBindingType, initializer)
      }
      ElaboratedExpression(
        FiobsExpression.Application(
          FiobsExpression.Annotation(
            FiobsExpression.Lambda(name, body.expression),
            SurfaceType.Arrow(translatedBindingType, translatedBodyType)
          ),
          bindingInitializer
        ),
        body.inferredType
      )
    }
  }
}

enum ModuleDefinitionVisibility {
  case Exported, Internal
}

final case class ElaboratedTermDefinition(
  identifier: Identifier,
  initializer: FiobsExpression,
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
