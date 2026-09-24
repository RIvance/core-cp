package cp.language.parser

import cp.language.core.{Expression, Module, TypeSyntax}
import cp.naming.Namespace
import cp.source.SourceSpan

import java.util.IdentityHashMap

private[language] final case class NameSite(span: SourceSpan, qualifier: Option[Namespace])

/**
 * Parser-owned occurrences. Object identity distinguishes identical syntax written at different
 * positions; it is local to this parse and is never used as a persistent symbol identity.
 * Backtracking may record discarded nodes. Only nodes visited by elaboration produce source facts.
 */
private[language] final class SourceSyntax {
  private val termNames = new IdentityHashMap[Expression, NameSite]()
  private val typeNames = new IdentityHashMap[TypeSyntax, NameSite]()
  private val selections = new IdentityHashMap[Expression, (NameSite, Expression)]()

  def term(expression: Expression): Option[NameSite] = Option(termNames.get(expression))
  def inputType(inputType: TypeSyntax): Option[NameSite] = Option(typeNames.get(inputType))
  def selection(expression: Expression): Option[(NameSite, Expression)] = Option(selections.get(expression))

  def withTerm(expression: Expression, site: NameSite): Expression = {
    termNames.put(expression, site)
    expression
  }

  def withType(inputType: TypeSyntax, site: NameSite): TypeSyntax = {
    typeNames.put(inputType, site)
    inputType
  }

  def withSelection(expression: Expression, receiver: Expression, site: NameSite): Expression = {
    selections.put(expression, site -> receiver)
    expression
  }
}

private[language] final case class ParsedSource(module: Module, syntax: SourceSyntax)
