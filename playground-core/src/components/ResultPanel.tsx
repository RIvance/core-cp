import { currentDiagnostics } from '../core/diagnostics';
import { useId, useState } from 'react';
import { ActionIcon, Badge, Button, Textarea, Tooltip } from '@mantine/core';
import { IconArrowDown, IconCircleCheck, IconCopy, IconPlayerPlay, IconTrash } from '@tabler/icons-react';
import type { Location } from '../core/contracts';
import { maximumInputBytes } from '../core/session';
import type { PlaygroundController, PlaygroundState } from '../core/controller';

/** Built-in output, diagnostic, input, artifact, and service-log views. */
export type ResultPanelId = 'output' | 'problems' | 'input' | 'inspector' | 'logs';
interface ResultPanelProps {
  state: PlaygroundState;
  controller: PlaygroundController;
  view: ResultPanelId;
  panels?: readonly ResultPanelId[];
  onError: (error: unknown) => void;
  setView: (view: ResultPanelId) => void;
  reveal: (location: Location) => void;
  copy?: (text: string) => void;
  download?: (name: string, text: string, mediaType?: string) => void;
}
/** @internal Result content rendered by PlaygroundSurface. */
export function ResultPanel({
  state,
  controller,
  view,
  setView,
  reveal,
  copy,
  download,
  panels,
  onError,
}: ResultPanelProps) {
  const id = useId();
  const [artifactIndex, setArtifactIndex] = useState(0);
  const execution = state.execution;
  const diagnostics = currentDiagnostics(state);
  const output = execution.kind === 'idle' ? '' : execution.output.map((chunk) => chunk.text).join('');
  const result = execution.kind === 'finished' ? execution.result : undefined;
  const selectedArtifactIndex = result && artifactIndex < result.artifacts.length ? artifactIndex : 0;
  const artifact = result?.artifacts[selectedArtifactIndex];
  const stale = execution.kind !== 'idle' && execution.revision !== state.revision;
  const tabs: { id: ResultPanelId; title: string; count?: number }[] = [
    { id: 'output', title: 'Output' },
    { id: 'problems', title: 'Problems', count: diagnostics.length },
    { id: 'input', title: 'Input' },
    { id: 'inspector', title: 'Inspector' },
    { id: 'logs', title: 'Language service', count: state.logs.length },
  ];
  const visibleTabs = panels ? tabs.filter((tab) => panels.includes(tab.id)) : tabs;
  return (
    <section className="tool-panel" aria-label="Program tools">
      <div className="tool-panel-header">
        <div role="tablist" aria-label="Program panels" className="panel-tabs">
          {visibleTabs.map((tab) => (
            <button
              key={tab.id}
              role="tab"
              aria-selected={view === tab.id}
              aria-controls={`${id}-panel-${tab.id}`}
              id={`${id}-tab-${tab.id}`}
              tabIndex={view === tab.id ? 0 : -1}
              onKeyDown={(event) => {
                const index = visibleTabs.findIndex((item) => item.id === view);
                const nextIndex =
                  event.key === 'ArrowRight'
                    ? (index + 1) % visibleTabs.length
                    : event.key === 'ArrowLeft'
                      ? (index - 1 + visibleTabs.length) % visibleTabs.length
                      : event.key === 'Home'
                        ? 0
                        : event.key === 'End'
                          ? visibleTabs.length - 1
                          : undefined;
                const next = nextIndex === undefined ? undefined : visibleTabs[nextIndex];
                if (next) {
                  event.preventDefault();
                  setView(next.id);
                  document.getElementById(`${id}-tab-${next.id}`)?.focus();
                }
              }}
              onClick={() => setView(tab.id)}
              className={view === tab.id ? 'active' : ''}
            >
              {tab.title}
              {!!tab.count && <span className="tab-count">{tab.count}</span>}
            </button>
          ))}
        </div>
        <div className="panel-actions">
          {stale && (
            <Badge color="warning" size="xs" variant="light">
              Previous revision
            </Badge>
          )}
          {view === 'output' && (
            <>
              {copy && (
                <Tooltip label="Copy output">
                  <ActionIcon
                    variant="subtle"
                    color="neutral"
                    aria-label="Copy output"
                    onClick={() => copy(output + (result?.value ?? ''))}
                  >
                    <IconCopy size={15} />
                  </ActionIcon>
                </Tooltip>
              )}
              <Tooltip label="Clear output">
                <ActionIcon
                  variant="subtle"
                  color="neutral"
                  aria-label="Clear output"
                  disabled={execution.kind === 'running'}
                  onClick={() => controller.clearOutput()}
                >
                  <IconTrash size={15} />
                </ActionIcon>
              </Tooltip>
            </>
          )}
          {view === 'logs' && (
            <ActionIcon
              variant="subtle"
              color="neutral"
              aria-label="Clear language service messages"
              onClick={() => controller.clearLogs()}
            >
              <IconTrash size={15} />
            </ActionIcon>
          )}
        </div>
      </div>
      <div className="panel-content" role="tabpanel" id={`${id}-panel-${view}`} aria-labelledby={`${id}-tab-${view}`}>
        {view === 'output' && (
          <>
            {execution.kind === 'idle' ? (
              <div className="output-welcome">
                <span className="output-welcome-icon">
                  <IconPlayerPlay size={21} />
                </span>
                <div>
                  <strong>No output yet.</strong>
                  <p>
                    Run your code to see results here. <kbd>Ctrl</kbd> + <kbd>Enter</kbd>
                  </p>
                </div>
              </div>
            ) : (
              <div className="console-output">
                <div className="run-heading">
                  <span
                    className={`small-dot ${execution.kind === 'finished' && result?.status === 'error' ? 'error' : ''}`}
                  />
                  <span>
                    {execution.kind === 'running'
                      ? 'Running…'
                      : execution.kind === 'stopped'
                        ? execution.reason
                        : result?.status === 'success'
                          ? 'Execution completed'
                          : 'Execution failed'}
                  </span>
                  {'duration' in execution && <time>{Math.round(execution.duration)} ms</time>}
                </div>
                {execution.output.map((chunk, index) => (
                  <pre key={index} className={chunk.channel}>
                    {chunk.text}
                  </pre>
                ))}
                {result?.value !== undefined && (
                  <div className="result-value" data-testid="execution-value">
                    <span>↳</span>
                    <pre>{result.value}</pre>
                  </div>
                )}
                {result?.status === 'error' &&
                  result.diagnostics.map((diagnostic, index) => (
                    <pre className="stderr" key={index}>
                      {diagnostic.message}
                    </pre>
                  ))}
              </div>
            )}
          </>
        )}
        {view === 'problems' && (
          <div className="diagnostics">
            {diagnostics.length ? (
              diagnostics.map((diagnostic, index) => (
                <button
                  key={index}
                  className="diagnostic"
                  disabled={!diagnostic.location}
                  onClick={() => diagnostic.location && reveal(diagnostic.location)}
                >
                  <span className={`diagnostic-severity ${diagnostic.severity}`}>
                    {diagnostic.severity === 'error' ? '×' : '!'}
                  </span>
                  <span>
                    {diagnostic.message}
                    <small>
                      {diagnostic.source}
                      {diagnostic.code && ` · ${diagnostic.code}`}
                    </small>
                  </span>
                  {diagnostic.location && (
                    <small>
                      {
                        state.session.workspace.files.find((file) =>
                          state.documents.some(
                            (document) =>
                              document.fileId === file.id && document.source.uri === diagnostic.location?.uri,
                          ),
                        )?.path
                      }
                      {' · '}
                      Ln {diagnostic.location.range.start.line + 1}, Col {diagnostic.location.range.start.character + 1}
                    </small>
                  )}
                </button>
              ))
            ) : (
              <div className="empty-state">
                <IconCircleCheck size={22} />
                <span>No reported problems</span>
                <small>Compiler messages without source positions appear in Language service.</small>
              </div>
            )}
          </div>
        )}
        {view === 'input' && (
          <div className="input-panel">
            <p className="muted">Standard input, read one line at a time when you run the program.</p>
            <Textarea
              aria-label="Standard input"
              placeholder="Enter program input…"
              value={state.session.stdin}
              onChange={(event) => {
                try {
                  controller.updateSession({ ...state.session, stdin: event.currentTarget.value });
                } catch (error) {
                  onError(error);
                }
              }}
              minRows={4}
              maxLength={maximumInputBytes}
            />
          </div>
        )}
        {view === 'inspector' && (
          <div className="inspector-panel">
            {artifact ? (
              <>
                <div className="artifact-toolbar">
                  <select
                    aria-label="Artifact"
                    value={selectedArtifactIndex}
                    onChange={(event) => setArtifactIndex(Number(event.currentTarget.value))}
                  >
                    {result?.artifacts.map((item, index) => (
                      <option value={index} key={item.name}>
                        {item.name}
                      </option>
                    ))}
                  </select>
                  {download && (
                    <Button
                      size="compact-xs"
                      variant="subtle"
                      leftSection={<IconArrowDown size={13} />}
                      onClick={() => download(`${artifact.name}.txt`, artifact.content, artifact.mediaType)}
                    >
                      Download
                    </Button>
                  )}
                </div>
                <pre>{artifact.content}</pre>
              </>
            ) : (
              <div className="empty-state">Run a program to inspect artifacts produced by its language adapter.</div>
            )}
          </div>
        )}
        {view === 'logs' && (
          <div className="service-logs">
            {state.logs.length ? (
              state.logs.map((log, index) => (
                <div key={index} className={`service-log ${log.level}`}>
                  <Badge
                    size="xs"
                    color={log.level === 'error' ? 'error' : log.level === 'warning' ? 'warning' : 'neutral'}
                  >
                    {log.level}
                  </Badge>
                  <pre>{log.message}</pre>
                </div>
              ))
            ) : (
              <div className="empty-state">No language service messages.</div>
            )}
          </div>
        )}
      </div>
    </section>
  );
}
