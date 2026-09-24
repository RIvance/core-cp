package cp.language

import cp.fiobs.{CompilationError as FiobsCompilationError, CheckedProgram, Fiobs}
import cp.fiobs.runtime.{GlobalEnvironment, Value}
import cp.language.compilation.{CpSourceFile, IdentifiedSourceModule, ModuleSourceError, SourcePath}
import cp.language.analysis.{SourceAnalysis, SourceDiagnostic}
import cp.language.diagnostics.CompilerDiagnostics
import cp.language.core.Module
import cp.language.elaboration.*
import cp.language.evaluation.{CpEvaluationError, CpEvaluator}
import cp.language.parser.{CpParser, ParsingError, SourceSyntax}
import cp.naming.{Identifier, Namespace}
import cp.util.{Graph, Result}

final case class CompiledCpModule(
  sourceFile: CpSourceFile,
  sourceModule: Module,
  elaboratedModule: ElaboratedModule,
  definitions: Map[Identifier, CheckedProgram]
) {
  def namespace: Namespace = elaboratedModule.namespace
}

final case class CompiledCpProgram(modules: Map[Namespace, CompiledCpModule]) {
  def module(namespace: Namespace): Option[CompiledCpModule] = modules.get(namespace)

  def globalEnvironmentFor(namespace: Namespace): Option[GlobalEnvironment] = {
    definitionsFor(namespace).map { definitions =>
      GlobalEnvironment(definitions.map { case (identifier, program) =>
        identifier -> program.runtimeTerm
      })
    }
  }

  /** Definitions visible while executing a module, including its transitive imports. */
  def definitionsFor(namespace: Namespace): Option[Map[Identifier, CheckedProgram]] = {
    modules.get(namespace).map { _ =>
      dependencyClosure(namespace).flatMap(requiredNamespace => modules(requiredNamespace).definitions).toMap
    }
  }

  private def dependencyClosure(root: Namespace): Set[Namespace] = {
    def visit(pending: List[Namespace], visited: Set[Namespace]): Set[Namespace] = pending match {
      case Nil => visited
      case namespace :: remaining if visited.contains(namespace) => visit(remaining, visited)
      case namespace :: remaining =>
        val dependencies = modules(namespace).elaboratedModule.header.dependencies.toList
        visit(dependencies ::: remaining, visited + namespace)
    }

    visit(List(root), Set.empty)
  }
}

enum CpCompilationError {
  case NoSourceModules
  case Source(error: ModuleSourceError)
  case Parsing(path: Option[SourcePath], error: ParsingError)
  case DuplicateModule(namespace: Namespace, paths: List[SourcePath])
  case UnknownImportedModule(importer: Namespace, imported: Namespace)
  case CircularModuleDependencies(namespaces: List[Namespace])
  case Elaboration(namespace: Namespace, error: CpElaborationError)
  case Fiobs(identifier: Identifier, error: FiobsCompilationError)
}

enum CpProgramError {
  case Compilation(error: CpCompilationError)
  case Evaluation(error: CpEvaluationError)
}

object Cp {
  def parse(source: String): Result[Module, ParsingError] = {
    CpParser.parseModule(source)
  }

  /** Checks the supplied workspace without evaluation. Compilation currently reports its first failure. */
  def check(sourceFiles: List[CpSourceFile]): List[SourceDiagnostic] = compileModules(sourceFiles) match {
    case Result.Ok(_) => Nil
    case Result.Err(error) => List(CompilerDiagnostics.describe(error, sourceFiles))
  }

  /**
   * Inspects source using CP's normal parser, name resolution and elaboration. Failed definitions
   * retain facts established before their error; analysis never compiles FiTrie or evaluates code.
   */
  def analyze(sourceFiles: List[CpSourceFile]): SourceAnalysis = {
    val parsed = sourceFiles.map(parseSourceModule)
    val diagnostics = parsed.collect { case Result.Err(error) => error }
    val sources = parsed.collect { case Result.Ok(source) => source }
    uniqueModules(sources.map(_._1)).flatMap { modules =>
      moduleCompilationOrder(modules).map(order => modules -> order)
    } match {
      case Result.Err(error) =>
        SourceAnalysis((diagnostics :+ error).map(CompilerDiagnostics.describe(_, sourceFiles)), Map.empty)
      case Result.Ok((modules, order)) =>
        val syntax = sources.map { case (source, syntax) => source.namespace -> syntax }.toMap
        var headers = Map.empty[Namespace, ElaboratedModuleHeader]
        var issues = diagnostics
        val completions = order.map { namespace =>
          val source = modules(namespace)
          val imported = source.sourceModule.imports.map(_.targetNamespace).toSet
          val report = CpElaborator.analyze(
            source.sourceModule, namespace, headers.filter(entry => imported.contains(entry._1)), syntax(namespace)
          )
          report.header.foreach(header => headers = headers.updated(namespace, header))
          report.error.foreach(error => issues = issues :+ CpCompilationError.Elaboration(namespace, error))
          source.sourceFile.path -> report.completions
        }.toMap
        SourceAnalysis(issues.map(CompilerDiagnostics.describe(_, sourceFiles)), completions)
    }
  }

  def elaborate(
    module: Module,
    namespace: Namespace,
    importedHeaders: Map[Namespace, ElaboratedModuleHeader] = Map.empty
  ): Result[ElaboratedModule, CpElaborationError] = {
    CpElaborator.elaborate(module, namespace, importedHeaders)
  }

  /** Raw source can be compiled only when its module declaration supplies an identity. */
  def compile(source: String): Result[CompiledCpModule, CpCompilationError] = {
    parse(source)
      .mapError(error => CpCompilationError.Parsing(None, error))
      .flatMap { module =>
        module.declaredNamespace match {
          case None => Result.Err(CpCompilationError.Source(ModuleSourceError.ModuleIdentityUnavailable))
          case Some(namespace) =>
            val fileName = namespace.segments.lastOption.map(_ + ".cp").getOrElse("Module.cp")
            compile(CpSourceFile(SourcePath(fileName), source))
        }
      }
  }

  def compile(sourceFile: CpSourceFile): Result[CompiledCpModule, CpCompilationError] = {
    compileModules(List(sourceFile)).map(_.modules.values.head)
  }

  def compileModules(
    sourceFiles: List[CpSourceFile]
  ): Result[CompiledCpProgram, CpCompilationError] = {
    identifyModules(sourceFiles).flatMap { identifiedModules =>
      uniqueModules(identifiedModules).flatMap { modulesByNamespace =>
        moduleCompilationOrder(modulesByNamespace).flatMap { compilationOrder =>
          compileInOrder(compilationOrder, modulesByNamespace)
        }
      }
    }
  }

  def evaluate(source: String): Result[Value, CpProgramError] = {
    compile(source)
      .mapError(CpProgramError.Compilation(_))
      .flatMap { compiledModule =>
        val program = CompiledCpProgram(Map(compiledModule.namespace -> compiledModule))
        CpEvaluator.evaluate(program, compiledModule.namespace)
          .mapError(CpProgramError.Evaluation(_))
      }
  }

  def evaluate(sourceFile: CpSourceFile): Result[Value, CpProgramError] = {
    compileModules(List(sourceFile))
      .mapError(CpProgramError.Compilation(_))
      .flatMap { program =>
        val namespace = program.modules.keys.head
        CpEvaluator.evaluate(program, namespace).mapError(CpProgramError.Evaluation(_))
      }
  }

  def evaluate(
    sourceFiles: List[CpSourceFile],
    namespace: Namespace
  ): Result[Value, CpProgramError] = {
    compileModules(sourceFiles)
      .mapError(CpProgramError.Compilation(_))
      .flatMap { program =>
        CpEvaluator.evaluate(program, namespace).mapError(CpProgramError.Evaluation(_))
      }
  }

  private def identifyModules(
    sourceFiles: List[CpSourceFile]
  ): Result[List[IdentifiedSourceModule], CpCompilationError] = {
    if (sourceFiles.isEmpty) {
      Result.Err(CpCompilationError.NoSourceModules)
    } else {
      Result.traverse(sourceFiles)(parseSourceModule).map(_.map(_._1))
    }
  }

  private def parseSourceModule(
    sourceFile: CpSourceFile
  ): Result[(IdentifiedSourceModule, SourceSyntax), CpCompilationError] = {
    CpParser.parseSource(sourceFile.contents)
      .mapError(error => CpCompilationError.Parsing(Some(sourceFile.path), error))
      .flatMap { parsed =>
        IdentifiedSourceModule.create(sourceFile, parsed.module)
          .mapError(CpCompilationError.Source(_)).map(_ -> parsed.syntax)
      }
  }

  private def uniqueModules(
    modules: List[IdentifiedSourceModule]
  ): Result[Map[Namespace, IdentifiedSourceModule], CpCompilationError] = {
    modules.groupBy(_.namespace).toList.sortBy(_._1.render).collectFirst {
      case (namespace, occurrences) if occurrences.size > 1 =>
        CpCompilationError.DuplicateModule(
          namespace,
          occurrences.map(_.sourceFile.path).sortBy(_.toString)
        )
    } match {
      case Some(error) => Result.Err(error)
      case None => Result.Ok(modules.map(module => module.namespace -> module).toMap)
    }
  }

  private def moduleCompilationOrder(
    modules: Map[Namespace, IdentifiedSourceModule]
  ): Result[List[Namespace], CpCompilationError] = {
    val initialGraph = Graph.directed[Namespace].addVertices(modules.keys)
    modules.toList.sortBy(_._1.render).foldLeft(
      Result.Ok(initialGraph): Result[Graph[Namespace], CpCompilationError]
    ) { case (accumulatedGraph, (namespace, module)) =>
      accumulatedGraph.flatMap { graph =>
        module.sourceModule.imports.map(_.targetNamespace).distinct.sortBy(_.render).foldLeft(
          Result.Ok(graph): Result[Graph[Namespace], CpCompilationError]
        ) { (currentGraph, dependency) =>
          currentGraph.flatMap { completeGraph =>
            if (modules.contains(dependency)) {
              Result.Ok(completeGraph.addEdge(dependency, namespace))
            } else {
              Result.Err(CpCompilationError.UnknownImportedModule(namespace, dependency))
            }
          }
        }
      }
    }.flatMap { graph =>
      graph.topologicalSort match {
        case Some(order) => Result.Ok(order.toList)
        case None =>
          val cyclicNamespaces = graph.stronglyConnectedComponents
            .filter(component => component.size > 1 || component.exists(graph.isSelfLoop))
            .flatten.toList.sortBy(_.render)
          Result.Err(CpCompilationError.CircularModuleDependencies(cyclicNamespaces))
      }
    }
  }

  private def compileInOrder(
    compilationOrder: List[Namespace],
    sourceModules: Map[Namespace, IdentifiedSourceModule]
  ): Result[CompiledCpProgram, CpCompilationError] = {
    val initialResult: Result[Map[Namespace, CompiledCpModule], CpCompilationError] = Result.Ok(Map.empty)
    compilationOrder.foldLeft(initialResult) { (accumulatedModules, namespace) =>
      accumulatedModules.flatMap { compiledModules =>
        val sourceModule = sourceModules(namespace)
        val importedHeaders = sourceModule.sourceModule.imports.map(_.targetNamespace).distinct.map {
          importedNamespace => importedNamespace -> compiledModules(importedNamespace).elaboratedModule.header
        }.toMap
        CpElaborator.elaborate(sourceModule.sourceModule, namespace, importedHeaders)
          .mapError(error => CpCompilationError.Elaboration(namespace, error))
          .flatMap { elaboratedModule =>
            compileDefinitions(elaboratedModule, importedHeaders).map { definitions =>
              compiledModules.updated(
                namespace,
                CompiledCpModule(
                  sourceModule.sourceFile,
                  sourceModule.sourceModule,
                  elaboratedModule,
                  definitions
                )
              )
            }
          }
      }
    }.map(CompiledCpProgram.apply)
  }

  private def compileDefinitions(
    module: ElaboratedModule,
    importedHeaders: Map[Namespace, ElaboratedModuleHeader]
  ): Result[Map[Identifier, CheckedProgram], CpCompilationError] = {
    val languageGlobalTypes = importedHeaders.valuesIterator.flatMap(_.termSignatures).toMap ++
      module.globalTermTypes
    val globalTypes = languageGlobalTypes.view.mapValues(TypeTranslation.toFiobs).toMap
    Result.traverse(module.termDefinitions.toList.sortBy(_._1.name)) { case (identifier, definition) =>
      Fiobs.infer(definition.initializer, globalTypes)
        .mapError(error => CpCompilationError.Fiobs(identifier, FiobsCompilationError.Typing(error)))
        .map { program =>
          require(
            program.programType == TypeTranslation.toFiobs(definition.definitionType),
            "an elaborated definition must synthesize its recorded CP interface"
          )
          identifier -> program
        }
    }.map(_.toMap)
  }
}
