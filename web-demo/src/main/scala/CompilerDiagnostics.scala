package cp.visualizer

import cp.fiobs.{ApplicableForm, CompilationError as FiobsCompilationError}
import cp.fiobs.elaboration.{ElaborationError as FiobsElaborationError}
import cp.fiobs.typing.TypeError
import cp.fitrie.{FiTrieCompilationError, FiTrieMergeError, RouteKey}
import cp.fitrie.elaboration.{CoercionError, FiTrieElaborationError}
import cp.language.CpCompilationError
import cp.language.compilation.{CpFiTrieCompilationError, ModuleSourceError}
import cp.language.elaboration.*
import cp.language.parser.ParsingError
import cp.primitive.{PrimitiveType, PrimitiveValue}
import cp.source.SourceSpan

/** Human-readable rendering of structured compiler errors at the browser boundary. */
private[visualizer] object CompilerDiagnostics {
  def sourceSpan(error: CpCompilationError): Option[SourceSpan] = error match {
    case CpCompilationError.Elaboration(_, elaborationError) => elaborationError.location
    case _ => None
  }

  def render(error: CpCompilationError): String = error match {
    case CpCompilationError.NoSourceModules => "No CP source modules were provided."
    case CpCompilationError.Source(sourceError) => renderModuleSourceError(sourceError)
    case CpCompilationError.Parsing(_, parsingError) => renderParsingError(parsingError)
    case CpCompilationError.DuplicateModule(namespace, paths) =>
      s"Module ${namespace.render} is declared by multiple files: ${paths.mkString(", ")}."
    case CpCompilationError.UnknownImportedModule(importer, imported) =>
      s"Module ${importer.render} imports ${imported.render}, but that module is not available."
    case CpCompilationError.CircularModuleDependencies(namespaces) =>
      s"The module dependency cycle is: ${namespaces.map(_.render).mkString(" → ")}."
    case CpCompilationError.Elaboration(namespace, elaborationError) =>
      s"In module ${namespace.render}: ${renderCpElaborationError(elaborationError)}"
    case CpCompilationError.Fiobs(identifier, compilationError) =>
      s"While checking ${identifier.render}: ${renderFiobsCompilationError(compilationError)}"
  }

  def render(error: CpFiTrieCompilationError): String = error match {
    case CpFiTrieCompilationError.ModuleNotCompiled(namespace) =>
      s"Module ${namespace.render} was not compiled."
    case CpFiTrieCompilationError.EntryPointNotFound(identifier) =>
      s"Entry point ${identifier.render} was not found. Define an ordinary `main` value."
    case CpFiTrieCompilationError.Definition(identifier, compilationError) =>
      s"While compiling ${identifier.render} to FiTrie: ${renderFiTrieCompilationError(compilationError)}"
  }

  private def renderModuleSourceError(error: ModuleSourceError): String = error match {
    case ModuleSourceError.ModuleIdentityUnavailable =>
      "The module has no declaration and its source path cannot provide a module name."
    case ModuleSourceError.InvalidSourceFileExtension(path) =>
      s"Source file $path must use the .cp extension."
    case ModuleSourceError.InvalidModuleFileName(path) =>
      s"Source file $path must have a PascalCase module name."
  }

  private def renderParsingError(error: ParsingError): String = error match {
    case ParsingError.Syntax(message, _, _) => message
    case ParsingError.SignatureUsedAsOrdinaryTypeApplication(signatureName) =>
      s"Signature '$signatureName' cannot be used as an ordinary type application."
  }

  private def renderCpElaborationError(error: CpElaborationError): String = error match {
    case CpElaborationError.Located(_, underlying) => renderCpElaborationError(underlying)
    case CpElaborationError.NameResolution(resolutionError) =>
      renderNameResolutionError(resolutionError)
    case CpElaborationError.TypeExpansion(expansionError) =>
      renderTypeExpansionError(expansionError)
    case CpElaborationError.TypeTranslation(translationError) =>
      renderTypeTranslationError(translationError)
    case CpElaborationError.UnboundTermVariable(name, scope) =>
      s"Term '$name' is not in scope.${availableNames(scope)}"
    case CpElaborationError.CannotInfer(_) =>
      "Cannot infer the type of this expression; add an explicit type annotation."
    case CpElaborationError.TypeMismatch(_, actualType, expectedType) =>
      s"Type mismatch: found ${actualType.render}, but expected ${expectedType.render}."
    case CpElaborationError.LambdaParameterMismatch(actualType, expectedType) =>
      s"Lambda parameter type ${actualType.render} does not match ${expectedType.render}."
    case CpElaborationError.TypeLambdaBoundMismatch(actualBound, expectedBound) =>
      s"Type-lambda bound ${actualBound.render} does not match ${expectedBound.render}."
    case CpElaborationError.ExpectedFunction(_, actualType) =>
      s"Expected a function, but this expression has type ${actualType.render}."
    case CpElaborationError.ExpectedUniversal(_, actualType) =>
      s"Expected a polymorphic value, but this expression has type ${actualType.render}."
    case CpElaborationError.ExpectedRecord(_, actualType) =>
      s"Expected a record, but this expression has type ${actualType.render}."
    case CpElaborationError.MissingRecordField(_, label, actualType) =>
      s"Type ${actualType.render} has no '$label' field."
    case CpElaborationError.ExpectedTrait(_, actualType) =>
      s"Expected a trait, but this expression has type ${actualType.render}."
    case CpElaborationError.TypesAreNotDisjoint(leftType, rightType) =>
      s"Cannot merge ${leftType.render} with ${rightType.render}: the types are not disjoint."
    case CpElaborationError.TypeArgumentViolatesBound(argumentType, disjointBound) =>
      s"Type argument ${argumentType.render} is not disjoint from bound ${disjointBound.render}."
    case CpElaborationError.TraitRequirementNotSatisfied(providedInterface, requiredInterface) =>
      s"Trait provides ${providedInterface.render}, but requires ${requiredInterface.render}."
    case CpElaborationError.TraitImplementationMismatch(actualInterface, declaredInterface) =>
      s"Trait implementation has interface ${actualInterface.render}, " +
        s"but declares ${declaredInterface.render}."
    case CpElaborationError.PrimitiveSignatureNotFound(operator, _) =>
      s"Operator '${operator.symbol}' does not accept the operand types at this expression."
    case CpElaborationError.PatternParameterTypeUnavailable(constructorName, parameterName) =>
      s"Pattern parameter '$parameterName' in '$constructorName' needs an explicit type."
    case CpElaborationError.RecursiveDeclarationRequiresType(name) =>
      s"Recursive declaration '$name' requires an explicit result type."
    case CpElaborationError.DuplicateTermDefinition(name) =>
      s"Term '$name' is defined more than once in this module."
    case CpElaborationError.DuplicateTypeDefinition(name) =>
      s"Type '$name' is defined more than once in this module."
    case CpElaborationError.RecursiveTypeDefinitions(identifiers) =>
      s"Recursive type definitions are not allowed: ${identifiers.map(_.render).mkString(", ")}."
  }

  private def renderNameResolutionError(error: NameResolutionError): String = error match {
    case NameResolutionError.UnknownModule(namespace) =>
      s"Module ${namespace.render} is not available."
    case NameResolutionError.ImportingCurrentModule(namespace) =>
      s"Module ${namespace.render} cannot import itself."
    case NameResolutionError.UnknownImportedMember(identifier) =>
      s"Imported member ${identifier.render} does not exist."
    case NameResolutionError.ModuleNotImported(namespace) =>
      s"Module ${namespace.render} must be imported before its members can be used."
    case NameResolutionError.UnknownTerm(reference) =>
      s"Unknown term '${reference.render}'."
    case NameResolutionError.UnknownType(reference) =>
      s"Unknown type '${reference.render}'."
    case NameResolutionError.AmbiguousTerm(name, candidates) =>
      s"Term '$name' is ambiguous: ${candidates.map(_.render).mkString(", ")}."
    case NameResolutionError.AmbiguousType(name, candidates) =>
      s"Type '$name' is ambiguous: ${candidates.map(_.render).mkString(", ")}."
  }

  private def renderTypeExpansionError(error: TypeExpansionError): String = error match {
    case TypeExpansionError.NameResolution(resolutionError) =>
      renderNameResolutionError(resolutionError)
    case TypeExpansionError.UnknownSignature(identifier) =>
      s"Unknown type signature ${identifier.render}."
    case TypeExpansionError.SignatureArityMismatch(identifier, expected, actual) =>
      s"Signature ${identifier.render} expects $expected type arguments, but received $actual."
    case TypeExpansionError.UnexpectedNamedType(inputType) =>
      s"Named type ${inputType.render} remained after type expansion."
    case TypeExpansionError.UnexpectedSignatureApplication(inputType) =>
      s"Signature application ${inputType.render} remained after type expansion."
  }

  private def renderTypeTranslationError(error: TypeTranslationError): String = error match {
    case TypeTranslationError.UnboundTypeVariable(name, scope) =>
      s"Type variable '$name' is not in scope.${availableNames(scope)}"
    case TypeTranslationError.UnexpandedNamedType(reference) =>
      s"Named type '${reference.render}' was not expanded before Fiobs translation."
    case TypeTranslationError.UnexpandedSignatureApplication(reference, _) =>
      s"Signature '${reference.render}' was not expanded before Fiobs translation."
  }

  private def renderFiobsCompilationError(error: FiobsCompilationError): String = error match {
    case FiobsCompilationError.Elaboration(elaborationError) =>
      renderFiobsElaborationError(elaborationError)
    case FiobsCompilationError.Typing(typeError) => renderTypeError(typeError)
  }

  private def renderFiobsElaborationError(error: FiobsElaborationError): String = error match {
    case FiobsElaborationError.UnboundTermVariable(name, scope) =>
      s"Term '$name' is not in scope.${availableNames(scope)}"
    case FiobsElaborationError.UnboundTypeVariable(name, scope) =>
      s"Type variable '$name' is not in scope.${availableNames(scope)}"
  }

  private def renderTypeError(error: TypeError): String = error match {
    case TypeError.UnboundTermVariable(index) => s"Unbound Fiobs term variable x$index."
    case TypeError.UnboundGlobal(identifier) => s"Unknown global ${identifier.render}."
    case TypeError.IllFormedType(inputType) => s"Type ${inputType.render()} is not well formed."
    case TypeError.CannotInfer(term) =>
      s"Cannot infer the type of '${term.render()}'; add an annotation."
    case TypeError.CheckingShapeMismatch(term, expectedType) =>
      s"Term '${term.render()}' cannot be checked against ${expectedType.render()}."
    case TypeError.TypeMismatch(_, actualType, expectedType) =>
      s"Type mismatch: found ${actualType.render()}, but expected ${expectedType.render()}."
    case TypeError.NotApplicable(_, actualType, applicableForm) =>
      s"Type ${actualType.render()} is not ${applicableDescription(applicableForm)}."
    case TypeError.TypesAreNotDisjoint(leftType, rightType) =>
      s"Cannot merge ${leftType.render()} with ${rightType.render()}: the types are not disjoint."
    case TypeError.TypeArgumentViolatesBound(argumentType, disjointBound) =>
      s"Type argument ${argumentType.render()} is not disjoint from bound ${disjointBound.render()}."
    case TypeError.TypeLambdaBoundMismatch(actualBound, expectedBound) =>
      s"Type-lambda bound ${actualBound.render()} does not match ${expectedBound.render()}."
    case TypeError.NoPrimitiveSignature(operator, _) =>
      s"Operator '${operator.symbol}' does not accept these Fiobs operand types."
  }

  private def renderFiTrieCompilationError(error: FiTrieCompilationError): String = error match {
    case FiTrieCompilationError.SourceElaboration(elaborationError) =>
      renderFiobsElaborationError(elaborationError)
    case FiTrieCompilationError.TargetElaboration(elaborationError) =>
      renderFiTrieElaborationError(elaborationError)
  }

  private def renderFiTrieElaborationError(error: FiTrieElaborationError): String = error match {
    case FiTrieElaborationError.Typing(typeError) => renderTypeError(typeError)
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
    case RouteKey.Projection(label) => s"projection ${label.value}"
  }

  private def applicableDescription(applicableForm: ApplicableForm): String = applicableForm match {
    case ApplicableForm.Arrow => "a function type"
    case ApplicableForm.Universal => "a polymorphic type"
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

  private def availableNames(names: List[String]): String = names.distinct match {
    case Nil => ""
    case available => s" Available names: ${available.mkString(", ")}."
  }
}
