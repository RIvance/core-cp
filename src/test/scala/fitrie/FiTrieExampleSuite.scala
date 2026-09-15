package cp.fitrie

import cp.fiobs.runtime.Value
import cp.fitrie.evaluation.{Evaluation, GlobalEnvironment}
import cp.language.compilation.{CpFiTrieCompiler, CpSourceFile, IdentifiedSourceModule}
import cp.language.evaluation.CpEvaluator
import cp.language.{CompiledCpProgram, Cp}
import cp.naming.{Identifier, Namespace}
import cp.primitive.PrimitiveValue
import cp.util.Result

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.matching.Regex

class FiTrieExampleSuite extends munit.FunSuite {
  private val exampleDirectory = Paths.get("examples").toAbsolutePath.normalize
  private val expectedExpressionDirective: Regex = """(?m)^[ \t]*//[ \t]*expected:[ \t]*(.+?)[ \t]*$""".r
  private val allExampleFiles = discoverExampleFiles()
  private val entryExampleFiles = allExampleFiles.filter(isEntryExample)

  test("the FiTrie comparison corpus is not empty") {
    assert(entryExampleFiles.nonEmpty)
  }

  entryExampleFiles.foreach { exampleFile =>
    test(s"compare Fiobs and FiTrie for ${displayPath(exampleFile)}") {
      val source = Files.readString(exampleFile)
      val expectedValue = evaluateExpectedValue(source, exampleFile)
      val sourceFiles = allExampleFiles.filter(_.getParent == exampleFile.getParent).map { path =>
        CpSourceFile(path, Files.readString(path))
      }
      val targetNamespace = identifyTargetNamespace(source, exampleFile)
      val compiledProgram = Cp.compileModules(sourceFiles) match {
        case Result.Ok(program) => program
        case Result.Err(error) => fail(s"example compilation failed for ${displayPath(exampleFile)}: $error")
      }
      val fiobsValue = CpEvaluator.evaluate(compiledProgram, targetNamespace) match {
        case Result.Ok(value) => value
        case Result.Err(error) => fail(s"Fiobs evaluation failed for ${displayPath(exampleFile)}: $error")
      }
      val expectedPrimitive = primitiveValue(expectedValue, exampleFile, "expected expression")
      val fiobsPrimitive = primitiveValue(fiobsValue, exampleFile, "Fiobs evaluator")

      assertEquals(fiobsPrimitive, expectedPrimitive)

      CpFiTrieCompiler.compile(compiledProgram, targetNamespace) match {
        case Result.Ok(targetProgram) =>
          val fiTrieValue = Evaluation.observeTermination(
            targetProgram.entry,
            fiobsPrimitive.primitiveType,
            targetProgram.globalEnvironment
          ) match {
            case Result.Ok(value) => value
            case Result.Err(error) => fail(s"FiTrie evaluation failed for ${displayPath(exampleFile)}: $error")
          }
          assertEquals(fiTrieValue, fiobsPrimitive)
        case Result.Err(error) =>
          fail(s"FiTrie compilation failed for ${displayPath(exampleFile)}: $error")
      }
    }
  }

  private def evaluateExpectedValue(source: String, exampleFile: Path): Value = {
    val expectedExpression = readExpectedExpression(source, exampleFile)
    val expectedProgram = s"def main = $expectedExpression;"
    Cp.evaluate(CpSourceFile(Paths.get("Expected.cp"), expectedProgram)) match {
      case Result.Ok(value) => value
      case Result.Err(error) =>
        fail(s"invalid expected expression in ${displayPath(exampleFile)}: $error")
    }
  }

  private def identifyTargetNamespace(source: String, exampleFile: Path): Namespace = {
    Cp.parse(source) match {
      case Result.Ok(sourceModule) =>
        IdentifiedSourceModule.create(CpSourceFile(exampleFile, source), sourceModule) match {
          case Result.Ok(module) => module.namespace
          case Result.Err(error) => fail(s"invalid module identity in ${displayPath(exampleFile)}: $error")
        }
      case Result.Err(error) => fail(s"invalid example syntax in ${displayPath(exampleFile)}: $error")
    }
  }

  private def primitiveValue(value: Value, exampleFile: Path, evaluator: String): PrimitiveValue = value match {
    case Value.Primitive(primitive) => primitive
    case other => fail(
      s"$evaluator produced a non-primitive result for ${displayPath(exampleFile)}: $other"
    )
  }

  private def discoverExampleFiles(): List[Path] = {
    Using.resource(Files.walk(exampleDirectory)) { paths =>
      paths.iterator.asScala
        .filter(path => Files.isRegularFile(path) && path.getFileName.toString.endsWith(".cp"))
        .toList
        .sortBy(displayPath)
    }
  }

  private def readExpectedExpression(source: String, exampleFile: Path): String = {
    expectedExpressionDirective.findAllMatchIn(source).map(_.group(1)).toList match {
      case expectedExpression :: Nil => expectedExpression
      case Nil => fail(s"missing // expected: directive in ${displayPath(exampleFile)}")
      case _ => fail(s"multiple // expected: directives in ${displayPath(exampleFile)}")
    }
  }

  private def isEntryExample(path: Path): Boolean = {
    expectedExpressionDirective.findFirstIn(Files.readString(path)).nonEmpty
  }

  private def displayPath(path: Path): String = {
    exampleDirectory.relativize(path).iterator.asScala.map(_.toString).mkString("/")
  }
}
