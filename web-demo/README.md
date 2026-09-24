# Core CP Web Demo

**FiTrie Observatory** is a CP playground with a workspace editor and an
interactive view of FiTrie evaluation. Compilation and evaluation run locally
in browser workers.

The editor uses the Language Playground IDE package. It provides files and
folders, tabs, source search, examples, themes, local history, project import
and export, and share links. The trie explorer remains beside the editor.

## Run locally

Install Java 21, sbt, and Node.js 24 with npm. From the repository root:

```sh
cd web-demo/web
npm run setup
npm run dev
```

Setup builds the supplied IDE package in a separate generated directory and
installs the application dependencies. It does not change
the supplied IDE checkout. Development builds the Scala.js compiler and
language server, then starts Vite.

## Run a program

Choose an example, or enter:

```cp
def square(value: Int): Int = value * value
def main: Int = square(6) + 6
```

Select **Run** or press **Ctrl+Enter** / **Cmd+Enter**. **Output** shows `42`.
**Inspector** contains the elaborated Fᵢᵒᵇˢ term, which can be copied or
downloaded. **Problems** shows compiler and evaluation errors. Compiler errors
also appear while editing, through the CP language server.

Each Run sends an immutable workspace snapshot to two separate workers:

- The direct Fᵢᵒᵇˢ evaluator computes the displayed result, including record fields.
- The FiTrie evaluator creates a session for interactive stepping.

The **Stop** button and the execution time limit interrupt direct evaluation.
They do not block editing. The time limit is available in **Workspace settings**.
The trie can be explored while direct evaluation is still running.

## Explore the trie

| Control | Action |
| --- | --- |
| **Step** or **F10** | Advance to the next visible state. |
| **Restart** | Return to the first displayed state of this run. |
| **Fit** | Fit the graph into its viewport. |
| **Layout** | Arrange the nodes again. |
| Click a node | Inspect its full notation. |
| **Stop trie** | Interrupt a pending trie operation. Run again to create a new session. |

Changed nodes and edges are highlighted. Drag nodes to arrange them, drag the
background to pan, and scroll to zoom. At normal form, **Step** is disabled.

The counter counts reductions, so one click can increase it by more than one:
reductions that produce the same displayed graph are grouped together. Each
trie operation has a 30-second deadline; an expired operation terminates its
worker and reports the failure.

The explorer retains the snapshot from the last Run. Editing source does not
change that session. Run again to compile the edited workspace. A new run
replaces the previous trie session. Editor and graph colors follow the same
theme selection.

## Work with several modules

The **Module imports** example contains two files:

```cp
// Application.cp
module Examples::Application
import Examples::Library::*
def main: Int = twice(21)
```

```cp
// lib/Library.cp
module Examples::Library
def twice(value: Int): Int = value + value
```

The entry file is `Application.cp`. Editing or opening `Library.cp` does not
change the entry. Choose a different entry file in **Workspace settings**.
The compiler receives every `.cp` file, including closed tabs. Other workspace
text files are not CP modules. Module names and imports follow ordinary CP
rules; folders do not introduce namespaces.

CP evaluates the entry module's `main` definition. Other entry names and
nonempty standard input are reported as unsupported. The playground does not
change CP's evaluation or typing rules.

## Language service

The CP language server checks the complete synchronized workspace and publishes
parse and type errors with source ranges. Errors without a source range appear
in **Language service** messages; running also reports them in **Problems**.
The compiler currently reports the first failing compilation error.

The editor also receives semantic completion for parsed identifier prefixes,
using inferred record fields, local bindings, type names, and imports. For
example, typing record.fi can suggest field from the receiver's type.
Completion after a parse failure is not yet available.

The visualizer depends on the [CP language server](../language-server/README.md)
as an LSP client. The server is pure Scala, compiled to Scala.js for its browser
worker; it needs no backend service. A separate JVM stdio build serves desktop
editors. Neither server target depends on this demo or the IDE package.

## Development and verification

Run these commands from `web-demo/web`:

| Command | Purpose |
| --- | --- |
| `npm run setup` | Build the supplied IDE package and install dependencies. |
| `npm run dev` | Build compiler/server assets and start Vite. |
| `npm run scala:fast` | Rebuild both Scala.js entry points. |
| `npm test` | Build Scala.js and test graph rendering, snapshot comparison, and workspace execution. |
| `npm run test:integration` | Build Scala.js and check compilation and both evaluators. |
| `npm run build -- --base=./` | Build a static site with relative asset URLs. |
| `npx playwright install chromium` | Install the browser used by the UI tests. |
| `npm run test:browser` | Test the built site under a deployment subdirectory. |

After changing Scala sources, rebuild with `npm run scala:fast` and reload the
page. If using a system Chromium, set `PLAYWRIGHT_CHROMIUM_EXECUTABLE` when running
the browser tests. This setting affects only the test runner.

## Deployment

`npm run build` writes the static site to `web-demo/web/dist`. Preview it with
`npx vite preview`. A build using `--base=./` can be hosted under a repository
subpath or at a domain root; workers use the same base as the page.

The **Web Demo CI and GitHub Pages** workflow tests the language server,
Scala.js API, frontend, and production browser build. Successful builds on the
default branch deploy through GitHub Pages when its source is set to
**GitHub Actions**.
