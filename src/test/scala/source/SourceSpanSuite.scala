package cp.source

class SourceSpanSuite extends munit.FunSuite {
  test("a half-open span resolves across source lines using editor coordinates") {
    val source = "first\nsecond expression\nthird"
    val start = source.indexOf("second")
    val end = start + "second expression".length

    assertEquals(
      SourceSpan(start, end).resolveIn(source),
      Some(ResolvedSourceSpan(
        SourcePosition(2, 1),
        SourcePosition(2, 18)
      ))
    )
  }

  test("a span outside its source cannot be resolved") {
    assertEquals(SourceSpan(2, 5).resolveIn("abc"), None)
  }
}
