package cp.language.elaboration

import cp.language.analysis.{CompletionCandidate, CompletionKind, CompletionSite}
import cp.language.core.{Expression, TypeSyntax}
import cp.language.parser.{CpParser, NameSite, SourceSyntax}
import cp.language.typing.{ApplicativeView, Type}
import cp.naming.Identifier
import cp.primitive.PrimitiveType
import cp.source.SourceSpan

import java.util.IdentityHashMap
import scala.collection.mutable

/**
 * Observes the existing elaboration derivation. It never resolves additional definitions during
 * that derivation: doing so could introduce false recursion through the analysis itself.
 * Global candidates are joined with established signatures after all definitions have been visited.
 */
private[language] final class SourceInspection(syntax: SourceSyntax) {
  private enum CapturedSite {
    case Term(site: NameSite, bindings: List[(String, Type)], typeScope: TypeScope)
    case Selection(site: NameSite, receiver: Expression)
    case TypeName(site: NameSite, typeScope: TypeScope)
  }

  private val sites = mutable.LinkedHashMap.empty[SourceSpan, CapturedSite]
  private val inferredTypes = new IdentityHashMap[Expression, (Type, TypeScope)]()

  private[elaboration] def observeExpression(expression: Expression, context: ElaborationContext): Unit = {
    syntax.term(expression).foreach { site =>
      sites.update(site.span, CapturedSite.Term(site, context.sourceBindings, context.typeScope))
    }
    syntax.selection(expression).foreach { case (site, receiver) =>
      sites.update(site.span, CapturedSite.Selection(site, receiver))
    }
  }

  private[elaboration] def observeType(inputType: TypeSyntax, scope: TypeScope): Unit = {
    syntax.inputType(inputType).foreach { site => sites.update(site.span, CapturedSite.TypeName(site, scope)) }
  }

  private[elaboration] def inferred(expression: Expression, inputType: Type, scope: TypeScope): Unit = {
    inferredTypes.put(expression, inputType -> scope)
  }

  def completions(
    moduleScope: ModuleScope,
    globalTypes: Map[Identifier, Type],
    signatures: Map[Identifier, SignatureDefinition]
  ): List[CompletionSite] = sites.toList.map { case (span, captured) =>
    val candidates = captured match {
      case CapturedSite.Term(site, bindings, scope) =>
        val locals = if (site.qualifier.nonEmpty) Nil else bindings.map { case (name, inputType) =>
          termCandidate(name, inputType, scope)
        }
        val shadowed = locals.map(_.name).toSet
        val globals = moduleScope.visibleNames(NameKind.Term, site.qualifier).flatMap { case (name, identifier) =>
          globalTypes.get(identifier).filter(_ => !shadowed.contains(name))
            .map(termCandidate(name, _, TypeScope.empty))
        }
        locals ++ globals
      case CapturedSite.Selection(_, receiver) =>
        Option(inferredTypes.get(receiver)).toList.flatMap { case (inputType, scope) =>
          // Projection uses exactly these fields. Recursive types require explicit unfolding, and
          // intersections combine repeated labels using the existing CP recordFields operation.
          inputType.recordFields.toList.map { case (label, fieldType) =>
            CompletionCandidate(label, CompletionKind.Field, fieldType.renderIn(scope.displayNames))
          }
        }
      case CapturedSite.TypeName(site, scope) =>
        val locals = if (site.qualifier.nonEmpty) Nil else {
          scope.sourceNames.filter(CpParser.isUnqualifiedTypeReference).map { name =>
            CompletionCandidate(name, CompletionKind.TypeParameter, name)
          }
        }
        val shadowed = locals.map(_.name).toSet
        val globals = moduleScope.visibleNames(NameKind.Type, site.qualifier).flatMap { case (name, identifier) =>
          val accessible = !shadowed.contains(name) &&
            (site.qualifier.nonEmpty || CpParser.isUnqualifiedTypeReference(name))
          signatures.get(identifier).filter(_ => accessible).map { signature =>
            val parameters = signature.parameters.flatMap(parameter => List(parameter, s"$parameter⁺"))
            CompletionCandidate(name, CompletionKind.Type, signature.bodyType.renderIn(parameters))
          }
        }
        val builtins = if (site.qualifier.nonEmpty) Nil else {
          (PrimitiveType.values.toList.map(Type.Primitive(_)) ++ List(Type.Top, Type.Bottom)).map { inputType =>
            CompletionCandidate(inputType.render, CompletionKind.Type, inputType.render)
          }
        }
        locals ++ globals ++ builtins.filterNot(candidate => shadowed.contains(candidate.name))
    }
    CompletionSite(span, candidates.distinct.sortBy(_.name))
  }

  private def termCandidate(name: String, inputType: Type, scope: TypeScope): CompletionCandidate = {
    val kind = inputType.termApplicationView match {
      case ApplicativeView.Applicable(_) => CompletionKind.Function
      case _ => CompletionKind.Variable
    }
    CompletionCandidate(name, kind, inputType.renderIn(scope.displayNames))
  }
}
