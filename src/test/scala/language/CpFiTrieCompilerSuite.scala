package cp.language

import cp.fitrie.evaluation.Evaluation
import cp.language.compilation.*
import cp.naming.Namespace
import cp.primitive.{PrimitiveType, PrimitiveValue}
import cp.util.Result

import java.nio.file.Paths

class CpFiTrieCompilerSuite extends munit.FunSuite {
  test("compile a CP module's main definition and its dependency closure to FiTrie") {
    val sources = List(
      CpSourceFile(Paths.get("Library.cp"), "def answer: Int = 40;"),
      CpSourceFile(Paths.get("Application.cp"),
        """import Library::answer
          |def main: Int = answer + 2;
          |""".stripMargin)
    )
    val cpProgram = expectSuccess(Cp.compileModules(sources))
    val targetProgram = expectSuccess(CpFiTrieCompiler.compile(
      cpProgram,
      Namespace("Application")
    ))

    assertEquals(targetProgram.entryPoint.render, "Application::main")
    assertEquals(
      targetProgram.globalEnvironment.definitions.keySet.map(_.render),
      Set("Application::main", "Library::answer")
    )
    assertEquals(
      Evaluation.observeTermination(
        targetProgram.entry,
        PrimitiveType.Integer,
        targetProgram.globalEnvironment
      ),
      Result.Ok(PrimitiveValue.Integer(42))
    )
  }

  test("report a structured error when the target module has no main definition") {
    val source = CpSourceFile(Paths.get("Library.cp"), "def answer: Int = 42;")
    val cpProgram = expectSuccess(Cp.compileModules(List(source)))

    assertEquals(
      CpFiTrieCompiler.compile(cpProgram, Namespace("Library")),
      Result.Err(CpFiTrieCompilationError.EntryPointNotFound(
        Namespace("Library").identifier("main")
      ))
    )
  }

  private def expectSuccess[T, E](result: Result[T, E]): T = result match {
    case Result.Ok(value) => value
    case Result.Err(error) => fail(s"unexpected error: $error")
  }
}
