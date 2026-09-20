package cp.fiobs

import cp.fiobs.elaboration.{ElaborationError, Elaborator}
import cp.fiobs.eval.LazyEvaluation
import cp.fiobs.runtime.{EvaluationError, RuntimeTerm, Value}
import cp.fiobs.typing.{TypeChecker, TypeError, TypingContext}
import cp.naming.Identifier
import cp.util.Result

/**
 * A checked source term and its decoration under the recorded global typing context.
 * Only the Fiobs checking boundary constructs this artifact; evaluation and target
 * compilation reuse the same checked result.
 */
final class CheckedProgram private[fiobs] (
  val sourceTerm: Term,
  val runtimeTerm: RuntimeTerm,
  val programType: Type,
  val globalTypes: Map[Identifier, Type]
)

enum CompilationError {
  case Elaboration(error: ElaborationError)
  case Typing(error: TypeError)
}

enum ProgramError {
  case Compilation(error: CompilationError)
  case Evaluation(error: EvaluationError)
}

/** Public boundary from named expressions or resolved terms to checked Fiobs programs. */
object Fiobs {
  def infer(term: Term, globalTypes: Map[Identifier, Type] = Map.empty): Result[CheckedProgram, TypeError] = {
    TypeChecker(TypingContext.withGlobals(globalTypes)).infer(term).map { typed =>
      new CheckedProgram(term, typed.runtimeTerm, typed.inferredType, globalTypes)
    }
  }

  def check(
    term: Term,
    expectedType: Type,
    globalTypes: Map[Identifier, Type] = Map.empty
  ): Result[CheckedProgram, TypeError] = {
    TypeChecker(TypingContext.withGlobals(globalTypes)).check(term, expectedType).map { runtimeTerm =>
      new CheckedProgram(term, runtimeTerm, expectedType, globalTypes)
    }
  }

  def compile(
    expression: Expr,
    globalTypes: Map[Identifier, Type] = Map.empty
  ): Result[CheckedProgram, CompilationError] = {
    Elaborator.elaborate(expression)
      .mapError(CompilationError.Elaboration(_))
      .flatMap(infer(_, globalTypes).mapError(CompilationError.Typing(_)))
  }

  def compileChecking(
    expression: Expr,
    expectedType: SurfaceType,
    globalTypes: Map[Identifier, Type] = Map.empty
  ): Result[CheckedProgram, CompilationError] = {
    for {
      term <- Elaborator.elaborate(expression).mapError(CompilationError.Elaboration(_))
      inputType <- Elaborator.elaborateType(expectedType).mapError(CompilationError.Elaboration(_))
      checked <- check(term, inputType, globalTypes).mapError(CompilationError.Typing(_))
    } yield checked
  }

  def evaluate(expression: Expr): Result[Value, ProgramError] = {
    compile(expression)
      .mapError(ProgramError.Compilation(_))
      .flatMap { program =>
        LazyEvaluation.evaluate(program.runtimeTerm).mapError(ProgramError.Evaluation(_))
      }
  }
}
