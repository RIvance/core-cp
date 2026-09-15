package cp.language.compilation

import cp.fitrie.evaluation.GlobalEnvironment
import cp.fitrie.{FiTrie, FiTrieCompilationError, FiTrieCompiler, ResponseComputation}
import cp.language.CompiledCpProgram
import cp.naming.{Identifier, Namespace}
import cp.util.Result

final case class CompiledCpFiTrieProgram(
  entryPoint: Identifier,
  entry: FiTrie,
  globalEnvironment: GlobalEnvironment
)

enum CpFiTrieCompilationError {
  case ModuleNotCompiled(namespace: Namespace)
  case Definition(identifier: Identifier, error: FiTrieCompilationError)
  case EntryPointNotFound(identifier: Identifier)
}

/** Compiles the definitions visible to one CP module into one closed FiTrie program. */
object CpFiTrieCompiler {
  private val entryPointName = "main"

  def compile(
    program: CompiledCpProgram,
    targetNamespace: Namespace
  ): Result[CompiledCpFiTrieProgram, CpFiTrieCompilationError] = {
    program.definitionsFor(targetNamespace) match {
      case None => Result.Err(CpFiTrieCompilationError.ModuleNotCompiled(targetNamespace))
      case Some(requiredDefinitions) =>
        val globalTypes = requiredDefinitions.map { case (identifier, definition) =>
          identifier -> definition.programType
        }
        Result.traverse(requiredDefinitions.toList.sortBy(_._1.render)) { case (identifier, definition) =>
          FiTrieCompiler.compileChecking(
            definition.sourceTerm,
            definition.programType,
            globalTypes
          ).mapError(error => CpFiTrieCompilationError.Definition(identifier, error))
            .map(compiled => identifier -> compiled.targetTrie)
        }.flatMap { compiledDefinitions =>
          val targetDefinitions = compiledDefinitions.toMap
          val entryPoint = targetNamespace.identifier(entryPointName)
          if (targetDefinitions.contains(entryPoint)) {
            Result.Ok(CompiledCpFiTrieProgram(
              entryPoint,
              FiTrie.response(ResponseComputation.Global(entryPoint)),
              GlobalEnvironment(targetDefinitions)
            ))
          } else {
            Result.Err(CpFiTrieCompilationError.EntryPointNotFound(entryPoint))
          }
        }
    }
  }
}
