package cp.language.evaluation

import cp.fiobs.eval.LazyEvaluation
import cp.fiobs.runtime.{EvaluationError, FullyEvaluatedValue, GlobalEnvironment, RuntimeTerm, Value}
import cp.language.CompiledCpProgram
import cp.naming.{Identifier, Namespace}
import cp.util.Result

enum CpEvaluationError {
  case ModuleNotCompiled(namespace: Namespace)
  case EntryPointNotFound(identifier: Identifier)
  case Fiobs(error: EvaluationError)
}

/** Selects a module's ordinary `main` global and executes it with lazy Fiobs reduction. */
object CpEvaluator {
  private val entryPointName = "main"

  def evaluate(
    program: CompiledCpProgram,
    namespace: Namespace
  ): Result[Value, CpEvaluationError] = evaluateMain(program, namespace)(LazyEvaluation.evaluate)

  /** Evaluates `main` and recursively forces its lazy record fields. */
  def evaluateFully(
    program: CompiledCpProgram,
    namespace: Namespace
  ): Result[FullyEvaluatedValue, CpEvaluationError] = {
    evaluateMain(program, namespace)(LazyEvaluation.evaluateFully)
  }

  private def evaluateMain[T](
    program: CompiledCpProgram,
    namespace: Namespace
  )(
    evaluator: (RuntimeTerm, GlobalEnvironment) => Result[T, EvaluationError]
  ): Result[T, CpEvaluationError] = {
    program.module(namespace) match {
      case None => Result.Err(CpEvaluationError.ModuleNotCompiled(namespace))
      case Some(module) =>
        val entryPoint = namespace.identifier(entryPointName)
        if (!module.definitions.contains(entryPoint)) {
          Result.Err(CpEvaluationError.EntryPointNotFound(entryPoint))
        } else {
          val globalEnvironment = program.globalEnvironmentFor(namespace).getOrElse {
            throw new IllegalStateException(s"compiled module has no global environment: $namespace")
          }
          evaluator(RuntimeTerm.Global(entryPoint), globalEnvironment)
            .mapError(CpEvaluationError.Fiobs(_))
        }
    }
  }
}
