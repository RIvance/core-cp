# CP language server

This subproject exposes the existing CP compiler through LSP. It has no
playground, editor, or web-demo dependency. Any LSP client can use the stdio
server; browser clients can start the same server in a dedicated worker.

The Scala.js `coreJS` project compiles the shared CP sources. This subproject
depends on that compiler and adds source analysis and diagnostics. The server
uses the compiler's module identities, import resolution, source spans, and
type checker. It does not evaluate programs or change the CP core.

## Build and run

With Java 21, sbt, and Node.js 24 installed, run from this directory:

```sh
npm ci
npm run build
npm start
```

`npm start` uses LSP framing on standard input and standard output. To configure
an editor, invoke `node dist/main/typescript/node.js --stdio`. Standard output
is reserved for the protocol.

For a browser worker, use the package's public entry point:

```ts
import { startBrowserLanguageServer } from "@core-cp/language-server/browser";
startBrowserLanguageServer();
```

The client owns the worker. Terminating it stops analysis and releases its
workspace. The server does not require a particular client or worker library.

## Source workspace

The client synchronizes every CP source file needed for compilation, including
imports whose editor tabs are closed. The server does not read files from disk.
It accepts `didOpen`, full-document `didChange`, and `didClose`, and uses document
URIs to preserve file identity. Closing a document removes it from the source
workspace and rechecks its dependents.

For example, a client can synchronize `Application.cp` and `lib/Library.cp`:

```cp
// Application.cp
import Library::*
def main: Int = answer
```

```cp
// lib/Library.cp
def answer: Int = 42
```

Changing `answer` to `1 ,, 2` produces a type error located in `Library.cp`.
Correcting it replaces that file's diagnostics with an empty list. Removing the
library reports the unresolved import. Folder names do not change CP namespaces;
explicit `module` declarations work as usual.

## Supported protocol features

The server advertises full-document synchronization and UTF-16 positions. It
publishes versioned parse and type diagnostics and clears obsolete diagnostics
after changes or closures. Older document versions cannot overwrite accepted
source. Compilation checks the workspace but never evaluates it, so a well-typed
recursive program need not terminate for analysis to finish.

The compiler currently stops at its first error. Errors with source spans become
LSP diagnostics. Module-level errors without spans use `window/logMessage`;
the server does not assign them an invented token range.

Completion, hover, definitions, references, rename, formatting, and symbol
queries are not advertised. Adding one requires a corresponding compiler-backed
analysis operation; lexical guesses are not substitutes for type information.

## Tests

```sh
npm test
```

The tests launch the actual stdio process and communicate through LSP. They
cover initialization, imports, Unicode ranges, source versions, closing files,
diagnostic clearing, and shutdown. They require no web server or IDE package.
