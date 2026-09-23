import { lazy, Suspense, useId, useRef, useSyncExternalStore, type ReactNode, type RefObject } from 'react';
import { ActionIcon, Badge } from '@mantine/core';
import { IconFileCode, IconLock, IconX } from '@tabler/icons-react';
import { Group as PanelGroup, Panel, Separator } from 'react-resizable-panels';
import type { PlaygroundController } from '../core/controller';
import { activeFile } from '../core/session';
import type { EditorHandle } from '../editor/workspace';
import type { EditorLanguage } from '../editor/monaco';
import { ResultPanel, type ResultPanelId } from './ResultPanel';

const EditorPane = lazy(async () => ({ default: (await import('../editor/EditorPane')).EditorPane }));
const ignorePosition = () => {};

/** Shared editor/tabs and resizable result area. File-management controls are optional host-owned slots. */
export interface PlaygroundSurfaceProps {
  /** Owns this surface's models and execution state; the host must connect and dispose it. */
  readonly controller: PlaygroundController;
  /** Instance-scoped lexical registrations. */
  readonly editorLanguage: EditorLanguage;
  /** Receives editor operations after Monaco loads; cleared when the editor unmounts. */
  readonly editorRef?: RefObject<EditorHandle | null>;
  /** Selected result tab. Must belong to panels when a restricted list is provided. */
  readonly panel: ResultPanelId;
  /** Called when the user selects a result tab. The host owns selection. */
  readonly onPanelChange: (panel: ResultPanelId) => void;
  /** Receives editor, language-provider, and input validation failures. */
  readonly onError: (error: unknown) => void;
  /** Optional cursor callback. Line/column are one-based; selected is the number of UTF-16 code units. */
  readonly onPosition?: (line: number, column: number, selected: number) => void;
  /** Called after models and editor operations are available. Each editor lifecycle invokes it once. */
  readonly onEditorReady?: (editor: EditorHandle) => void;
  /** Tab-bar controls supplied by the host. Omit to offer no file-management actions. */
  readonly tabActions?: ReactNode;
  /** Adds close buttons. Omit for a fixed set of tabs. This component never deletes files. */
  readonly onCloseFile?: (fileId: string) => void;
  /** Host content shown when no tab is open. Omitted by default. */
  readonly emptyContent?: ReactNode;
  /** Show the current path and language above the editor. Defaults to true. */
  readonly breadcrumbs?: boolean;
  /** Visible result tabs. Omit for all tabs. Must be nonempty and contain panel. */
  readonly panels?: readonly ResultPanelId[];
  /** Adds a copy-output control; clipboard ownership stays with the host. */
  readonly copy?: (text: string) => void;
  /** Adds an artifact download control; omitted by default. */
  readonly download?: (name: string, text: string, mediaType?: string) => void;
}

/** Render the shared editor and result components inside a height-constrained, themed host. */
export function PlaygroundSurface({
  controller,
  editorLanguage,
  editorRef: suppliedEditorRef,
  panel,
  onPanelChange,
  onError,
  onPosition,
  onEditorReady,
  tabActions,
  onCloseFile,
  emptyContent,
  breadcrumbs = true,
  panels,
  copy,
  download,
}: PlaygroundSurfaceProps): ReactNode {
  const internalEditorRef = useRef<EditorHandle>(null);
  const editorRef = suppliedEditorRef ?? internalEditorRef;
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot);
  const id = useId();
  const file = activeFile(state.session);
  const definition = controller.plugin.definition;
  const openFiles = state.session.openFileIds.map((fileId) => state.session.workspace.file(fileId)!);
  return (
    <PanelGroup orientation="vertical" id={`${id}-layout`}>
      <Panel id={`${id}-source`} defaultSize="68%" minSize="150px">
        <section className="editor-area" aria-label="Code workspace">
          <div className="editor-tabs">
            <div className="source-tabs" role="tablist" aria-label="Source files">
              {openFiles.map((source, index) => (
                <div
                  key={source.id}
                  className={`editor-tab ${source.id === state.session.activeFileId ? 'active' : ''}`}
                >
                  <button
                    role="tab"
                    id={`${id}-file-${source.id}`}
                    aria-controls={`${id}-editor`}
                    aria-selected={source.id === state.session.activeFileId}
                    tabIndex={source.id === state.session.activeFileId ? 0 : -1}
                    onClick={() => controller.selectFile(source.id)}
                    onKeyDown={(event) => {
                      const nextIndex =
                        event.key === 'ArrowRight'
                          ? (index + 1) % openFiles.length
                          : event.key === 'ArrowLeft'
                            ? (index - 1 + openFiles.length) % openFiles.length
                            : event.key === 'Home'
                              ? 0
                              : event.key === 'End'
                                ? openFiles.length - 1
                                : undefined;
                      const next = nextIndex === undefined ? undefined : openFiles[nextIndex];
                      if (next) {
                        event.preventDefault();
                        controller.selectFile(next.id);
                        document.getElementById(`${id}-file-${next.id}`)?.focus();
                      }
                    }}
                  >
                    <IconFileCode size={16} />
                    <span>{source.path}</span>
                    {controller.isReadOnly(source.id) && <IconLock size={13} aria-label="Read-only" />}
                    {source.id === state.session.entryFileId && (
                      <span className="entry-file-mark" title="Entry file">
                        ▶
                      </span>
                    )}
                  </button>
                  {onCloseFile && (
                    <ActionIcon
                      size="xs"
                      variant="subtle"
                      color="neutral"
                      aria-label={`Close ${source.path}`}
                      title="Close tab"
                      onClick={() => onCloseFile?.(source.id)}
                    >
                      <IconX size={13} />
                    </ActionIcon>
                  )}
                </div>
              ))}
            </div>
            {tabActions}
          </div>
          {breadcrumbs && (
            <div className="breadcrumbs">
              <span>workspace</span>
              <span>/</span>
              {file ? (
                file.path.split('/').map((segment, index, segments) => (
                  <span key={index}>
                    {index > 0 && <span className="breadcrumb-separator">/</span>}
                    {index === segments.length - 1 ? <strong>{segment}</strong> : segment}
                  </span>
                ))
              ) : (
                <strong>No files open</strong>
              )}
              <Badge size="xs" variant="outline" color="neutral">
                {definition.name}
              </Badge>
            </div>
          )}
          {!file && emptyContent}
          <Suspense
            fallback={
              <div className="empty-state" role="status">
                Loading editor…
              </div>
            }
          >
            <EditorPane
              ref={editorRef}
              language={editorLanguage}
              controller={controller}

              onError={onError}
              onPosition={onPosition ?? ignorePosition}
              id={`${id}-editor`}
              onReady={onEditorReady}
            />
          </Suspense>
        </section>
      </Panel>
      <Separator className="resize-handle horizontal" aria-label="Resize output panel" />
      <Panel id={`${id}-tools`} defaultSize="32%" minSize="130px">
        <ResultPanel
          state={state}
          controller={controller}
          view={panel}
          setView={onPanelChange}
          reveal={(location) => editorRef?.current?.reveal(location)}
          copy={copy}
          download={download}
          panels={panels}
          onError={onError}
        />
      </Panel>
    </PanelGroup>
  );
}
