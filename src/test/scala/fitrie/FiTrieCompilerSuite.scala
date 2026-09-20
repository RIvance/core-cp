package cp.fitrie

import cp.fiobs.{Fiobs, Term, Type}
import cp.fiobs.eval.LazyEvaluation
import cp.fiobs.runtime.{GlobalEnvironment as FiobsGlobals, Value}
import cp.fitrie.evaluation.{Evaluation, GlobalEnvironment}
import cp.naming.Namespace
import cp.primitive.{BinaryOperator, PrimitiveType, PrimitiveValue}
import cp.util.Result

class FiTrieCompilerSuite extends munit.FunSuite {
  test("checked programs retain checking-mode terms and global typing context for both runtimes") {
    val identifier = Namespace("Library").identifier("addTwo")
    val functionType = Type.Arrow(Type.Integer, Type.Integer)
    val function = expectSuccess(Fiobs.check(
      Term.Lambda(Term.Binary(BinaryOperator.Add, Term.Variable(0), Term.Literal(PrimitiveValue.Integer(2)))),
      functionType
    ))
    val program = expectSuccess(Fiobs.infer(
      Term.Application(Term.Global(identifier), Term.Literal(PrimitiveValue.Integer(40))),
      Map(identifier -> functionType)
    ))
    val targetFunction = expectSuccess(FiTrieCompiler.compile(function))
    val targetProgram = expectSuccess(FiTrieCompiler.compile(program))

    assertEquals(targetFunction.programType, functionType)
    assertEquals(targetProgram.programType, Type.Integer)
    assertEquals(
      Evaluation.observeTermination(
        targetProgram.targetTrie,
        PrimitiveType.Integer,
        GlobalEnvironment(Map(identifier -> targetFunction.targetTrie))
      ),
      Result.Ok(PrimitiveValue.Integer(42))
    )
    assertEquals(
      LazyEvaluation.evaluate(program.runtimeTerm, FiobsGlobals(Map(identifier -> function.runtimeTerm))),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  private def expectSuccess[T, E](result: Result[T, E]): T = result match {
    case Result.Ok(value) => value
    case Result.Err(error) => fail(s"unexpected error: $error")
  }
}
