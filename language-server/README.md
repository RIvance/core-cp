# CP language server

The server is written in Scala and builds for the JVM and Scala.js. Both targets
run the same document store, JSON-RPC session, diagnostics, and semantic
completion code. Only their transports differ: JVM stdio uses Content-Length
framing; the browser build receives and sends objects in a dedicated worker.
The browser needs no backend service.

The visualizer is a client of this server. The server has no dependency on the
visualizer, playground, editor library, or FiTrie.

## Build and run

From the repository root, with Java 21 and sbt installed:

```sh
sbt languageServerJVM/assembly
java -jar language-server/target/cp-language-server.jar --stdio
```

Configure a desktop editor to run that Java command. The executable reserves
stdout for LSP messages and sends transport failures to stderr. It processes
messages in arrival order and exits after the LSP shutdown/exit sequence,
without waiting for the client to close stdin.

For the browser build:

```sh
sbt languageServer/fullLinkJS
```

The generated ES module is available through the npm package entry
`@core-cp/language-server/browser`. Its `startBrowserLanguageServer` export starts
the session in the current dedicated worker and returns a function that removes
its message listener. The client owns the worker. The package manifest points
directly to Scala.js output; there are no JavaScript or TypeScript server
sources or runtime npm dependencies.

## Compiler boundary

`CpBackend` is the only adapter to CP. It calls the public `Cp.check` and
`Cp.analyze` APIs with immutable source snapshots. The compiler returns
diagnostic messages, source offsets, and completion candidates. Internal error
variants, syntax trees, typing contexts, elaborated terms, and runtime
representations stay inside the compiler.

The LSP layer translates offsets to UTF-16 positions, filters completion labels
by the typed prefix, and constructs replacement edits. It does not reconstruct
scope or infer types. Compiler implementation changes that preserve this API
need no corresponding server changes.

The compiler owns diagnostic descriptions, so the visualizer's compilation API
and the language server can report the same errors without sharing their
transport or UI code.

## Source workspace

The client synchronizes every required CP source file, including imported files
whose editor tabs are closed. The server does not discover files on disk. It
accepts `didOpen`, full-document `didChange`, and `didClose`. Closing a document
removes it from the source workspace and rechecks its dependents. Older versions
cannot replace accepted text, and malformed change batches leave the document
intact.

URI identity is preserved in responses. Source filenames still determine
implicit CP module names; folders do not introduce namespaces. Explicit module
declarations and imports use the normal compiler rules.

Compilation never evaluates code. Diagnostics include document versions, and
successful rechecks clear earlier errors. The compiler currently stops at its
first compilation error. An issue with no source span is sent as a
`window/logMessage` notification.

## Completion

Completion uses the compiler's lexical bindings, import resolution and inferred
receiver types. For example, at the end of:

```cp
def result(record: { field: Int; file: String }) = record.fi
```

the server proposes `field: Int` and `file: String`. Selecting one replaces the
entire existing name, including any suffix after the cursor. A surrounding
expected type does not remove candidates: the selected name may begin a longer
expression.

Parameters, inferred let bindings, fields introduced by `open`, and module
imports follow ordinary CP shadowing and ambiguity rules. Qualified names only
expose authorized modules. Intersected record fields use CP's projection types;
recursive records require explicit `unfold`. Type-name completion includes
in-scope type parameters and established signatures.

Analysis retains facts established before an elaboration error, including the
unknown name being completed. It currently requires a syntactically complete
module: `record.fi` can be queried, while `record.` and unfinished expressions
cannot. Names whose types have not been established are omitted. Hover,
navigation, rename, formatting, and symbol queries are not advertised.

## Tests

```sh
sbt ";languageServerJVM/test;languageServer/test"
```

The shared suite runs on both targets. Additional Scala tests exercise the
actual JVM process and the Scala.js worker adapter, including framing, UTF-8,
UTF-16 coordinates, lifecycle errors, source versions, imports, diagnostic
clearing, and semantic completion. None require the visualizer or playground.
