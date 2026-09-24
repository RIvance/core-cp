package cp.languageserver.browser

import cp.languageserver.compiler.CpBackend
import cp.languageserver.protocol.{ErrorCode, JsonRpc, RpcError}
import cp.languageserver.server.LanguageServer
import cp.util.Result

import scala.scalajs.js
import scala.scalajs.js.annotation.{JSExportTopLevel, JSGlobal}
import scala.util.control.NonFatal

@js.native
trait WorkerMessage extends js.Object {
  val data: js.Any = js.native
}

/** The standard dedicated-worker interface; no editor or host-library types cross this boundary. */
@js.native
trait WorkerPort extends js.Object {
  def addEventListener(kind: String, listener: js.Function1[WorkerMessage, Unit]): Unit = js.native
  def removeEventListener(kind: String, listener: js.Function1[WorkerMessage, Unit]): Unit = js.native
  def postMessage(message: js.Any): Unit = js.native
  def close(): Unit = js.native
}

@js.native
@JSGlobal("self")
private object CurrentWorker extends WorkerPort

// JSON.stringify has no string result for values with no JSON representation.
@js.native
@JSGlobal("JSON")
private object WorkerJson extends js.Object {
  def stringify(value: js.Any): js.UndefOr[String] = js.native
}

/** Converts worker messages to JSON-RPC; all protocol and compiler work stays in shared Scala. */
final class WorkerServer(port: WorkerPort, session: LanguageServer) {
  private val listener: js.Function1[WorkerMessage, Unit] = event => {
    val encoded = try {
      WorkerJson.stringify(event.data).toOption match {
        case Some(message) => Result.Ok(message)
        case None => Result.Err(RpcError(ErrorCode.InvalidRequest, "Expected a JSON-RPC object."))
      }
    } catch {
      case NonFatal(_) =>
        Result.Err(RpcError(ErrorCode.InvalidRequest, "The worker message is not JSON data."))
    }
    val replies = encoded match {
      case Result.Ok(message) => session.receiveText(message)
      case Result.Err(error) => List(JsonRpc.failure(None, error))
    }
    replies.foreach(reply => port.postMessage(js.JSON.parse(ujson.write(reply))))
    if (session.shouldExit) {
      stop()
      port.close()
    }
  }

  port.addEventListener("message", listener)

  /** Detaches this session without terminating its host-owned worker. Safe to call more than once. */
  def stop(): Unit = port.removeEventListener("message", listener)
}

object WorkerServer {
  /** Call once in a dedicated worker. The return value disposes the session's listener. */
  @JSExportTopLevel("startBrowserLanguageServer")
  def startBrowserLanguageServer(): js.Function0[Unit] = {
    val server = new WorkerServer(CurrentWorker, new LanguageServer(new CpBackend))
    () => server.stop()
  }
}
