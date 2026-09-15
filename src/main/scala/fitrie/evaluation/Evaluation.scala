package cp.fitrie.evaluation

import cp.fitrie.{FiTrie, FiTrieMergeError, RootKeyExpression, TermVariableIndex}
import cp.naming.Identifier
import cp.primitive.{PrimitiveOperationError, PrimitiveType, PrimitiveValue}
import cp.util.Result

import scala.annotation.tailrec

final case class GlobalEnvironment(definitions: Map[Identifier, FiTrie]) {
  def lookup(identifier: Identifier): Option[FiTrie] = definitions.get(identifier)
}

object GlobalEnvironment {
  val empty: GlobalEnvironment = GlobalEnvironment(Map.empty)
}

enum EvaluationError {
  case IllScopedTrie(trie: FiTrie)
  case IllScopedGlobal(identifier: Identifier, trie: FiTrie)
  case UnknownGlobal(identifier: Identifier)
  case UnboundLocalVariable(index: TermVariableIndex)
  case UnresolvedRootKeyExpression(expression: RootKeyExpression)
  case Merge(error: FiTrieMergeError)
  case PrimitiveFailure(error: PrimitiveOperationError)
  case MissingTermination(primitiveType: PrimitiveType)
}

/** Lazy FiTrie evaluation driven only by the requested observation. */
object Evaluation {
  /** Starts an immutable lazy small-step session without reducing the entry trie. */
  def start(
    trie: FiTrie,
    globalEnvironment: GlobalEnvironment = GlobalEnvironment.empty
  ): Result[EvaluationSession, EvaluationError] = {
    EvaluationSession.start(trie, globalEnvironment)
  }

  def observeTermination(
    trie: FiTrie,
    primitiveType: PrimitiveType,
    globalEnvironment: GlobalEnvironment = GlobalEnvironment.empty
  ): Result[PrimitiveValue, EvaluationError] = {
    RuntimeCompiler.compile(trie, globalEnvironment).flatMap { compiled =>
      @tailrec
      def continue(current: RuntimeTrie): Result[PrimitiveValue, EvaluationError] = {
        current.terminationPayloads.get(primitiveType) match {
          case Some(payload) => Result.Ok(payload)
          case None => RuntimeEvaluation.step(current, compiled.globalEnvironment) match {
            case Result.Ok(Some(next)) => continue(next)
            case Result.Ok(None) => Result.Err(EvaluationError.MissingTermination(primitiveType))
            case Result.Err(error) => Result.Err(error)
          }
        }
      }

      continue(compiled.entry)
    }
  }
}
