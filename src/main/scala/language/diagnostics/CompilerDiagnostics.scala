package cp.language.diagnostics

import cp.fiobs.{ApplicableForm, CompilationError as FiobsCompilationError}
import cp.fiobs.elaboration.{ElaborationError as FiobsElaborationError}
import cp.fiobs.typing.TypeError
import cp.language.{Cp, CpCompilationError}
import cp.language.analysis.{DiagnosticPhase, DiagnosticSource, SourceDiagnostic}
import cp.language.compilation.{CpSourceFile, IdentifiedSourceModule, ModuleSourceError}
import cp.language.elaboration.*
import cp.language.parser.ParsingError
import cp.source.SourceSpan

/** Compiler-owned error descriptions. Clients need not inspect elaboration or target-language errors. */
object CompilerDiagnostics {
  def describe(error: CpCompilationError, sources: List[CpSourceFile]): SourceDiagnostic = {
    val source = sourceFile(error, sources)
    val span = error match {
      case CpCompilationError.Parsing(_, ParsingError.Syntax(_, line, column)) =>
        source.flatMap(file => parsingPosition(file.contents, line, column))
      case _ => sourceSpan(error)
    }
    val phase = error match {
      case CpCompilationError.Parsing(_, _) => DiagnosticPhase.Parsing
      case _ => DiagnosticPhase.Checking
    }
    SourceDiagnostic(phase, render(error), source.map(file => DiagnosticSource(file.path, span)))
  }

  // RegexParsers reports one-based coordinates, with LF as the line separator. Preserve that
  // convention here; clients convert the resulting UTF-16 offsets into their own coordinates.
  private def parsingPosition(source: String, line: Int, column: Int): Option[SourceSpan] = {
    var start = 0
    var currentLine = 1
    while (currentLine < line && start >= 0) {
      val newline = source.indexOf('\n', start)
      start = if (newline < 0) -1 else newline + 1
      currentLine += 1
    }
    val offset = start + column - 1
    Option.when(start >= 0 && offset >= 0 && offset <= source.length)(SourceSpan(offset, offset))
  }

  /** Preserve the compiler's source identity; diagnostics never infer a file from the message text. */
  private def sourceFile(error: CpCompilationError, sources: List[CpSourceFile]): Option[CpSourceFile] = {
    val path = error match {
      case CpCompilationError.Parsing(sourcePath, _) => sourcePath
      case CpCompilationError.Source(ModuleSourceError.InvalidSourceFileExtension(sourcePath)) => Some(sourcePath)
      case CpCompilationError.Source(ModuleSourceError.InvalidModuleFileName(sourcePath)) => Some(sourcePath)
      case _ => None
    }
    val namespace = error match {
      case CpCompilationError.Elaboration(owner, _) => Some(owner)
      case CpCompilationError.Fiobs(identifier, _) => Some(identifier.scope)
      case CpCompilationError.UnknownImportedModule(owner, _) => Some(owner)
      case _ => None
    }
    path.flatMap(sourcePath => sources.find(_.path == sourcePath)).orElse {
      namespace.flatMap { owner =>
        sources.find { source =>
          Cp.parse(source.contents).toOption
            .flatMap(module => IdentifiedSourceModule.create(source, module).toOption)
            .exists(_.namespace == owner)
        }
      }
    }
  }

  private def sourceSpan(error: CpCompilationError): Option[SourceSpan] = error match {
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
      s"While checking ${identifier.render}: ${render(compilationError)}"
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
  }

  private def renderCpElaborationError(error: CpElaborationError): String = error match {
    case CpElaborationError.Located(_, underlying) => renderCpElaborationError(underlying)
    case CpElaborationError.NameResolution(resolutionError) =>
      renderNameResolutionError(resolutionError)
    case CpElaborationError.TypeExpansion(expansionError) =>
      renderTypeExpansionError(expansionError)
    case CpElaborationError.CheckingShapeMismatch(_, expectedType) =>
      s"This expression cannot be checked against ${expectedType.render}."
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
      s"Cyclic type aliases require an explicit μ binder: ${identifiers.map(_.render).mkString(", ")}."
    case CpElaborationError.ExpectedRecursiveType(actualType) =>
      s"Expected a recursive type μ X. A, but received ${actualType.render}."
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
    case NameResolutionError.KindMismatch(reference, expected, actual, candidates) =>
      def kindName(kind: NameKind): String = kind match {
        case NameKind.Term => "term"
        case NameKind.Type => "type"
      }
      s"Expected a ${kindName(expected)}, but '${reference.render}' names a ${kindName(actual)}: " +
        s"${candidates.map(_.render).mkString(", ")}."
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
    case TypeExpansionError.DuplicateSortParameter(name) => s"Sort parameter '$name' is declared more than once."
  }

  def render(error: FiobsCompilationError): String = error match {
    case FiobsCompilationError.Elaboration(elaborationError) =>
      renderFiobsElaborationError(elaborationError)
    case FiobsCompilationError.Typing(typeError) => render(typeError)
  }

  private def renderFiobsElaborationError(error: FiobsElaborationError): String = error match {
    case FiobsElaborationError.UnboundTermVariable(name, scope) =>
      s"Term '$name' is not in scope.${availableNames(scope)}"
    case FiobsElaborationError.UnboundTypeVariable(name, scope) =>
      s"Type variable '$name' is not in scope.${availableNames(scope)}"
  }

  def render(error: TypeError): String = error match {
    case TypeError.UnboundTermVariable(index) => s"Unbound Fiobs term variable x$index."
    case TypeError.UnboundGlobal(identifier) => s"Unknown global ${identifier.render}."
    case TypeError.IllFormedType(inputType) => s"Type ${inputType.render()} is not well formed."
    case TypeError.ExpectedRecursiveType(inputType) =>
      s"Expected a recursive type μ X. A, but received ${inputType.render()}."
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
    case TypeError.NoPrimitiveSignature(operator, _, candidates) =>
      val causes = candidates.map(_.cause).distinct.map(render).mkString(" ")
      s"Operator '${operator.symbol}' does not accept these Fiobs operands. $causes"
  }

  private def applicableDescription(applicableForm: ApplicableForm): String = applicableForm match {
    case ApplicableForm.Arrow => "a function type"
    case ApplicableForm.Universal => "a polymorphic type"
  }

  private def availableNames(names: List[String]): String = names.distinct match {
    case Nil => ""
    case available => s" Available names: ${available.mkString(", ")}."
  }
}
