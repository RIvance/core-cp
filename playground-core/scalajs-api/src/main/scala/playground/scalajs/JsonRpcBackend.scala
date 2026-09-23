package playground.scalajs

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js
import scala.scalajs.js.JSConverters.*
import scala.util.Try

/**
 * Optional message-server interface, independent of execution and of any particular language protocol.
 * Export a factory accepting a js.Function1[js.Object, Unit] outbound sink and returning this interface.
 * Use the sink for replies, server requests, and unsolicited notifications, including asynchronous analysis results.
 * The TypeScript host validates JSON-RPC envelopes in both directions; method payloads belong to the server.
 */
trait JsonRpcBackend extends js.Object {
  /**
   * Receive one decoded JSON-RPC envelope. Invocations start in arrival order and may overlap asynchronously.
   * The server owns document ordering and cancellation. Replies go to the factory's outbound sink.
   * Throw/reject for a processing failure: requests receive InternalError; notifications/responses fail the connection.
   */
  def receive(message: js.Object): Unit | js.Promise[Unit]

  /** Optional resource cleanup on explicit host disposal. Worker termination stops the whole execution context. */
  val dispose: js.UndefOr[js.Function0[Unit]] = js.undefined
}

/** Adapt servers whose existing public API accepts and returns complete JSON strings. */
object JsonRpcBackend {
  /**
   * Translate decoded objects to a text server and emit its response batch in order.
   *
   * @param receive Accept one JSON message, without Content-Length framing, and return complete JSON messages.
   *                An empty sequence is valid for notifications. Calls may overlap; the server owns ordering.
   * @param emit Sink supplied by serveJsonRpc. Parsed objects are validated at that TypeScript boundary.
   * @param close Optional cleanup for subscriptions owned by the text server.
   * @return A backend that rejects on synchronous exceptions, failed futures, or malformed response JSON.
   *         For push-based servers, implement JsonRpcBackend directly and retain the factory's outbound sink.
   */
  def fromJson(
    receive: String => Future[Seq[String]],
    emit: js.Function1[js.Object, Unit],
    close: () => Unit = () => ()
  )(using ExecutionContext): JsonRpcBackend = {
    val handle = receive
    new JsonRpcBackend {
      override val dispose: js.UndefOr[js.Function0[Unit]] = () => close()

      override def receive(message: js.Object): js.Promise[Unit] = {
        Future.fromTry(Try(handle(js.JSON.stringify(message)))).flatten.map { responses =>
          responses.foreach(response => emit(js.JSON.parse(response).asInstanceOf[js.Object]))
        }.toJSPromise
      }
    }
  }
}
