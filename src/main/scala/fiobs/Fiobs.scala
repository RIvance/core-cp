package cp.fiobs

import cp.fiobs.elaboration.{ElaborationError, Elaborator}
import cp.fiobs.eval.LazyEvaluation
import cp.fiobs.runtime.{EvaluationError, RuntimeTerm, Value}
import cp.fiobs.typing.{TypeChecker, TypeError, TypedTerm}
import cp.naming.Identifier
import cp.util.Result

final case class CompiledProgram(
  sourceTerm: Term,
  runtimeTerm: RuntimeTerm,
  programType: Type
)

enum CompilationError {
  case Elaboration(error: ElaborationError)
  case Typing(error: TypeError)
}

enum ProgramError {
  case Compilation(error: CompilationError)
  case Evaluation(error: EvaluationError)
}

/** Public boundary from named expressions to checked and decorated Fiobs programs. */
object Fiobs {
  def compile(
    expression: Expr,
    globalTypes: Map[Identifier, Type] = Map.empty
  ): Result[CompiledProgram, CompilationError] = {
    Elaborator.elaborate(expression)
      .mapError(CompilationError.Elaboration(_))
      .flatMap { sourceTerm =>
        TypeChecker(cp.fiobs.typing.TypingContext.withGlobals(globalTypes)).infer(sourceTerm)
          .mapError(CompilationError.Typing(_))
          .map(typedTerm => compiled(sourceTerm, typedTerm))
      }
  }

  def compileChecking(
    expression: Expr,
    expectedType: SurfaceType,
    globalTypes: Map[Identifier, Type] = Map.empty
  ): Result[CompiledProgram, CompilationError] = {
    Elaborator.elaborate(expression)
      .mapError(CompilationError.Elaboration(_))
      .flatMap { sourceTerm =>
        Elaborator.elaborateType(expectedType)
          .mapError(CompilationError.Elaboration(_))
          .flatMap { coreExpectedType =>
            TypeChecker(cp.fiobs.typing.TypingContext.withGlobals(globalTypes)).check(sourceTerm, coreExpectedType)
              .mapError(CompilationError.Typing(_))
              .map(runtimeTerm => CompiledProgram(sourceTerm, runtimeTerm, coreExpectedType))
          }
      }
  }

  def evaluate(expression: Expr): Result[Value, ProgramError] = {
    compile(expression)
      .mapError(ProgramError.Compilation(_))
      .flatMap { program =>
        LazyEvaluation.evaluate(program.runtimeTerm)
          .mapError(ProgramError.Evaluation(_))
      }
  }

  private def compiled(sourceTerm: Term, typedTerm: TypedTerm): CompiledProgram =
    CompiledProgram(sourceTerm, typedTerm.runtimeTerm, typedTerm.inferredType)
}
