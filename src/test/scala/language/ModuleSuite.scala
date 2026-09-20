package cp.language

import cp.fiobs.Term
import cp.fiobs.runtime.Value
import cp.language.compilation.{CpSourceFile, ModuleSourceError}
import cp.language.elaboration.{CpElaborationError, NameResolutionError}
import cp.language.evaluation.{CpEvaluationError, CpEvaluator}
import cp.naming.Namespace
import cp.primitive.PrimitiveValue
import cp.util.Result

import java.nio.file.Paths

class ModuleSuite extends munit.FunSuite {
  test("compiling no source modules is a structured error") {
    assertEquals(Cp.compileModules(Nil), Result.Err(CpCompilationError.NoSourceModules))
  }

  test("named and module-only imports provide their distinct visibility") {
    val someModule = sourceFile(
      "SomeModule.cp",
      """
        |module SomeModule;
        |def someImportedThing: Int = 40;
        |""".stripMargin
    )
    val someOtherModule = sourceFile(
      "SomeOtherModule.cp",
      """
        |module SomeOtherModule;
        |def someOtherImportedThing: Int = 2;
        |""".stripMargin
    )
    val application = sourceFile(
      "Application.cp",
      """
        |module Application;
        |import SomeModule::someImportedThing;
        |import module SomeOtherModule;
        |def main: Int =
        |  someImportedThing +
        |  SomeModule::someImportedThing - 40 +
        |  SomeOtherModule::someOtherImportedThing;
        |""".stripMargin
    )

    assertEquals(
      Cp.evaluate(List(application, someOtherModule, someModule), Namespace("Application")),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("wildcard imports expose term signatures and normalized type definitions") {
    val data = sourceFile(
      "Data.cp",
      """
        |module Data;
        |type Number = Int;
        |def number: Number = 42;
        |""".stripMargin
    )
    val application = sourceFile(
      "Application.cp",
      """
        |module Application;
        |import Data::*;
        |def identity(value: Number): Number = value;
        |def main: Int = identity(number);
        |""".stripMargin
    )

    assertEquals(
      Cp.evaluate(List(application, data), Namespace("Application")),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("module-only imports support qualified type definitions") {
    val data = sourceFile("Data.cp", "module Data; type Number = Int;")
    val application = sourceFile(
      "Application.cp",
      """
        |module Application;
        |import module Data;
        |def identity(value: Data::Number): Data::Number = value;
        |def main: Int = identity(42);
        |""".stripMargin
    )

    assertEquals(
      Cp.evaluate(List(application, data), Namespace("Application")),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("explicit member imports take precedence over wildcard imports") {
    val first = sourceFile("First.cp", "module First; def answer: Int = 0;")
    val second = sourceFile("Second.cp", "module Second; def answer: Int = 42;")
    val application = sourceFile(
      "Application.cp",
      """
        |module Application;
        |import First::*;
        |import Second::answer;
        |def main: Int = answer;
        |""".stripMargin
    )

    assertEquals(
      Cp.evaluate(List(application, second, first), Namespace("Application")),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("same-precedence imports are rejected when an unqualified name is ambiguous") {
    val first = sourceFile("First.cp", "module First; def answer: Int = 1;")
    val second = sourceFile("Second.cp", "module Second; def answer: Int = 2;")
    val application = sourceFile(
      "Application.cp",
      """
        |module Application;
        |import First::answer;
        |import Second::answer;
        |def main: Int = answer;
        |""".stripMargin
    )

    Cp.compileModules(List(application, first, second)) match {
      case Result.Err(CpCompilationError.Elaboration(
            _,
            CpElaborationError.Located(
              _,
              CpElaborationError.NameResolution(NameResolutionError.AmbiguousTerm("answer", candidates))
            )
          )) =>
        assertEquals(
          candidates,
          List(Namespace("First").identifier("answer"), Namespace("Second").identifier("answer"))
        )
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("a named import must identify an exported type or term") {
    val library = sourceFile("Library.cp", "module Library; def available: Int = 42;")
    val application = sourceFile(
      "Application.cp",
      "module Application; import Library::missing; def main: Int = 42;"
    )

    Cp.compileModules(List(application, library)) match {
      case Result.Err(CpCompilationError.Elaboration(
            _,
            CpElaborationError.NameResolution(NameResolutionError.UnknownImportedMember(identifier))
          )) => assertEquals(identifier, Namespace("Library").identifier("missing"))
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("module-only imports do not introduce unqualified members") {
    val library = sourceFile("Library.cp", "module Library; def answer: Int = 42;")
    val application = sourceFile(
      "Application.cp",
      "module Application; import module Library; def main: Int = answer;"
    )

    Cp.compileModules(List(application, library)) match {
      case Result.Err(CpCompilationError.Elaboration(
            Namespace(Vector("Application")),
            CpElaborationError.Located(
              _,
              CpElaborationError.NameResolution(NameResolutionError.UnknownTerm(_))
            )
          )) => ()
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("qualified references require an import for their absolute root namespace") {
    val library = sourceFile("Library.cp", "module Library; def answer: Int = 42;")
    val application = sourceFile(
      "Application.cp",
      "module Application; def main: Int = Library::answer;"
    )

    Cp.compileModules(List(application, library)) match {
      case Result.Err(CpCompilationError.Elaboration(
            Namespace(Vector("Application")),
            CpElaborationError.Located(
              _,
              CpElaborationError.NameResolution(NameResolutionError.ModuleNotImported(
                Namespace(Vector("Library"))
              ))
            )
          )) => ()
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("qualified paths are absolute and never implicitly relative") {
    val rootC = sourceFile("RootC.cp", "module C; def value: Int = 42;")
    val nestedC = sourceFile("NestedC.cp", "module A::B::C; def value: Int = 0;")
    val application = sourceFile(
      "Application.cp",
      """
        |module A::B;
        |import module C;
        |def main: Int = C::value;
        |""".stripMargin
    )

    assertEquals(
      Cp.evaluate(List(application, nestedC, rootC), Namespace("A", "B")),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("imports are not transitive") {
    val base = sourceFile("Base.cp", "module Base; def answer: Int = 42;")
    val middle = sourceFile(
      "Middle.cp",
      "module Middle; import Base::answer; def forwarded: Int = answer;"
    )
    val application = sourceFile(
      "Application.cp",
      "module Application; import module Middle; def main: Int = Base::answer;"
    )

    Cp.compileModules(List(application, middle, base)) match {
      case Result.Err(CpCompilationError.Elaboration(
            _,
            CpElaborationError.Located(
              _,
              CpElaborationError.NameResolution(NameResolutionError.ModuleNotImported(namespace))
            )
          )) => assertEquals(namespace, Namespace("Base"))
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("a dependency module's main remains an ordinary importable definition") {
    val library = sourceFile("Library.cp", "module Library; def main: Int = 40;")
    val application = sourceFile(
      "Application.cp",
      """
        |module Application;
        |import Library::main;
        |def importedEntry: Int = Library::main;
        |def main: Int = importedEntry + 2;
        |""".stripMargin
    )

    assertEquals(
      Cp.evaluate(List(application, library), Namespace("Application")),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
    assertEquals(
      Cp.evaluate(List(application, library), Namespace("Library")),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(40)))
    )
  }

  test("runtime dependency closure includes a dependency's internal mutual-recursion bundle") {
    val library = sourceFile(
      "Library.cp",
      """
        |module Library;
        |def even(value: Int): Bool =
        |  if value == 0 then true else odd(value - 1);
        |def odd(value: Int): Bool =
        |  if value == 0 then false else even(value - 1);
        |def answer: Bool = even(42);
        |""".stripMargin
    )
    val application = sourceFile(
      "Application.cp",
      "module Application; import Library::answer; def main: Bool = answer;"
    )

    assertEquals(
      Cp.evaluate(List(application, library), Namespace("Application")),
      Result.Ok(Value.Primitive(PrimitiveValue.Boolean(true)))
    )
  }

  test("compilation does not require main and module evaluation alone selects it") {
    val library = sourceFile("Library.cp", "module Library; def answer: Int = 42;")

    Cp.compileModules(List(library)) match {
      case Result.Ok(program) =>
        assertEquals(
          CpEvaluator.evaluate(program, Namespace("Library")),
          Result.Err(CpEvaluationError.EntryPointNotFound(Namespace("Library").identifier("main")))
        )
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("cross-module references remain globals instead of becoming de Bruijn variables") {
    val library = sourceFile("Library.cp", "module Library; def answer: Int = 42;")
    val application = sourceFile(
      "Application.cp",
      "module Application; import Library::answer; def main: Int = answer;"
    )

    Cp.compileModules(List(application, library)) match {
      case Result.Ok(program) =>
        val entryPoint = program.modules(Namespace("Application"))
          .definitions(Namespace("Application").identifier("main"))
        entryPoint.sourceTerm match {
          case Term.Annotation(Term.Global(identifier), _) =>
            assertEquals(identifier, Namespace("Library").identifier("answer"))
          case other => fail(s"unexpected compiled entry term: $other")
        }
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("definition order does not constrain forward global references") {
    val application = sourceFile(
      "Application.cp",
      """
        |module Application;
        |def main: Int = Application::answer;
        |def answer = 42;
        |""".stripMargin
    )

    assertEquals(
      Cp.evaluate(application),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("type definitions are dependency-ordered and fully expanded") {
    val application = sourceFile(
      "Application.cp",
      """
        |module Application;
        |type Answer = Number;
        |type Number = Int;
        |def answer: Answer = 42;
        |def main: Int = (answer : Int);
        |""".stripMargin
    )

    assertEquals(
      Cp.evaluate(application),
      Result.Ok(Value.Primitive(PrimitiveValue.Integer(42)))
    )
  }

  test("recursive type definitions are rejected without introducing recursive types") {
    val application = sourceFile(
      "Application.cp",
      """
        |module Application;
        |type First = Second;
        |type Second = First;
        |""".stripMargin
    )

    Cp.compile(application) match {
      case Result.Err(CpCompilationError.Elaboration(
            _,
            CpElaborationError.RecursiveTypeDefinitions(identifiers)
          )) =>
        assertEquals(identifiers.map(_.name), List("First", "Second"))
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("module dependency cycles are rejected before elaboration") {
    val first = sourceFile(
      "First.cp",
      "module First; import module Second; def first: Int = Second::second;"
    )
    val second = sourceFile(
      "Second.cp",
      "module Second; import module First; def second: Int = First::first;"
    )

    assertEquals(
      Cp.compileModules(List(first, second)),
      Result.Err(CpCompilationError.CircularModuleDependencies(List(
        Namespace("First"),
        Namespace("Second")
      )))
    )
  }

  test("two source files cannot define the same absolute module") {
    val first = sourceFile("First.cp", "module Duplicate; def first: Int = 1;")
    val second = sourceFile("Second.cp", "module Duplicate; def second: Int = 2;")

    assertEquals(
      Cp.compileModules(List(second, first)),
      Result.Err(CpCompilationError.DuplicateModule(
        Namespace("Duplicate"),
        List(first.path, second.path)
      ))
    )
  }

  test("an omitted module declaration uses only the PascalCase filename") {
    val source = sourceFile("FilenameModule.cp", "def main: Int = 42;")

    Cp.compile(source) match {
      case Result.Ok(module) => assertEquals(module.namespace, Namespace("FilenameModule"))
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("an explicit module declaration is authoritative over the filename") {
    val source = sourceFile("DifferentFilename.cp", "module Declared::Module; def main: Int = 42;")

    Cp.compile(source) match {
      case Result.Ok(module) => assertEquals(module.namespace, Namespace("Declared", "Module"))
      case other => fail(s"unexpected compilation result: $other")
    }
  }

  test("raw source without a module declaration has no implicit module identity") {
    assertEquals(
      Cp.compile("def main: Int = 42;"),
      Result.Err(CpCompilationError.Source(ModuleSourceError.ModuleIdentityUnavailable))
    )
  }

  test("ordinary source module filenames must use PascalCase") {
    val source = sourceFile("filename_module.cp", "def main: Int = 42;")

    assertEquals(
      Cp.compile(source),
      Result.Err(CpCompilationError.Source(ModuleSourceError.InvalidModuleFileName(source.path)))
    )
  }

  private def sourceFile(fileName: String, contents: String): CpSourceFile = {
    CpSourceFile(Paths.get(fileName), contents)
  }
}
