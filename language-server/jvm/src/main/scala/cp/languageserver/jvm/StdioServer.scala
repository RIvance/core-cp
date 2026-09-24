package cp.languageserver.jvm

import cp.languageserver.protocol.{ErrorCode, JsonRpc, RpcError}
import cp.languageserver.server.LanguageServer
import ujson.Value

import java.io.{BufferedInputStream, ByteArrayOutputStream, EOFException, IOException, InputStream, OutputStream}
import java.nio.ByteBuffer
import java.nio.charset.{CharacterCodingException, CodingErrorAction, StandardCharsets}
import java.util.Locale

/** Byte limits apply before allocating a body or parsing JSON. */
final case class FramingLimits(maximumHeaderBytes: Int = 16384, maximumContentBytes: Int = 16777216) {
  require(maximumHeaderBytes > 0 && maximumContentBytes >= 0)
}

/**
 * LSP's Content-Length transport. Lengths count UTF-8 bytes, not characters. A malformed frame
 * fails the stream: without a trustworthy length there is no unambiguous resynchronization point.
 * The caller owns the streams. Buffered input handles fragmented reads without rescanning prefixes.
 */
final class FramedConnection(
  input: InputStream,
  output: OutputStream,
  limits: FramingLimits = FramingLimits()
) {
  private val bufferedInput = new BufferedInputStream(input)

  def read(): Option[Array[Byte]] = {
    val header = new ByteArrayOutputStream
    var suffix = 0
    var complete = false
    while (!complete) {
      val next = bufferedInput.read()
      if (next < 0) {
        if (header.size() == 0) return None
        throw new EOFException("Incomplete LSP header.")
      }
      if (next > 127) throw new IOException("LSP headers must be ASCII.")
      header.write(next)
      if (header.size() > limits.maximumHeaderBytes) throw new IOException("LSP header exceeds its byte limit.")
      suffix = (suffix << 8) | next
      complete = header.size() >= 4 && suffix == 0x0d0a0d0a
    }
    val fields = new String(header.toByteArray, StandardCharsets.US_ASCII).stripSuffix("\r\n\r\n")
      .split("\r\n").toList.map { line =>
        val separator = line.indexOf(':')
        if (separator <= 0) throw new IOException("An LSP header needs a name and value.")
        line.substring(0, separator).trim -> line.substring(separator + 1).trim
      }
    val lengths = fields.collect { case (name, value) if name.equalsIgnoreCase("Content-Length") => value }
    val length = lengths match {
      case value :: Nil if value.nonEmpty && value.forall(character => character >= '0' && character <= '9') =>
        value.toIntOption.filter(_ <= limits.maximumContentBytes)
          .getOrElse(throw new IOException("LSP content length exceeds its byte limit."))
      case _ => throw new IOException("An LSP frame needs exactly one nonnegative Content-Length.")
    }
    fields.collect { case (name, value) if name.equalsIgnoreCase("Content-Type") => value }.foreach { value =>
      value.split(';').iterator.map(_.trim).filter(_.toLowerCase(Locale.ROOT).startsWith("charset="))
        .map(_.substring("charset=".length).trim).foreach { charset =>
          if (!charset.equalsIgnoreCase("utf-8") && !charset.equalsIgnoreCase("utf8")) {
            throw new IOException("LSP content must use UTF-8.")
          }
        }
    }
    val body = bufferedInput.readNBytes(length)
    if (body.length != length) throw new EOFException("Incomplete LSP body.")
    Some(body)
  }

  def write(message: Value): Unit = {
    val bytes = ujson.write(message).getBytes(StandardCharsets.UTF_8)
    val header = "Content-Length: " + bytes.length + "\r\n\r\n"
    output.write(header.getBytes(StandardCharsets.US_ASCII))
    output.write(bytes)
    output.flush()
  }
}

/** Blocking stdio adapter. No compiler work or process policy lives in the shared session. */
final class StdioServer(session: LanguageServer, input: InputStream, output: OutputStream) {
  def run(): Int = {
    val connection = new FramedConnection(input, output)
    var endOfInput = false
    while (!session.shouldExit && !endOfInput) {
      connection.read() match {
        case None => endOfInput = true
        case Some(bytes) =>
          val responses = try {
            val decoder = StandardCharsets.UTF_8.newDecoder()
              .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
            session.receiveText(decoder.decode(ByteBuffer.wrap(bytes)).toString)
          } catch {
            case _: CharacterCodingException =>
              List(JsonRpc.failure(None, RpcError(ErrorCode.ParseError, "The message is not valid UTF-8.")))
          }
          responses.foreach(connection.write)
      }
    }
    session.exitCode
  }
}
