package cp.languageserver.document

import cp.languageserver.protocol.{ErrorCode, Position, Range, RpcError}
import cp.util.Result

import java.net.{URI, URISyntaxException}

/** Client identity is the original URI. Decoded source names retain the scheme and authority. */
final case class DocumentUri private (value: String, path: String, sourceName: String)

object DocumentUri {
  def parse(value: String): Result[DocumentUri, RpcError] = {
    try {
      val uri = new URI(value)
      Option(uri.getPath).filter(_.nonEmpty) match {
        case Some(path) if uri.isAbsolute =>
          val authority = Option(uri.getAuthority).fold("")("//" + _)
          Result.Ok(DocumentUri(value, path, uri.getScheme + ":" + authority + path))
        case _ => Result.Err(RpcError(ErrorCode.InvalidParams, "A document URI needs an absolute scheme and path."))
      }
    } catch {
      case error: URISyntaxException => Result.Err(RpcError(ErrorCode.InvalidParams, error.getMessage))
    }
  }
}

/** One immutable document version. Line boundaries include LF, CRLF and CR. */
final class TextDocument(val uri: DocumentUri, val version: Int, val text: String) {
  private final case class Line(start: Int, end: Int)

  private val lines: Vector[Line] = {
    val result = Vector.newBuilder[Line]
    var start = 0
    var offset = 0
    while (offset < text.length) {
      text.charAt(offset) match {
        case '\r' | '\n' =>
          result += Line(start, offset)
          if (text.charAt(offset) == '\r' && offset + 1 < text.length && text.charAt(offset + 1) == '\n') {
            offset += 1
          }
          start = offset + 1
        case _ => ()
      }
      offset += 1
    }
    result += Line(start, text.length)
    result.result()
  }

  def offset(position: Position): Result[Int, RpcError] = {
    if (position.line < 0 || position.character < 0) {
      Result.Err(RpcError(ErrorCode.InvalidParams, "Document positions cannot be negative."))
    } else {
      // LSP clamps positions beyond a line or document to its end.
      val offset = if (position.line >= lines.size) text.length else {
        val line = lines(position.line)
        line.start + math.min(position.character, line.end - line.start)
      }
      if (isCharacterBoundary(offset)) Result.Ok(offset)
      else Result.Err(RpcError(ErrorCode.InvalidParams, "The position splits a UTF-16 surrogate pair."))
    }
  }

  def position(offset: Int): Option[Position] = {
    if (offset < 0 || offset > text.length || !isCharacterBoundary(offset)) {
      None
    } else {
      var low = 0
      var high = lines.size
      while (low + 1 < high) {
        val middle = low + (high - low) / 2
        if (lines(middle).start <= offset) low = middle else high = middle
      }
      Some(Position(low, math.min(offset, lines(low).end) - lines(low).start))
    }
  }

  def range(start: Int, end: Int): Option[Range] = {
    if (end < start) None else for {
      from <- position(start)
      to <- position(end)
    } yield Range(from, to)
  }

  private def isCharacterBoundary(offset: Int): Boolean = {
    offset == 0 || offset == text.length ||
      !(Character.isHighSurrogate(text.charAt(offset - 1)) && Character.isLowSurrogate(text.charAt(offset)))
  }
}

/** Full-document synchronization is atomic; stale versions cannot overwrite accepted text. */
final case class DocumentStore private (private val documents: Map[DocumentUri, TextDocument]) {
  def get(uri: DocumentUri): Option[TextDocument] = documents.get(uri)
  def values: List[TextDocument] = documents.values.toList.sortBy(_.uri.value)

  def open(document: TextDocument): Result[DocumentStore, RpcError] = {
    if (documents.contains(document.uri)) {
      Result.Err(RpcError(ErrorCode.InvalidParams, "The document is already open."))
    } else {
      Result.Ok(DocumentStore(documents.updated(document.uri, document)))
    }
  }

  def change(uri: DocumentUri, version: Int, text: String): Result[DocumentStore, RpcError] = {
    documents.get(uri) match {
      case None => Result.Err(RpcError(ErrorCode.InvalidParams, "The document is not open."))
      case Some(document) if version <= document.version => Result.Ok(this)
      case Some(_) => Result.Ok(DocumentStore(documents.updated(uri, new TextDocument(uri, version, text))))
    }
  }

  def close(uri: DocumentUri): DocumentStore = DocumentStore(documents.removed(uri))
}

object DocumentStore {
  val empty: DocumentStore = DocumentStore(Map.empty)
}
