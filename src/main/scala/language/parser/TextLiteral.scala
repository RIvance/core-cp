package cp.language.parser

import cp.util.Result

private[parser] enum TextLiteralError(val offset: Int) {
  case InvalidEscape(at: Int, character: Char) extends TextLiteralError(at)
  case InvalidUnicodeEscape(at: Int, digits: String) extends TextLiteralError(at)
  case IncompleteEscape(at: Int) extends TextLiteralError(at)

  def message: String = this match {
    case InvalidEscape(_, character) => s"invalid string escape: \\$character"
    case InvalidUnicodeEscape(_, digits) => s"a Unicode escape requires four hexadecimal digits, received '$digits'"
    case IncompleteEscape(_) => "incomplete string escape"
  }
}

/** Decodes the contents of a quoted literal without throwing for malformed source text. */
private[parser] object TextLiteral {
  def decode(contents: String): Result[String, TextLiteralError] = {
    val decoded = new StringBuilder
    var offset = 0
    while (offset < contents.length) {
      val character = contents.charAt(offset)
      if (character != '\\') {
        decoded.append(character)
        offset += 1
      } else if (offset + 1 == contents.length) {
        return Result.Err(TextLiteralError.IncompleteEscape(offset))
      } else {
        contents.charAt(offset + 1) match {
          case 'u' =>
            val digits = contents.substring(offset + 2, math.min(contents.length, offset + 6))
            if (digits.length != 4 || !digits.forall(character => Character.digit(character, 16) >= 0)) {
              return Result.Err(TextLiteralError.InvalidUnicodeEscape(offset, digits))
            }
            decoded.append(Integer.parseInt(digits, 16).toChar)
            offset += 6
          case escaped =>
            val value = escaped match {
              case 'b' => '\b'
              case 't' => '\t'
              case 'n' => '\n'
              case 'f' => '\f'
              case 'r' => '\r'
              case '\\' => '\\'
              case '"' => '"'
              case '\'' => '\''
              case other => return Result.Err(TextLiteralError.InvalidEscape(offset, other))
            }
            decoded.append(value)
            offset += 2
        }
      }
    }
    Result.Ok(decoded.result())
  }
}
