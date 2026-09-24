package cp.visualizer

import cp.fiobs.{CompilationError as FiobsCompilationError}
import cp.fitrie.{FiTrieCompilationError, FiTrieMergeError, RouteKey}
import cp.fitrie.elaboration.{CoercionError, FiTrieElaborationError}
import cp.language.compilation.CpFiTrieCompilationError
import cp.primitive.{PrimitiveType, PrimitiveValue}
import cp.language.diagnostics.CompilerDiagnostics

/** Target compilation diagnostics belong to the trie client, independently of language analysis. */
private[visualizer] object FiTrieDiagnostics {
  def render(error: CpFiTrieCompilationError): String = error match {
    case CpFiTrieCompilationError.ModuleNotCompiled(namespace) =>
      s"Module ${namespace.render} was not compiled."
    case CpFiTrieCompilationError.EntryPointNotFound(identifier) =>
      s"Entry point ${identifier.render} was not found. Define an ordinary `main` value."
    case CpFiTrieCompilationError.Definition(identifier, compilationError) =>
      s"While compiling ${identifier.render} to FiTrie: ${renderFiTrieCompilationError(compilationError)}"
  }

  private def renderFiTrieCompilationError(error: FiTrieCompilationError): String = error match {
    case FiTrieCompilationError.SourceElaboration(elaborationError) =>
      CompilerDiagnostics.render(FiobsCompilationError.Elaboration(elaborationError))
    case FiTrieCompilationError.TargetElaboration(elaborationError) =>
      renderFiTrieElaborationError(elaborationError)
  }

  private def renderFiTrieElaborationError(error: FiTrieElaborationError): String = error match {
    case FiTrieElaborationError.Typing(typeError) => CompilerDiagnostics.render(typeError)
    case FiTrieElaborationError.Coercion(coercionError) => renderCoercionError(coercionError)
    case FiTrieElaborationError.Merge(mergeError) => renderMergeError(mergeError)
    case FiTrieElaborationError.UnboundDecoratedTermVariable(index) =>
      s"Decorated term variable x$index is unbound."
    case FiTrieElaborationError.UnboundDecoratedGlobal(identifier) =>
      s"Decorated global ${identifier.render} is unavailable."
    case FiTrieElaborationError.ExpectedArrow(_, actualType) =>
      s"FiTrie application expected an arrow, but found ${actualType.render()}."
    case FiTrieElaborationError.ExpectedUniversal(_, actualType) =>
      s"FiTrie type application expected a universal type, but found ${actualType.render()}."
    case FiTrieElaborationError.ExpectedRecursive(_, actualType) =>
      s"FiTrie unfolding expected a recursive type, but found ${actualType.render()}."
    case FiTrieElaborationError.ExpectedRecord(_, label, actualType) =>
      s"FiTrie projection '$label' expected a record, but found ${actualType.render()}."
    case FiTrieElaborationError.UnexpectedType(_, actualType, expectedType) =>
      s"FiTrie elaboration produced ${actualType.render()}, but expected ${expectedType.render()}."
    case FiTrieElaborationError.NoPrimitiveSignature(operator, leftType, rightType) =>
      s"Operator '${operator.symbol}' has no signature for ${leftType.render()} and ${rightType.render()}."
  }

  private def renderCoercionError(error: CoercionError): String = error match {
    case CoercionError.NotSubtype(sourceType, targetType) =>
      s"Cannot coerce ${sourceType.render()} to ${targetType.render()}: it is not a subtype."
    case CoercionError.Merge(mergeError) => renderMergeError(mergeError)
  }

  private def renderMergeError(error: FiTrieMergeError): String = error match {
    case FiTrieMergeError.ConflictingTermination(route, primitiveType, leftPayload, rightPayload) =>
      val routeDescription = if (route.isEmpty) "the root" else route.map(renderRoute).mkString(" / ")
      s"Conflicting ${renderPrimitiveType(primitiveType)} results at $routeDescription: " +
        s"${renderPrimitiveValue(leftPayload)} and ${renderPrimitiveValue(rightPayload)}."
  }

  private def renderRoute(routeKey: RouteKey): String = routeKey match {
    case RouteKey.Application => "application"
    case RouteKey.TypeApplication => "type application"
    case RouteKey.Unfold => "unfolding"
    case RouteKey.Projection(label) => s"projection ${label.value}"
  }

  private def renderPrimitiveType(primitiveType: PrimitiveType): String = primitiveType match {
    case PrimitiveType.Integer => "Int"
    case PrimitiveType.Decimal => "Decimal"
    case PrimitiveType.Boolean => "Bool"
    case PrimitiveType.Text => "String"
    case PrimitiveType.Unit => "Unit"
  }

  private def renderPrimitiveValue(value: PrimitiveValue): String = value match {
    case PrimitiveValue.Integer(number) => number.toString
    case PrimitiveValue.Decimal(number) => number.toString
    case PrimitiveValue.Boolean(boolean) => boolean.toString
    case PrimitiveValue.Text(text) => s"\"$text\""
    case PrimitiveValue.UnitValue => "()"
  }

}
