package cp.language.parser

import scala.util.parsing.input.CharSequenceReader

/** Locates layout boundaries between a block binding and its following expression. */
private[parser] object BlockStatementLayout {
  def boundaries(source: CharSequence, start: Int, bindingColumn: Int): List[Int] = {
    val boundaries = List.newBuilder[Int]
    var index = start
    var parenthesisDepth = 0
    var bracketDepth = 0
    var braceDepth = 0

    while (index < source.length) {
      source.charAt(index) match {
        case '"' => index = skipTextLiteral(source, index)
        case '/' if startsWith(source, index, "//") =>
          index = skipLineComment(source, index + 2)
        case '-' if startsWith(source, index, "--") =>
          index = skipLineComment(source, index + 2)
        case '/' if startsWith(source, index, "/*") =>
          index = skipBlockComment(source, index + 2)
        case '(' =>
          parenthesisDepth += 1
          index += 1
        case ')' =>
          parenthesisDepth = math.max(0, parenthesisDepth - 1)
          index += 1
        case '[' =>
          bracketDepth += 1
          index += 1
        case ']' =>
          bracketDepth = math.max(0, bracketDepth - 1)
          index += 1
        case '{' =>
          braceDepth += 1
          index += 1
        case '}' if braceDepth > 0 =>
          braceDepth -= 1
          index += 1
        case '}' => index = source.length
        case '\r' if atBaseDelimiterDepth(parenthesisDepth, bracketDepth, braceDepth) =>
          val nextLine = if (index + 1 < source.length && source.charAt(index + 1) == '\n') index + 2 else index + 1
          addBoundary(source, nextLine, bindingColumn, boundaries)
          index = nextLine
        case '\n' if atBaseDelimiterDepth(parenthesisDepth, bracketDepth, braceDepth) =>
          val nextLine = index + 1
          addBoundary(source, nextLine, bindingColumn, boundaries)
          index = nextLine
        case _ => index += 1
      }
    }

    boundaries.result().distinct
  }

  private def addBoundary(
    source: CharSequence,
    boundary: Int,
    bindingColumn: Int,
    boundaries: scala.collection.mutable.Builder[Int, List[Int]]
  ): Unit = {
    nextTokenOffset(source, boundary).foreach { tokenOffset =>
      val tokenColumn = new CharSequenceReader(source, tokenOffset).pos.column
      if (tokenColumn <= bindingColumn && source.charAt(tokenOffset) != '}') {
        boundaries += boundary
      }
    }
  }

  private def nextTokenOffset(source: CharSequence, start: Int): Option[Int] = {
    var index = start
    var searching = true

    while (index < source.length && searching) {
      if (source.charAt(index).isWhitespace) {
        index += 1
      } else if (startsWith(source, index, "//") || startsWith(source, index, "--")) {
        index = skipLineComment(source, index + 2)
      } else if (startsWith(source, index, "/*")) {
        index = skipBlockComment(source, index + 2)
      } else {
        searching = false
      }
    }

    Option.when(index < source.length)(index)
  }

  private def skipTextLiteral(source: CharSequence, openingQuote: Int): Int = {
    var index = openingQuote + 1
    var escaped = false

    while (index < source.length) {
      val character = source.charAt(index)
      if (escaped) {
        escaped = false
      } else if (character == '\\') {
        escaped = true
      } else if (character == '"') {
        return index + 1
      }
      index += 1
    }

    index
  }

  private def skipLineComment(source: CharSequence, commentBody: Int): Int = {
    var index = commentBody
    while (index < source.length && source.charAt(index) != '\r' && source.charAt(index) != '\n') {
      index += 1
    }
    index
  }

  private def skipBlockComment(source: CharSequence, commentBody: Int): Int = {
    var index = commentBody
    while (index < source.length && !startsWith(source, index, "*/")) {
      index += 1
    }
    math.min(source.length, index + 2)
  }

  private def startsWith(source: CharSequence, offset: Int, expected: String): Boolean = {
    offset + expected.length <= source.length &&
      expected.indices.forall(index => source.charAt(offset + index) == expected.charAt(index))
  }

  private def atBaseDelimiterDepth(parentheses: Int, brackets: Int, braces: Int): Boolean = {
    parentheses == 0 && brackets == 0 && braces == 0
  }
}
