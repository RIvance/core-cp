# Core CP Web Demo

A browser playground for [Core CP](../README.md). Write CP programs, see their
results, and explore evaluation through an interactive graph. The app is called
**FiTrie Observatory** in the interface.

- CP source editing with syntax highlighting and compiler diagnostics.
- Built-in examples covering arithmetic, recursion, records, and lazy evaluation.
- An elaborated Fᵢᵒᵇˢ term and its evaluated result alongside the source.
- A FiTrie runtime graph with stepping, change highlighting, and node inspection.

Compilation and evaluation run in the browser using the project's Scala.js
build.

## Run locally

Install **Java 21**, **sbt**, and **Node.js 24 with npm**. From the repository
root:

```sh
cd web-demo/web
npm install
npm run dev
```

The development command builds the Scala.js compiler and starts Vite. Open the
local URL printed in the terminal.

## Use the playground

Choose a built-in example or enter a complete CP program:

```cp
def square(value: Int): Int = value * value

def main: Int = square(6) + 6
```

Select **Compile**. This program produces `42` in the result panel and loads
its initial evaluation graph. The workspace has four parts:

| Area | What it shows |
| --- | --- |
| Source editor | The CP program being compiled. |
| Compiler output | The elaborated Fᵢᵒᵇˢ term for `main`, or a compilation diagnostic. |
| Result | The value computed by the direct Fᵢᵒᵇˢ evaluator, or an evaluation error. |
| Runtime graph | The current FiTrie state, which advances when you select **Step**. |

The result is computed when you compile. You can then step through the graph
at your own pace. Changed nodes and edges are highlighted after each step.
When evaluation reaches normal form, no further reductions remain and the
**Step** button is disabled.

Selecting a node opens an inspector with its full notation. Drag nodes to
rearrange them, drag the background to pan, and scroll to zoom. Use **Fit** to
bring the graph into view or **Layout** to arrange it again.

Editing the source or choosing another example clears the previous result and
graph. Compile again to start a new evaluation.

### Controls

| Action | Control | Shortcut |
| --- | --- | --- |
| Compile the editor contents | **Compile** | `Ctrl+Enter` / `Cmd+Enter` while editing |
| Advance evaluation | **Step** | `F10` |
| Return to the initial compiled graph | **Restart** | — |
| Fit the graph in the viewport | Fit icon | — |
| Recompute node positions | Layout icon | — |
| Inspect a node | Click the node | — |

The step counter counts individual reductions. A click can advance it by more
than one because reductions that leave the displayed graph unchanged are
grouped together. **Restart** returns to the first displayed state of the
compiled program.

### Supported programs

The editor represents one file named `Main.cp`. Programs must define `main`;
an explicit `module` declaration can choose a different namespace. Imports of
other files are currently unavailable in the playground. The Scala compiler
API supports multi-module programs.

Compilation and direct evaluation run synchronously. A long-running or
nonterminating program can make the page unresponsive.

For syntax and more examples, see the [Core CP language tour](../README.md#language-tour).

## Development

Run the following commands from `web-demo/web`:

| Command | Purpose |
| --- | --- |
| `npm run dev` | Build Scala.js and start the development server. |
| `npm run scala:fast` | Rebuild the Scala.js code for development. |
| `npm test` | Run graph, rendering, and snapshot comparison tests. |
| `npm run test:integration` | Rebuild Scala.js and test the compiler and evaluator through the browser API. |
| `npm run build` | Optimize Scala.js, check TypeScript, and build the static site. |

After changing Scala sources, run `npm run scala:fast` and reload the page.
Vite handles updates to the TypeScript interface and styles during development.

To run the JVM semantic and regression suites, use the repository root:

```sh
sbt root/Test/testFull
```

The interface uses TypeScript, Monaco Editor, and Cytoscape.js. Scala.js exposes
the shared CP compiler and evaluators to the browser; graph rendering and
change highlighting belong to the interface. Vite serves and bundles the app.

## Production build

From `web-demo/web`:

```sh
npm run build
npx vite preview
```

The build writes the static site to `web-demo/web/dist`. The preview command
serves that output locally. To build with relative asset URLs for hosting under
a repository subpath, use:

```sh
npm run build -- --base=./
```

### GitHub Pages

The repository includes a **Web Demo CI and GitHub Pages** workflow. It runs
the frontend and Scala.js integration tests and builds the site on pushes and
pull requests. Successful builds on the default branch deploy to GitHub Pages.

To enable deployment:

1. In the repository's **Settings → Pages → Build and deployment**, choose
   **GitHub Actions** as the source. See the
   [GitHub Pages setup guide](https://docs.github.com/en/pages/getting-started-with-github-pages/configuring-a-publishing-source-for-your-github-pages-site).
2. Push to the default branch, or run **Web Demo CI and GitHub Pages** manually
   from the **Actions** tab with the default branch selected.
3. Open the site URL published by the deployment in the `github-pages` environment.

The workflow uses the relative-URL build shown above. You can preview the same
output locally with `npx vite preview` after that build.
