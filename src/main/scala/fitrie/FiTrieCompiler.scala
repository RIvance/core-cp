package cp.fitrie

import cp.fiobs.{Expr, SurfaceType, Term, Type}
import cp.fiobs.elaboration.{ElaborationError as SourceElaborationError, Elaborator as SourceElaborator}
import cp.fitrie.elaboration.{Elaborator as TargetElaborator, FiTrieElaborationError}
import cp.naming.Identifier
import cp.util.Result

final case class CompiledFiTrie(
  sourceTerm: Term,
  targetTrie: FiTrie,
  programType: Type
)

enum FiTrieCompilationError {
  case SourceElaboration(error: SourceElaborationError)
  case TargetElaboration(error: FiTrieElaborationError)
}

/** Public source-to-FiTrie compilation boundary. */
object FiTrieCompiler {
  def compile(
    expression: Expr,
    globalTypes: Map[Identifier, Type] = Map.empty
  ): Result[CompiledFiTrie, FiTrieCompilationError] = {
    SourceElaborator.elaborate(expression)
      .mapError(FiTrieCompilationError.SourceElaboration(_))
      .flatMap(compile(_, globalTypes))
  }

  def compile(
    term: Term,
    globalTypes: Map[Identifier, Type]
  ): Result[CompiledFiTrie, FiTrieCompilationError] = {
    TargetElaborator.infer(term, globalTypes)
      .mapError(FiTrieCompilationError.TargetElaboration(_))
      .map(typed => CompiledFiTrie(term, typed.trie, typed.inferredType))
  }

  def compile(term: Term): Result[CompiledFiTrie, FiTrieCompilationError] = {
    compile(term, Map.empty)
  }

  def compileChecking(
    expression: Expr,
    expectedType: SurfaceType,
    globalTypes: Map[Identifier, Type] = Map.empty
  ): Result[CompiledFiTrie, FiTrieCompilationError] = {
    SourceElaborator.elaborate(expression)
      .mapError(FiTrieCompilationError.SourceElaboration(_))
      .flatMap { term =>
        SourceElaborator.elaborateType(expectedType)
          .mapError(FiTrieCompilationError.SourceElaboration(_))
          .flatMap(compileChecking(term, _, globalTypes))
      }
  }

  def compileChecking(
    term: Term,
    expectedType: Type,
    globalTypes: Map[Identifier, Type]
  ): Result[CompiledFiTrie, FiTrieCompilationError] = {
    TargetElaborator.check(term, expectedType, globalTypes)
      .mapError(FiTrieCompilationError.TargetElaboration(_))
      .map(typed => CompiledFiTrie(term, typed.trie, typed.inferredType))
  }

  def compileChecking(
    term: Term,
    expectedType: Type
  ): Result[CompiledFiTrie, FiTrieCompilationError] = {
    compileChecking(term, expectedType, Map.empty)
  }
}
