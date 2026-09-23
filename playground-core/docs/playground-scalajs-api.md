# Optional Scala.js integration API

The `playground.scalajs` package lets a Scala.js compiler implement the playground's execution worker API and connect an existing message server. It supplies typed JavaScript objects for execution input, output, diagnostics, and artifacts. The compiler binding owns language semantics, library discovery, entry-point validation, and diagnostic projection.

Dependency direction:

```text
language compiler binding → playground.scalajs → Scala.js standard library
language TypeScript worker → neutral worker host → playground contracts
playground editor/workbench → playground contracts
```

The Scala.js library has no dependency on a language implementation, LSP, Monaco, or the UI. The shared playground has no dependency on this library or on a Scala.js-generated module. A JavaScript, Wasm, remote, or native-process adapter can implement the [neutral contracts](playground-language-integration.md) independently. Execution and message-server integration are separate; either may be used alone.

## Build dependency

[scalajs-api/build.sbt](../scalajs-api/build.sbt) is a standalone sbt project. Its production API depends only on the Scala.js standard library. A Scala.js compiler build can add a source dependency:

```scala
lazy val browserCompiler = rootProject
  .enablePlugins(ScalaJSPlugin)
  .dependsOn(RootProject(file(sys.props("playground.scalajsApi"))))
```

Pass `-Dplayground.scalajsApi=/absolute/path/to/scalajs-api` to sbt, pointing to this API project in your checkout. The consuming build owns how that dependency is located. The checked-in build uses Scala 3.8.4 and Scala.js 1.22.0; the consuming build must use compatible versions. The library installs no application entry point and exports no global JavaScript names. Each language binding owns its module exports and matching TypeScript declarations.

## Execution definitions

These declarations describe [Execution.scala](../scalajs-api/src/main/scala/playground/scalajs/Execution.scala). All data objects cross the JavaScript boundary directly. Optional fields use `js.undefined`; arrays contain JavaScript objects, not Scala collections or case classes. The classes use [Scala.js-defined JavaScript types](https://www.scala-js.org/doc/interoperability/sjs-defined-js-classes.html), whose public values are JavaScript fields.

```scala
package playground.scalajs

import scala.scalajs.js

trait SourceDocument extends js.Object {
  val uri: String
  val languageId: String
  val text: String
  val version: Int
}

trait ExecutionInput extends js.Object {
  val documents: js.Array[SourceDocument]
  val entryDocumentUri: String
  val entryPoint: String
  val stdin: String
}

type DiagnosticSeverity = "error" | "warning" | "info" | "hint"
type ExecutionStatus = "success" | "error"
type OutputChannel = "stdout" | "stderr"

final class Position(val line: Int, val character: Int) extends js.Object
final class Range(val start: Position, val end: Position) extends js.Object
final class Location(val uri: String, val range: Range) extends js.Object

final class Diagnostic(
  val message: String,
  val severity: DiagnosticSeverity,
  val location: js.UndefOr[Location] = js.undefined,
  val source: js.UndefOr[String] = js.undefined,
  val code: js.UndefOr[String | Double] = js.undefined,
  val data: js.UndefOr[js.Any] = js.undefined
) extends js.Object

final class Artifact(val name: String, val mediaType: String, val content: String) extends js.Object
final class OutputChunk(val channel: OutputChannel, val text: String) extends js.Object

final class ExecutionResult(
  val status: ExecutionStatus,
  val diagnostics: js.Array[Diagnostic] = js.Array(),
  val artifacts: js.Array[Artifact] = js.Array(),
  val value: js.UndefOr[String] = js.undefined
) extends js.Object

trait ExecutionBackend extends js.Object {
  def execute(
    input: ExecutionInput, emit: js.Function1[OutputChunk, Unit]
  ): ExecutionResult | js.Promise[ExecutionResult]
}
```

`ExecutionBackend.execute` receives the complete submitted workspace snapshot. URIs are unique and `entryDocumentUri` must identify one of them. Treat the input arrays and objects as immutable. Language-specific module identity and entry-point interpretation belong to the binding.

| Value                    | Contract                                                                                  |
| ------------------------ | ----------------------------------------------------------------------------------------- |
| `SourceDocument.version` | Integer revision for the supplied text; increases within one open document lifetime.      |
| `Position`               | Nonnegative, zero-based line and UTF-16 code-unit offset. Tabs count as one unit.         |
| `Range`                  | Half-open interval; end must not precede start.                                           |
| `Diagnostic.location`    | Exact source site, or absent when unavailable. Do not substitute a guessed location.      |
| `Diagnostic.code`        | Provider-owned string or numeric identifier; preserve its type.                           |
| `Diagnostic.data`        | Optional provider context containing only structured-cloneable JavaScript values.         |
| `Artifact`               | Named text payload and MIME type. Binary artifacts are not part of this contract.         |
| `OutputChunk`            | Ordered stdout/stderr fragment. It need not be a complete line. Empty chunks are ignored. |
| `ExecutionResult.status` | `error` for expected source/program failures; `success` for a completed successful run.   |
| `ExecutionResult.value`  | Optional rendered result; absence does not imply failure.                                 |

Return a result directly for synchronous compilers or a `js.Promise` for asynchronous ones. A Scala `Future` can be converted with `scala.scalajs.js.JSConverters.toJSPromise`. Throw or reject for infrastructure failures. Emit output before the result settles; retained output callbacks have no effect after settlement or host disposal.

Export a factory returning `ExecutionBackend` from the language module. Its configuration arguments are language-owned. In the TypeScript declaration for that module, use the neutral `ExecutionBackend` type from [worker-host.ts](../src/transport/worker-host.ts). A worker entry point then binds the export:

```ts
import { createRuntime } from './generated/main.js';
import { serveExecution } from '@language-playground/ide/transport';

serveExecution(self, createRuntime());
```

The language binding chooses its export names; `createRuntime` is an example. The owner uses `WorkerRuntime` to create a fresh worker for each run. Stop, timeout, failure, and successful completion terminate that worker. Run the compiler inside that worker; terminating its context interrupts synchronous code.

## Message-server definitions

[JsonRpcBackend.scala](../scalajs-api/src/main/scala/playground/scalajs/JsonRpcBackend.scala) defines a separate API for in-process JSON-RPC servers:

```scala
package playground.scalajs

import scala.concurrent.{ExecutionContext, Future}
import scala.scalajs.js

trait JsonRpcBackend extends js.Object {
  def receive(message: js.Object): Unit | js.Promise[Unit]
  val dispose: js.UndefOr[js.Function0[Unit]] = js.undefined
}

object JsonRpcBackend {
  def fromJson(
    receive: String => Future[Seq[String]],
    emit: js.Function1[js.Object, Unit],
    close: () => Unit = () => ()
  )(using ExecutionContext): JsonRpcBackend
}
```

A server factory accepts an outbound sink, `js.Function1[js.Object, Unit]`, and returns `JsonRpcBackend`. Call the sink with replies, server requests, and unsolicited notifications. The server can retain it for asynchronous analysis results. `receive` accepts requests, notifications, and responses to server requests. Its return value represents completion of message handling; it does not contain a reply.

The TypeScript host starts `receive` calls in arrival order without waiting for earlier promises. The backend must preserve its own document-state ordering. This allows a later cancellation notification to reach an asynchronously pending operation. A synchronous compiler still occupies the worker until it returns; termination is the only way to interrupt it from another thread.

The worker accepts one decoded JSON-RPC envelope per message, without stdio framing or batch arrays. Request identifiers are strings or safe integers. Omitted `params` is valid under [JSON-RPC 2.0](https://www.jsonrpc.org/specification#request_object). Method payload interpretation and reply correlation belong to the server.

Use `fromJson` when an existing server accepts a JSON string and returns a future response batch. It serializes the incoming object once, parses each output string, and emits outputs in batch order. An empty batch is valid. Synchronous exceptions, failed futures, invalid JSON, and sink exceptions reject the returned promise. This helper does not add cancellation, LSP capabilities, source analysis, or framing. A push-based server should implement `JsonRpcBackend` directly and retain the factory's sink.

In the generated module's TypeScript declaration, expose the factory as a function accepting `(message: RpcMessage) => void` and returning the neutral `JsonRpcBackend`. The worker entry point is:

```ts
import { createLanguageServer } from './generated/main.js';
import { serveJsonRpc } from '@language-playground/ide/transport';

serveJsonRpc(self, (emit) => createLanguageServer(emit));
```

When the server speaks LSP, the main-thread adapter can use `LspLanguageService` with a `MessageConnection` to this worker. Available editor features come from the server's actual initialization result. Leave unsupported providers absent.

## Validation and lifetime

The neutral host functions and their complete TypeScript signatures are included in the [language integration API](playground-language-integration.md#api-definitions). Their behavior is independent of whether a backend was compiled by Scala.js.

| Condition                                                           | Host behavior                                                                         |
| ------------------------------------------------------------------- | ------------------------------------------------------------------------------------- |
| Invalid execution input                                             | Reply with `InvalidParams` (`-32602`) without calling the compiler.                   |
| Unknown execution method                                            | Reply with `MethodNotFound` (`-32601`).                                               |
| Another execution is active                                         | Reject the overlapping request with server error `-32000`.                            |
| Invalid execution output/result, thrown exception, rejected promise | Reply with `InternalError` (`-32603`).                                                |
| JSON-RPC backend request fails                                      | Reply with `InternalError`; a TypeScript `RpcError` retains its code and data.        |
| JSON-RPC backend notification/response handling fails               | Report a terminal worker error; no notification reply is fabricated.                  |
| Malformed envelope or failed message delivery                       | Detach the host and report a terminal worker error.                                   |
| Host is disposed                                                    | Remove its listener, release the message backend once, and suppress subsequent sends. |
| Worker is terminated                                                | Stop the entire execution context, including synchronous computation.                 |

The disposer returned by `serveExecution` or `serveJsonRpc` is idempotent. It cannot stop code already executing inside a worker. Explicit disposal of a message host invokes `JsonRpcBackend.dispose` when supplied. Worker termination does not promise a cleanup callback; resources outside the worker must be owned and released by the main-thread adapter.

## Verification

Run the Scala.js API tests from the project root:

```sh
cd scalajs-api
sbt test
```

The library tests check ordinary JavaScript inputs, result field visibility, optional fields, message ordering, asynchronous dispatch, and error propagation. The frontend tests exercise the host with a plain TypeScript backend. Each language integration owns tests of its compiled binding through these contracts.
