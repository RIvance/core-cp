# Language Playground IDE

`@language-playground/ide` is a browser IDE library built with TypeScript, React, Mantine, and Monaco. It provides a full application and independent embedded editors built on a shared editor/execution core, with neutral language contracts and optional worker/LSP integrations. The host application supplies the language, runtime, lexical editor setup, and examples. This directory is the complete reusable project: source, public APIs, documentation, lockfile, tests, and build tools. It can be maintained as its own Git repository.

## Use in an application

The package ships ESM modules, TypeScript declarations, styles, and the Monaco editor worker. React and React DOM 19.2 are peer dependencies. Use an ESM bundler with module-worker and asset-URL support; Vite is tested. Serve the output over HTTP(S) in a browser with ES2022 and module workers.

```ts
import { mountPlayground } from '@language-playground/ide';
import type { LanguagePlugin, EditorLanguage } from '@language-playground/ide/api';
import '@language-playground/ide/styles.css';

export function openEditor(container: HTMLElement, plugin: LanguagePlugin, editor: EditorLanguage) {
  return mountPlayground(container, { plugin, editor });
}
```

Give the empty host element an explicit height. The IDE fills the element. Keep the returned handle and call `dispose()` before removing the host or replacing its language. Disposal saves source, stops running programs, disconnects the service, releases editor models/providers, and removes listeners and portals. Repeated disposal has no effect.

The full application supports one mount per window. Embedded editors support multiple independent instances. Dialogs can overlay the viewport. Workspace keyboard shortcuts are registered on the window while the IDE is mounted. The stylesheet includes Mantine and Monaco base styles. Importing the package does not mount the IDE or choose a language.

For an embedded editor, use a single create call. Optional settings have defaults; files, execution, diagnostics, events, and native Monaco access are available through its handle.

```ts
import { createEmbeddedPlayground } from '@language-playground/ide/embedded';

const editor = createEmbeddedPlayground(container, { plugin, value: source });
editor.updateOptions({ fontSize: 16, wordWrap: true });
await editor.run();
```

The embed has fixed tabs, optional read-only files, and no storage or file-management controls. It shares themes at page scope, matching Monaco; other state and lifetimes are independent. See [embedded API and defaults](docs/embedding.md). The full application's optional `title` and `documentTitle` customize its header and browser tab.

`mountPlayground` accepts optional synchronous `storage` with `getItem`/`setItem`, defaulting to browser local storage. Startup restores a share link or saved session before using the first configured example; a language mismatch reports a warning and opens the initial example. An empty example list opens a blank file. Failed storage operations are reported without losing the in-memory workspace.

| Entry point                           | Contents                                                                                          |
| ------------------------------------- | ------------------------------------------------------------------------------------------------- |
| `@language-playground/ide`            | Mount/dispose API and neutral types                                                               |
| `@language-playground/ide/embedded`   | Embedded create/handle API with fixed tabs and host-controlled options                            |
| `@language-playground/ide/core`       | Shared controller, state, settings, and React editor/result components                            |
| `@language-playground/ide/api`        | Document, diagnostic, execution, plugin, language-service, example, and editor-installation types |
| `@language-playground/ide/editor`     | Configured Monaco instance and theme installation                                                 |
| `@language-playground/ide/transport`  | JSON-RPC connection, worker runtime, worker hosts, and protocol validators                        |
| `@language-playground/ide/lsp`        | Optional LSP language-service implementation                                                      |
| `@language-playground/ide/examples`   | Validated file-based example catalogs                                                             |
| `@language-playground/ide/workspace`  | Immutable virtual workspace, path operations, and ZIP/TAR archive formats                         |
| `@language-playground/ide/themes`     | Built-in palettes and complete custom theme types                                                 |
| `@language-playground/ide/styles.css` | Complete stylesheet                                                                               |

Import through these entry points. Internal source and emitted modules are not public APIs. Implement only the language capabilities your backend supports; missing capabilities stay unavailable. The contracts do not require a particular compiler, implementation language, or transport. Doc comments in the emitted declarations specify ownership, coordinates, cancellation, and failure behavior.

## Workspace

- Virtual folders with a keyboard-accessible file explorer, nested file creation, folder creation, move/rename, and recursive deletion. Contents stay in flat records with relative paths. Closing a tab keeps the file, its undo history, and the independently selected entry file.
- Monaco editing with undo/redo, multiple cursors, search and replace, bracket matching, indentation, folding, snippets, and accessible editor commands.
- Searchable command palette, keyboard shortcuts, symbol outline and navigation, resizable panels, responsive drawers, and persistent editor settings.
- Live diagnostics, hover, definitions, references, symbols, signature help, and semantic folding when the connected service supplies them. Completion, formatting and rename providers are also implemented at the generic editor boundary.
- Optional inlay hints, semantic highlighting, and code actions/quick fixes. The shared editor supports rich hint labels, lazy resolution, provider refresh, semantic palettes, and version-checked text edits with undo. Unsupported features remain visibly unavailable.
- Explicit language-service capability reporting, failure messages, logs and restart. Unsupported commands explain why they are unavailable.
- Local execution with streamed standard output/error, standard input, entry-file and entry-point selection, stop, execution limits, optional run-after-editing, result revisions and downloadable compiler artifacts.
- Autosave, up to 20 local checkpoints, text/project import and export, ZIP/TAR/TAR.GZ archives, and compressed share links. Archives preserve paths and empty folders. Workspace replacement, moves, and deletion keep a checkpoint first. Moves create new editor models; closing and reopening a tab preserves its model.

Use **Ctrl+Enter** to run, **Shift+Escape** to stop, **Ctrl+S** to save a checkpoint, and **Ctrl+Shift+P** or **F1** to open commands. Use Command instead of Ctrl on macOS. The keyboard-help dialog lists the registered shortcuts.

Choose a color theme with the palette button at the top right, immediately left of Keyboard shortcuts. Its dropdown supports search and marks the current theme. The command palette also provides theme commands. Midnight, Daylight, Ocean, Dusk, Forest, Sand, Graphite, Porcelain, High contrast dark, and High contrast light apply to source highlighting, editor widgets, panels, menus, and dialogs. Graphite adapts VS Code Dark+; Porcelain adapts GitHub Light Default. See [theme sources](docs/theme-sources.md) for palette adjustments and attribution. Changes take effect immediately and are saved without replacing editor models or undo history. Hosts may supply additional palettes and an initial theme through `mountPlayground`; see the [theme API](docs/playground-language-integration.md#color-themes).

Source and history are stored in this browser under a language-specific namespace. Settings are shared across languages. If browser storage fails, the current source and checkpoints remain in memory and the UI reports that persistence failed. Export a `.playground.json` project to move a session between browsers. This reserved extension distinguishes full session exports from source files. ZIP, TAR, and TAR.GZ exports contain UTF-8 text files and folders; use project JSON to preserve entry selection, input, and tabs. Archives with links, binary files, invalid paths, or exceeded size limits are rejected as a whole. See the [workspace and archive contracts](docs/playground-language-integration.md#virtual-workspaces-and-archives). Storage is local to the site origin; it is not cloud synchronization.

A share link contains every workspace file and folder, open tabs, active and entry-file selections, entry point and standard input in its URL fragment. The fragment is not sent to the static host. Anyone with the link can read its contents. Opening a link imports the snapshot, preserves the previous saved session as a checkpoint, and consumes the fragment so later reloads restore new edits. Sharing does not require a backend.

Workspace limits are 50 files, 200 folders (including inferred parents), 512,000 UTF-8 bytes per source file, 2,000,000 UTF-8 bytes across the workspace, and 64,000 UTF-8 bytes of input. Source import accepts multiple files; project export and sharing preserve the entire workspace. Versions 1 and 2 of projects, saves, checkpoints, and share links migrate at the serialization boundary; current exports use version 3. Share payloads are limited to 48,000 encoded characters and bounded during decompression. Runs stop after the configured 1–60 seconds or 256,000 output characters. These limits belong to the workspace and persistence boundaries, not to the language semantics.

## Build and verify the library

Node.js 22.12 or newer and npm are sufficient. Run these commands from this directory, including after moving it into its own repository:

```sh
npm ci
npm run build
npm test
npm run lint
npm run format:check
npx playwright install chromium
npm run test:e2e
npm run test:package
npm run test:standalone
```

`check` checks TypeScript and the generated API reference. `docs:api` regenerates that reference. `build` runs those checks and produces `dist/`, including declarations and worker assets. `npm pack` rebuilds and packages that output; it does not include tests, source, or dependencies. `watch` rebuilds JavaScript and styles during host development; rerun `build` when changing exported types.

`test:package` installs an archive in a temporary directory outside the workspace. A separate consumer type-checks and builds from public imports, then exercises execution, the Monaco worker, subdirectory hosting, mount/dispose/remount, service cleanup, and storage failure. Its runtime is a test-only text adapter. The package test needs npm registry access and an installed Playwright Chromium, or `PLAYWRIGHT_CHROMIUM_EXECUTABLE_PATH` pointing to a compatible browser.

`test:e2e` covers editor providers using a separate test adapter. These tests need no language compiler. Generated bundles, dependencies, browser reports, and caches are excluded from version control.

## Project ownership

| Component                                                                           | Responsibility                                                                                                                                                 |
| ----------------------------------------------------------------------------------- | -------------------------------------------------------------------------------------------------------------------------------------------------------------- |
| [core/](src/core/index.ts)                                                          | Session/settings, revisions, language-service lifecycle, execution, and neutral contracts.                                                                     |
| [components/](src/components/PlaygroundSurface.tsx)                                 | Shared editor/tabs, Run/Stop, and result rendering.                                                                                                            |
| [embedded/](src/embedded/index.ts)                                                  | Fixed-file policy, host API, independent mounting, scoped shortcuts.                                                                                           |
| [core/contracts.ts](src/core/contracts.ts)                                          | Language-neutral documents, capabilities, execution, and plugin contracts.                                                                                     |
| [workspace/](src/workspace/model.ts)                                                | Immutable files and folders, path invariants, and atomic workspace operations. Archive codecs and example loading translate external contents into this model. |
| [workbench/](src/workbench/controller.ts)                                           | Application persistence/checkpoints, open/close workflows, commands, and the file explorer. Browser file actions own import/export dialogs.                    |
| [editor/](src/editor/workspace.ts)                                                  | Monaco models, view state, undo history, and language-service providers.                                                                                       |
| [persistence/](src/persistence/session-store.ts)                                    | Project versions, saved-session migration, browser storage, and share serialization.                                                                           |
| [transport/](src/transport/rpc.ts) and [adapters/lsp/](src/adapters/lsp/service.ts) | Optional worker and LSP integrations implementing the neutral contracts.                                                                                       |

- [docs/playground-language-integration.md](docs/playground-language-integration.md) specifies package exports and the adapter API.
- [scalajs-api/](scalajs-api/README.md) provides an optional Scala.js source API. Its [formal reference](docs/playground-scalajs-api.md) describes the typed execution and message interfaces. Test it independently with `sbt test` from that directory. The TypeScript build does not run sbt or import this API.
- [tests/](tests/controller.test.ts) contains language-neutral tests and test-only adapters, including multi-editor host pages. Concrete language applications, examples, compiler builds, and integration tests belong to their own repositories.

An application depends on this package through its public exports. This project does not import or build applications, and no command reads files from a parent project. There is no shared npm workspace with a consumer. Both the lockfile and ignore rules are local to this directory.

`test:standalone` exports this directory's tracked files to a temporary Git repository, installs with `npm ci`, and runs the build, API documentation check, unit tests, lint, formatting, editor browser tests, and installed-package tests. It verifies that source files stay unchanged and generated output is ignored. Run it from a Git checkout after staging newly added files; it does not require a commit, a language application, or a compiler toolchain. It needs the same browser and registry access as `test:package`.
