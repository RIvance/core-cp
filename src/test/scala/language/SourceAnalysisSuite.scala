package cp.language

import cp.language.analysis.{CompletionCandidate, CompletionKind}
import cp.language.compilation.{CpSourceFile, SourcePath}

class SourceAnalysisSuite extends munit.FunSuite {
  private def candidates(marked: String, dependencies: List[CpSourceFile] = Nil): List[CompletionCandidate] = {
    val offset = marked.indexOf('¦')
    assert(offset >= 0, "the test needs one cursor position")
    val source = CpSourceFile(SourcePath("Main.cp"), marked.replace("¦", ""))
    val report = Cp.analyze(source :: dependencies)
    val sites = report.completions.getOrElse(source.path, Nil)
      .filter(site => site.span.startOffset <= offset && offset <= site.span.endOffset)
    assert(sites.nonEmpty, s"No completion site at $offset: $report")
    sites.minBy(site => site.span.endOffset - site.span.startOffset).candidates
  }

  test("record completion uses the inferred receiver and retains fields of every result type") {
    val found = candidates("def result(record: { field: Int; file: String }): Int = record.fi¦")
    assertEquals(found.map(_.name), List("field", "file"))
    assertEquals(found.map(_.description), List("Int", "String"))
    assert(found.forall(_.kind == CompletionKind.Field))
  }

  test("local shadowing, inferred lets and open use the actual elaboration scope") {
    val found = candidates("""
      |def same: Bool = true
      |def result(same: String, outer: Bool) =
      |  let same = 42 in open { exposed = "text" } in sam¦
      |""".stripMargin)
    val byName = found.map(candidate => candidate.name -> candidate.description).toMap
    assertEquals(byName("same"), "Int")
    assertEquals(byName("outer"), "Bool")
    assertEquals(byName("exposed"), "String")
    assertEquals(found.count(_.name == "same"), 1)
  }

  test("the initializer of a nonrecursive let does not see that binding or later bindings") {
    val found = candidates("def result = let future = fut¦ in let later = 1 in future")
    assert(!found.exists(candidate => Set("future", "later").contains(candidate.name)))
  }

  test("intersection fields combine by CP projection and recursive records require unfolding") {
    val found = candidates("def result = ({ shared = 1; left = true } ,, { shared = \"s\"; right = 2 }).sha¦")
    assertEquals(found.map(_.name), List("left", "right", "shared"))
    assertEquals(found.find(_.name == "shared").map(_.description), Some("Int & String"))
    assertEquals(candidates("type R = mu X. { value: Int }; def result(record: R) = record.va¦"), Nil)
    assertEquals(
      candidates("type R = mu X. { value: Int }; def result(record: R) = (unfold[R] record).va¦").map(_.name),
      List("value")
    )
  }

  test("qualified lookup follows imports and excludes locals") {
    val library = CpSourceFile(SourcePath("Library.cp"), "def value = { answer = 42 }; def other = true")
    val found = candidates("import module Library; def result(value: String) = Library::va¦", List(library))
    assertEquals(found.map(_.name), List("other", "value"))
    assertEquals(found.find(_.name == "value").map(_.description), Some("{answer : Int}"))
    assertEquals(candidates("def result = Library::va¦", List(library)), Nil)
  }

  test("the observer does not add a recursive dependency by enumerating candidates") {
    val source = CpSourceFile(SourcePath("Main.cp"), "def helper = 42; def result = helper")
    assertEquals(Cp.analyze(List(source)).diagnostics, Nil)
    assert(Cp.compile(source).toOption.nonEmpty)
    assertEquals(candidates("def helper = 42; def result = hel¦").map(_.name), List("helper"))
  }

  test("incomplete type names retain established signatures and their enclosing type binders") {
    val found = candidates("type Count = Int; def result[A](value: A): Co¦ = value")
    assert(found.exists(candidate => candidate.name == "Count" && candidate.description == "Int"))
    assert(found.exists(candidate => candidate.name == "A" && candidate.kind == CompletionKind.TypeParameter))
    assert(found.exists(candidate => candidate.name == "Int" && candidate.kind == CompletionKind.Type))
  }

  test("term candidates under type binders render the source names of those binders") {
    val found = candidates("def result[A](value: A) = va¦")
    assertEquals(found.find(_.name == "value").map(_.description), Some("A"))
  }

  test("type completion respects built-in syntax while qualified names still access homonymous aliases") {
    val found = candidates("type Int = String; type Float = Bool; def result[Int] = (42 : I¦)")
    assertEquals(found.filter(_.name == "Int"), List(CompletionCandidate("Int", CompletionKind.Type, "Int")))
    assert(!found.exists(_.name == "Float"))
    val library = CpSourceFile(SourcePath("Library.cp"), "type Int = String")
    val qualified = candidates("import module Library; def result = (42 : Library::I¦)", List(library))
    assertEquals(qualified, List(CompletionCandidate("Int", CompletionKind.Type, "String")))
  }
}
