package cp.languageserver.server

import cp.languageserver.document.{DocumentStore, DocumentUri, TextDocument}
import cp.languageserver.protocol.*
import cp.util.Result
import ujson.Value

import scala.util.control.NonFatal

private enum Lifecycle {
  case Created, Initializing, Running, Shutdown, Exited
}

/** A feature owns both its request handler and the capability it advertises. */
private final case class RequestEndpoint(
  method: String,
  capability: (String, Value),
  handle: JsonInput => Result[Value, RpcError]
)

/**
 * One serialized LSP session. Transports call receive in arrival order on a single owning thread.
 * Analysis is synchronous and completes against that exact document snapshot before the next
 * message. Cancellation arriving after a response is therefore a no-op, as permitted by LSP.
 * The session owns neither streams, workers, timers, filesystem access nor a playground.
 */
final class LanguageServer(backend: LanguageBackend) {
  private var lifecycle = Lifecycle.Created
  private var documents = DocumentStore.empty
  private var status = 1

  private val endpoints = List(
    RequestEndpoint(
      "textDocument/completion",
      "completionProvider" -> ujson.Obj(
        "triggerCharacters" -> ujson.Arr.from(backend.completionTriggers), "resolveProvider" -> false
      ),
      complete
    )
  )
  private val requests = endpoints.map(endpoint => endpoint.method -> endpoint).toMap

  def shouldExit: Boolean = lifecycle == Lifecycle.Exited
  def exitCode: Int = status

  def receive(message: Value): List[Value] = dispatch(JsonRpc.decode(message))
  def receiveText(message: String): List[Value] = dispatch(JsonRpc.parse(message))

  private def dispatch(decoded: Result[IncomingMessage, InvalidMessage]): List[Value] = {
    if (shouldExit) Nil else decoded match {
      case Result.Err(failure) => List(JsonRpc.failure(failure.id, failure.error))
      case Result.Ok(IncomingMessage.Response) => Nil
      case Result.Ok(IncomingMessage.Request(id, method, params)) =>
        val result = try request(method, JsonInput(params)) catch {
          case NonFatal(error) => Result.Err(RpcError(ErrorCode.InternalError, safeMessage(error)))
        }
        List(result match {
          case Result.Ok(value) => JsonRpc.response(id, value)
          case Result.Err(error) => JsonRpc.failure(Some(id), error)
        })
      case Result.Ok(IncomingMessage.Notification(method, params)) =>
        try notification(method, JsonInput(params)) catch {
          case NonFatal(error) => List(log(safeMessage(error)))
        }
    }
  }

  private def request(method: String, params: JsonInput): Result[Value, RpcError] = {
    if (method == "initialize" && lifecycle == Lifecycle.Created) {
      for {
        _ <- params.objectValue
        _ <- params.field("capabilities").flatMap(_.objectValue)
      } yield {
        lifecycle = Lifecycle.Initializing
        val capabilities = ujson.Obj("textDocumentSync" -> 1, "positionEncoding" -> "utf-16")
        endpoints.foreach(endpoint => capabilities(endpoint.capability._1) = endpoint.capability._2)
        ujson.Obj("capabilities" -> capabilities, "serverInfo" -> ujson.Obj("name" -> "CP language server"))
      }
    } else if (method == "initialize") {
      Result.Err(RpcError(ErrorCode.InvalidRequest, "The server has already been initialized."))
    } else {
      lifecycle match {
        case Lifecycle.Created | Lifecycle.Initializing =>
          Result.Err(RpcError(ErrorCode.ServerNotInitialized, "The server has not finished initialization."))
        case Lifecycle.Shutdown | Lifecycle.Exited =>
          Result.Err(RpcError(ErrorCode.InvalidRequest, "The server has shut down."))
        case Lifecycle.Running if method == "shutdown" =>
          lifecycle = Lifecycle.Shutdown
          Result.Ok(ujson.Null)
        case Lifecycle.Running => requests.get(method) match {
          case Some(endpoint) => endpoint.handle(params)
          case None => Result.Err(RpcError(ErrorCode.MethodNotFound, s"Unknown request method '$method'."))
        }
      }
    }
  }

  private def notification(method: String, params: JsonInput): List[Value] = method match {
    case "exit" =>
      status = if (lifecycle == Lifecycle.Shutdown) 0 else 1
      lifecycle = Lifecycle.Exited
      Nil
    case "initialized" if lifecycle == Lifecycle.Initializing =>
      params.objectValue match {
        case Result.Ok(_) => lifecycle = Lifecycle.Running; Nil
        case Result.Err(error) => List(log(error.message))
      }
    case _ if lifecycle != Lifecycle.Running => Nil
    case "textDocument/didOpen" =>
      applyChange(for {
        input <- params.field("textDocument")
        uri <- documentUri(input)
        version <- input.field("version").flatMap(_.integer())
        text <- input.field("text").flatMap(_.string)
        _ <- input.field("languageId").flatMap(_.string)
        updated <- documents.open(new TextDocument(uri, version, text))
      } yield updated)
    case "textDocument/didChange" =>
      applyChange(for {
        input <- params.field("textDocument")
        uri <- documentUri(input)
        version <- input.field("version").flatMap(_.integer())
        changes <- params.field("contentChanges").flatMap(_.elements)
        text <- fullReplacement(changes)
        updated <- documents.change(uri, version, text)
      } yield updated)
    case "textDocument/didClose" =>
      params.field("textDocument").flatMap(documentUri) match {
        case Result.Err(error) => List(log(error.message))
        case Result.Ok(uri) =>
          documents = documents.close(uri)
          publish(uri, None, Nil) :: diagnostics()
      }
    // Unknown notifications, including cancellation of already completed requests, need no response.
    case _ => Nil
  }

  private def fullReplacement(changes: List[JsonInput]): Result[String, RpcError] = {
    Result.traverse(changes) { change =>
      if (change.optional("range").nonEmpty) {
        Result.Err(RpcError(ErrorCode.InvalidParams, "This server requires full-document changes."))
      } else {
        change.field("text").flatMap(_.string)
      }
    }.flatMap { texts =>
      texts.lastOption match {
        case Some(text) => Result.Ok(text)
        case None => Result.Err(RpcError(ErrorCode.InvalidParams, "contentChanges cannot be empty."))
      }
    }
  }

  private def applyChange(updated: Result[DocumentStore, RpcError]): List[Value] = updated match {
    case Result.Err(error) => List(log(error.message))
    case Result.Ok(snapshot) if snapshot == documents => Nil
    case Result.Ok(snapshot) => documents = snapshot; diagnostics()
  }

  private def complete(params: JsonInput): Result[Value, RpcError] = for {
    input <- params.field("textDocument")
    uri <- documentUri(input)
    position <- params.field("position").flatMap(Position.decode)
    result <- documents.get(uri) match {
      case None => Result.Ok(ujson.Null)
      case Some(document) => document.offset(position)
        .map(offset => backend.complete(documents, document, offset).json)
    }
  } yield result

  private def diagnostics(): List[Value] = {
    val report = try backend.check(documents) catch {
      case NonFatal(error) => DiagnosticReport(Map.empty, List(safeMessage(error)))
    }
    report.messages.map(log) ++ documents.values.map { document =>
      publish(document.uri, Some(document.version), report.diagnostics.getOrElse(document.uri, Nil))
    }
  }

  private def documentUri(input: JsonInput): Result[DocumentUri, RpcError] =
    input.field("uri").flatMap(_.string).flatMap(DocumentUri.parse)

  private def publish(uri: DocumentUri, version: Option[Int], diagnostics: List[Diagnostic]): Value = {
    val params = ujson.Obj("uri" -> uri.value, "diagnostics" -> ujson.Arr.from(diagnostics.map(_.json)))
    version.foreach(value => params("version") = ujson.Num(value))
    JsonRpc.notification("textDocument/publishDiagnostics", params)
  }

  private def log(message: String): Value =
    JsonRpc.notification("window/logMessage", ujson.Obj("type" -> 1, "message" -> message))

  private def safeMessage(error: Throwable): String =
    Option(error.getMessage).filter(_.nonEmpty).getOrElse(error.getClass.getSimpleName)
}
