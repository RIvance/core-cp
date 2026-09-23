import { useRef, useSyncExternalStore } from 'react';
import { Alert } from '@mantine/core';
import type { EditorLanguage } from '../editor/monaco';
import type { EditorHandle } from '../editor/workspace';
import { errorMessage } from '../core/controller';
import { PlaygroundSurface } from '../components/PlaygroundSurface';
import { RunButton } from '../components/RunButton';
import { getTheme, onDidChangeTheme } from '../appearance/page-theme';
import type { EmbeddedController } from './controller';

export function EmbeddedView({ controller, language }: { controller: EmbeddedController; language: EditorLanguage }) {
  const state = useSyncExternalStore(controller.core.subscribe, controller.core.getSnapshot);
  const view = useSyncExternalStore(controller.subscribeView, controller.getView);
  const theme = useSyncExternalStore(onDidChangeTheme, getTheme);
  const editor = useRef<EditorHandle>(null);
  const run = () => {
    void controller.run().catch(controller.report);
  };
  return (
    <section
      className="embedded-playground"
      aria-label={view.options.ariaLabel}
      data-theme={theme.id}
      onKeyDownCapture={(event) => {
        if ((event.ctrlKey || event.metaKey) && event.key === 'Enter' && !event.altKey && !event.shiftKey) {
          event.preventDefault();
          event.stopPropagation();
          run();
        } else if (event.key === 'Escape' && event.shiftKey && !event.altKey && !event.ctrlKey && !event.metaKey) {
          event.preventDefault();
          event.stopPropagation();
          controller.stop();
        } else if (
          event.key === 'F1' ||
          ((event.ctrlKey || event.metaKey) && event.shiftKey && event.key.toLowerCase() === 'p')
        ) {
          // This wrapper has no command palette. Monaco's other editing shortcuts remain available.
          event.preventDefault();
          event.stopPropagation();
        }
      }}
    >
      <header className="embedded-toolbar">
        <strong>{view.options.title}</strong>
        <RunButton state={state} run={run} stop={() => controller.stop()} />
      </header>
      {view.error !== undefined && (
        <Alert color="error" withCloseButton onClose={() => controller.dismissError()}>
          {errorMessage(view.error)}
        </Alert>
      )}
      {state.service.kind === 'failed' && (
        <Alert color="warning" title="Language services unavailable">
          {state.service.message}
        </Alert>
      )}
      <div className="embedded-surface">
        <PlaygroundSurface
          controller={controller.core}
          editorLanguage={language}
          editorRef={editor}
          panel={view.options.panel}
          panels={view.options.panels}
          onPanelChange={(panel) => controller.updateOptions({ panel })}
          breadcrumbs={view.options.breadcrumbs}
          onError={controller.report}
          onEditorReady={(handle) => controller.attachEditor(handle)}
        />
      </div>
      <div className="sr-only" role="status" aria-live="polite">
        {state.execution.kind === 'running'
          ? 'Program running'
          : state.execution.kind === 'finished'
            ? `Execution ${state.execution.result.status}`
            : ''}
      </div>
    </section>
  );
}
