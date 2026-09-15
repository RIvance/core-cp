package cp.source

/** A half-open character range within one source file. */
final case class SourceSpan(startOffset: Int, endOffset: Int) {
  require(startOffset >= 0, "a source span cannot start before its source")
  require(endOffset >= startOffset, "a source span cannot end before it starts")

  def resolveIn(source: String): Option[ResolvedSourceSpan] = {
    if (endOffset > source.length) {
      None
    } else {
      Some(ResolvedSourceSpan(
        SourcePosition.atOffset(source, startOffset),
        SourcePosition.atOffset(source, endOffset)
      ))
    }
  }
}

/** A one-based source coordinate, matching editors and parser diagnostics. */
final case class SourcePosition(line: Int, column: Int) {
  require(line >= 1, "a source position must have a positive line")
  require(column >= 1, "a source position must have a positive column")
}

private object SourcePosition {
  def atOffset(source: String, offset: Int): SourcePosition = {
    var line = 1
    var lineStart = 0
    var current = 0
    while (current < offset) {
      if (source.charAt(current) == '\n') {
        line += 1
        lineStart = current + 1
      }
      current += 1
    }
    SourcePosition(line, offset - lineStart + 1)
  }
}

final case class ResolvedSourceSpan(start: SourcePosition, end: SourcePosition)
