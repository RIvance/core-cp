import { createRoot } from 'react-dom/client';
import { ThemeProvider } from '../appearance/ThemeProvider';
import { RenderBoundary } from '../components/RenderBoundary';
import type { EditorLanguage } from '../editor/monaco';
import { EmbeddedController } from './controller';
import { EmbeddedView } from './EmbeddedView';
import type { EmbeddedPlaygroundHandle, EmbeddedPlaygroundOptions } from './api';

const plainText: EditorLanguage = {
  install(editor, language) {
    editor.languages.register({ id: language.id });
    return { dispose() {} };
  },
};

/**
 * Create an embedded playground in an empty, connected element of the current document.
 * Import @language-playground/ide/styles.css once and give the container an explicit height.
 * The returned handle is usable immediately for content, settings, subscriptions, and execution.
 * Await handle.ready only for native Monaco access. Lexical syntax defaults to plain text.
 *
 * Files/tabs are fixed for this instance's lifetime. Users cannot create, delete, close, import, or export files.
 * No storage, URL handling, page-level keyboard listeners, or workbench command palette is installed.
 * Ctrl/Meta+Enter runs and Shift+Escape stops while focus is inside this instance.
 * Multiple instances own separate services, models, undo stacks, selections, settings, and runs. Color themes
 * apply page-wide, as in Monaco. Omitted theme inherits the current page palette.
 *
 * @throws Error for a nonempty/detached/foreign-document container. Invalid options throw before mounting.
 * Dispose before removing the container. A synchronous setup failure attempts all cleanup before propagating.
 */
export function createEmbeddedPlayground(
  container: HTMLElement,
  options: EmbeddedPlaygroundOptions,
): EmbeddedPlaygroundHandle {
  if (container.ownerDocument !== document || !container.isConnected || container.hasChildNodes())
    throw new Error('The playground container must be empty and connected to the current document.');
  const controller = new EmbeddedController(options);
  const host = document.createElement('div');
  host.className = 'language-playground embedded-host';
  host.dataset.playgroundId = crypto.randomUUID();
  container.appendChild(host);
  const root = createRoot(host, { identifierPrefix: host.dataset.playgroundId });
  controller.onDidDispose(() => {
    try {
      root.unmount();
    } finally {
      host.remove();
    }
  });
  try {
    root.render(
      <ThemeProvider host={host}>
        <RenderBoundary onError={controller.renderingFailed}>
          <EmbeddedView controller={controller} language={options.editor ?? plainText} />
        </RenderBoundary>
      </ThemeProvider>,
    );
    void controller.restartLanguageService();
  } catch (error) {
    try {
      controller.dispose();
    } catch (cleanupError) {
      throw new AggregateError([error, cleanupError], 'Embedded playground setup and cleanup failed.', {
        cause: cleanupError,
      });
    }
    throw error;
  }
  return controller;
}
