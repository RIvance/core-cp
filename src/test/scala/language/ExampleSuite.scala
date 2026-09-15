package cp.language

import cp.language.compilation.CpSourceFile
import cp.language.compilation.IdentifiedSourceModule
import cp.util.Result

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters.*
import scala.util.Using
import scala.util.matching.Regex

class ExampleSuite extends munit.FunSuite {
  private val exampleDirectory = Paths.get("examples").toAbsolutePath.normalize
  private val expectedExpressionDirective: Regex = """(?m)^[ \t]*//[ \t]*expected:[ \t]*(.+?)[ \t]*$""".r
  private val allExampleFiles = discoverExampleFiles()
  private val entryExampleFiles = allExampleFiles.filter(isEntryExample)
  private val compilationSets = allExampleFiles.groupBy(_.getParent).toList.sortBy { case (directory, _) =>
    displayPath(directory)
  }

  test("the example corpus is not empty") {
    assert(entryExampleFiles.nonEmpty)
  }

  compilationSets.foreach { case (directory, files) =>
    test(s"compile example set ${displayPath(directory)}") {
      val sourceFiles = files.map(path => CpSourceFile(path, Files.readString(path)))
      Cp.compileModules(sourceFiles) match {
        case Result.Ok(_) => ()
        case Result.Err(error) => fail(s"example compilation set ${displayPath(directory)} failed: $error")
      }
    }
  }

  entryExampleFiles.foreach { exampleFile =>
    test(s"example ${displayPath(exampleFile)}") {
      val source = Files.readString(exampleFile)
      val expectedExpression = readExpectedExpression(source, exampleFile)
      val expectedProgram = s"def main = $expectedExpression;"
      val expectedValue = Cp.evaluate(CpSourceFile(Paths.get("Expected.cp"), expectedProgram)) match {
        case Result.Ok(value) => value
        case Result.Err(error) => fail(s"invalid expected expression in ${displayPath(exampleFile)}: $error")
      }
      val sourceFiles = allExampleFiles.filter(_.getParent == exampleFile.getParent).map { path =>
        CpSourceFile(path, Files.readString(path))
      }
      val targetNamespace = Cp.parse(source) match {
        case Result.Ok(sourceModule) =>
          IdentifiedSourceModule.create(CpSourceFile(exampleFile, source), sourceModule) match {
            case Result.Ok(module) => module.namespace
            case Result.Err(error) => fail(s"invalid module identity in ${displayPath(exampleFile)}: $error")
          }
        case Result.Err(error) => fail(s"invalid example syntax in ${displayPath(exampleFile)}: $error")
      }

      assertEquals(Cp.evaluate(sourceFiles, targetNamespace), Result.Ok(expectedValue))
    }
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
