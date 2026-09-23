import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import { Notifications, createNotificationsStore, notifications } from '@mantine/notifications';
import type { LanguagePlugin } from './core/contracts';
import type { EditorLanguage } from './editor/monaco';
import { defaultSettings, appendCheckpoint, sessionFromExample } from './workbench/session';
import { SessionStore, sessionFromFragment } from './persistence/session-store';
import { WorkbenchController } from './workbench/controller';
import { errorMessage } from './core/controller';
import { RenderBoundary } from './components/RenderBoundary';
import { Workbench } from './workbench/Workbench';
import { ThemeCatalog } from './appearance/catalog';
import { ThemeProvider } from './appearance/ThemeProvider';
import { setTheme } from './appearance/page-theme';
import type { PlaygroundTheme } from './appearance/themes';

/** Dependencies selected by the host application. The IDE contains no default language or source examples. */
export interface PlaygroundOptions {
  /** Defines the language, examples, and factories for independently owned runtime and language-service instances. */
  readonly plugin: LanguagePlugin;
  /** Header title. Defaults to the playground wordmark; does not change the browser document title. */
  readonly title?: string;
  /** Browser tab title while mounted. Omit to retain the host page title. Restored on disposal if unchanged. */
  readonly documentTitle?: string;
  /** Installs lexical editor behavior. Semantic providers come from plugin.createLanguageService. */
  readonly editor: EditorLanguage;
  /**
   * Additional editor/workbench palettes, appended after the built-in themes in the supplied order.
   * Definitions are validated and copied before mounting. IDs must be unique across built-in and additional themes;
   * use a new ID to derive a palette from a built-in theme. Themes are host presentation settings, not language APIs.
   * Invalid definitions or duplicate IDs throw TypeError before any workspace, DOM, or plugin state changes.
   */
  readonly themes?: readonly PlaygroundTheme[];
  /**
   * Initial theme ID when no preference is saved, and fallback when a saved theme is no longer available.
   * Must identify a built-in or supplied theme; an unknown ID throws RangeError before mounting.
   * Defaults to the first theme returned by getBuiltinThemes(). A valid saved preference takes precedence.
   * Missing saved themes produce a warning and reset only the theme; other settings are retained.
   */
  readonly defaultTheme?: string;
  /**
   * Synchronous session storage. Defaults to window.localStorage, accessed only during mounting and saving.
   * Keys for source/history include plugin.definition.id; editor settings use a shared playground namespace.
   * Storage exceptions are shown in the UI and do not discard the in-memory workspace.
   */
  readonly storage?: Pick<Storage, 'getItem' | 'setItem'>;
}

/** Owns a mounted IDE and the resources created through its plugin factories. */
export interface PlaygroundHandle {
  /**
   * Save the current workspace, stop execution, disconnect the language service, and remove the IDE DOM,
   * editor models, providers, timers, notifications, and browser listeners. Repeated calls have no effect.
   * Saved browser data is retained. Cleanup attempts every step and throws AggregateError if a step fails.
   * Call before removing the host container or mounting another IDE in this window.
   */
  dispose(): void;
}

// The full application owns window shortcuts, share fragments, and persisted workspace identities.
// Embedded editors have their own namespaces and can coexist with it.
let activeMount: PlaygroundHandle | undefined;

/**
 * Mount the complete IDE into an empty, connected element of the current browser document.
 * Import @language-playground/ide/styles.css once and give the container an explicit height.
 * The IDE fills that container; dialogs may overlay the viewport. One mount is supported per window.
 *
 * Startup opens a share fragment, saved source, the first configured example, or a blank file, in that order.
 * A language mismatch reports a warning and opens the initial example or blank file. Successfully imported
 * fragments are removed from the URL. Workspace keyboard shortcuts apply to the window while mounted.
 * Source/history are saved on edits, pagehide, beforeunload, and disposal. Language-service startup is
 * asynchronous; service and storage failures are reported in the UI. Missing language capabilities stay absent.
 *
 * @throws Error if the container is detached, nonempty, belongs to another document, or an IDE is already mounted.
 * Synchronous setup failures release resources before propagating. Importing this module does not mount an IDE.
 */
export function mountPlayground(container: HTMLElement, options: PlaygroundOptions): PlaygroundHandle {
  if (activeMount) throw new Error('Dispose the mounted playground before mounting another IDE.');
  if (container.ownerDocument !== document || !container.isConnected || container.hasChildNodes())
    throw new Error('The playground container must be empty and connected to the current document.');
  const warnings: string[] = [];
  function recover<T>(read: () => T, fallback: T, description: string): T {
    try {
      return read();
    } catch (error) {
      warnings.push(`${description}: ${errorMessage(error)}`);
      return fallback;
    }
  }
  const { plugin, editor } = options;
  const themes = new ThemeCatalog(options.themes, options.defaultTheme);
  const definition = plugin.definition;
  const initial = definition.examples[0];
  const defaultSession = sessionFromExample(definition, initial);
  // Access to localStorage can itself fail in restricted browser contexts; failures remain visible to the user.
  const repository = new SessionStore(
    options.storage ?? {
      getItem: (key) => window.localStorage.getItem(key),
      setItem: (key, value) => window.localStorage.setItem(key, value),
    },
    definition.id,
  );
  const stored = recover(() => repository.load(), undefined, 'Could not restore saved source');
  const shared = recover(() => sessionFromFragment(window.location.hash), undefined, 'Could not open shared source');
  let session = shared ?? stored ?? defaultSession;
  if (session.languageId !== definition.id) {
    warnings.push(
      `The saved project requires the “${session.languageId}” adapter. The ${definition.name} example is open.`,
    );
    session = defaultSession;
  }
  const initialSettings = { ...defaultSettings, theme: themes.defaultTheme.id };
  let settings = recover(() => repository.settings(initialSettings), initialSettings, 'Could not restore settings');
  if (!themes.find(settings.theme)) {
    warnings.push(`The saved color theme “${settings.theme}” is unavailable. Using ${themes.defaultTheme.label}.`);
    settings = { ...settings, theme: themes.defaultTheme.id };
  }
  let history = recover(() => repository.history(), [], 'Could not restore history');
  if (shared && stored) {
    history = appendCheckpoint(history, stored);
    recover(() => repository.saveHistory(history), undefined, 'Could not persist the previous source checkpoint');
  }
  // Consume the imported snapshot once so reloading restores subsequent local edits.
  if (shared && shared.languageId === definition.id) {
    const url = new URL(window.location.href);
    url.hash = '';
    window.history.replaceState(null, '', url);
  }
  const controller = new WorkbenchController(plugin, repository, session, settings, history, themes);

  const host = document.createElement('div');
  host.className = 'language-playground';
  host.dataset.playgroundId = crypto.randomUUID();
  container.appendChild(host);
  const previousTitle = document.title;
  if (options.documentTitle !== undefined) document.title = options.documentTitle;
  setTheme(themes.get(settings.theme));
  const root = createRoot(host, { identifierPrefix: host.dataset.playgroundId });
  const notificationStore = createNotificationsStore();
  const save = () => controller.save();
  const beforeUnload = (event: BeforeUnloadEvent) => {
    controller.save();
    if (controller.getSnapshot().persistence === 'failed') {
      event.preventDefault();
      event.returnValue = '';
    }
  };
  let disposed = false;
  const handle: PlaygroundHandle = {
    dispose() {
      if (disposed) return;
      disposed = true;
      window.removeEventListener('pagehide', save);
      window.removeEventListener('beforeunload', beforeUnload);
      const errors: unknown[] = [];
      for (const release of [
        () => root.unmount(),
        () => controller.dispose(),
        () => notifications.clean(notificationStore),
      ]) {
        try {
          release();
        } catch (error) {
          errors.push(error);
        }
      }
      host.remove();
      if (options.documentTitle !== undefined && document.title === options.documentTitle)
        document.title = previousTitle;
      activeMount = undefined;
      if (errors.length) throw new AggregateError(errors, 'Could not release all playground resources.');
    },
  };
  activeMount = handle;
  try {
    window.addEventListener('pagehide', save);
    window.addEventListener('beforeunload', beforeUnload);
    controller.save();
    void controller.connect();
    root.render(
      <StrictMode>
        <ThemeProvider host={host}>
          <Notifications position="top-right" limit={3} store={notificationStore} />
          <RenderBoundary>
            <Workbench
              controller={controller}
              editorLanguage={editor}
              startupWarnings={warnings}
              notificationStore={notificationStore}
              title={options.title}
              host={host}
            />
          </RenderBoundary>
        </ThemeProvider>
      </StrictMode>,
    );
  } catch (error) {
    try {
      handle.dispose();
    } catch (cleanupError) {
      throw new AggregateError([error, cleanupError], 'Playground setup and cleanup failed.', { cause: cleanupError });
    }
    throw error;
  }
  return handle;
}
