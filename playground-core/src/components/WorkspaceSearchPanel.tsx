import { useEffect, useMemo, useRef, useState, type ReactNode, type Ref } from 'react';
import { ActionIcon, TextInput, Tooltip } from '@mantine/core';
import { useMergedRef } from '@mantine/hooks';
import { IconLetterCase, IconRegex, IconSearch, IconTextScan2 } from '@tabler/icons-react';
import type { Workspace } from '../workspace/model';
import type { WorkspaceSearchMatch, WorkspaceSearchOptions, WorkspaceSearchResult } from '../workspace/search';
import type { SearchReply, SearchRequest } from './workspace-search.worker';

/** Host inputs for a language-independent sidebar search. No compiler, service, persistence, or URI policy is used. */
export interface WorkspaceSearchPanelProps {
  /** Immutable source snapshot, including files whose editor tabs are closed. Changes cancel older searches. */
  readonly workspace: Workspace;
  /** Open/select this file and range. The host owns navigation and translates file identity to its document URI. */
  readonly onSelect: (fileId: string, match: WorkspaceSearchMatch) => void;
  /** Pause workers while hidden, retaining the query and options. Defaults to true. */
  readonly active?: boolean;
  /** Focus the search input when activated. Defaults to false. */
  readonly autoFocus?: boolean;
  /** Optional access to the search field for host keyboard commands. The component owns its value and lifetime. */
  readonly inputRef?: Ref<HTMLInputElement>;
  /** Total match limit, passed to searchWorkspace. Defaults to 1,000. Truncation is visible in the result summary. */
  readonly limit?: number;
  /** Positive maximum worker lifetime in milliseconds. Defaults to 2,000; timeouts terminate the search worker. */
  readonly timeout?: number;
}

interface SearchInput {
  readonly workspace: Workspace;
  readonly query: string;
  readonly options: WorkspaceSearchOptions;
  readonly timeout: number;
}

type SearchState = { readonly input: SearchInput } & (
  | { readonly kind: 'failed'; readonly message: string }
  | { readonly kind: 'ready'; readonly result: WorkspaceSearchResult }
);

/**
 * Search workspace text with literal, case-sensitive, whole-word, and regexp controls. Results are grouped by
 * file; selecting a result calls onSelect. Every query runs in a worker; edits, option changes, hiding the panel,
 * or unmounting terminate obsolete work. Stale results cannot navigate. Invalid queries and timeouts appear inline.
 * Mount under ThemeProvider and include the package stylesheet; the parent supplies the sidebar heading and height.
 */
export function WorkspaceSearchPanel({
  workspace,
  onSelect,
  active = true,
  autoFocus = false,
  inputRef,
  limit = 1_000,
  timeout = 2_000,
}: WorkspaceSearchPanelProps): ReactNode {
  const [query, setQuery] = useState('');
  const input = useRef<HTMLInputElement>(null);
  const mergedInput = useMergedRef(input, inputRef);
  useEffect(() => {
    if (active && autoFocus) input.current?.focus();
  }, [active, autoFocus]);
  const [options, setOptions] = useState<WorkspaceSearchOptions>({});
  const search = useMemo<SearchInput>(
    () => ({ workspace, query, options: { ...options, limit }, timeout }),
    [workspace, query, options, limit, timeout],
  );
  const [state, setState] = useState<SearchState>();
  useEffect(() => {
    if (!active || !search.query) return;
    const fail = (message: string) => setState({ input: search, kind: 'failed', message });
    if (!Number.isFinite(search.timeout) || search.timeout <= 0) {
      fail('The search timeout must be positive.');
      return;
    }
    let worker: Worker | undefined;
    let timer: ReturnType<typeof setTimeout> | undefined;
    let running = true;
    const stop = () => {
      running = false;
      worker?.terminate();
      clearTimeout(timer);
    };
    try {
      worker = new Worker(new URL('./workspace-search.worker.ts', import.meta.url), { type: 'module' });
      worker.onmessage = (event: MessageEvent<SearchReply>) => {
        if (!running) return;
        stop();
        const reply = event.data;
        setState(
          reply.kind === 'result'
            ? { input: search, kind: 'ready', result: reply.result }
            : { input: search, kind: 'failed', message: reply.message },
        );
      };
      worker.onerror = (event) => {
        if (!running) return;
        event.preventDefault();
        stop();
        fail(event.message || 'The search worker failed.');
      };
      timer = setTimeout(() => {
        if (!running) return;
        stop();
        fail('Search timed out. Narrow the query or simplify the regular expression.');
      }, search.timeout);
      const request: SearchRequest = {
        workspace: search.workspace.snapshot,
        query: search.query,
        options: search.options,
      };
      worker.postMessage(request);
    } catch (error) {
      stop();
      fail(error instanceof Error ? error.message : String(error));
    }
    return stop;
  }, [active, search]);
  const current = state?.input === search ? state : undefined;
  const controls = [
    { key: 'caseSensitive', label: 'Match case', icon: IconLetterCase },
    { key: 'wholeWord', label: 'Match whole word', icon: IconTextScan2 },
    { key: 'regularExpression', label: 'Use regular expression', icon: IconRegex },
  ] as const;
  return (
    <div className="workspace-search-panel">
      <div className="workspace-search-input">
        <TextInput
          ref={mergedInput}
          aria-label="Search workspace files"
          placeholder="Search"
          leftSection={<IconSearch size={14} />}
          value={query}
          onChange={(event) => setQuery(event.currentTarget.value)}
        />
        <div className="workspace-search-options" role="group" aria-label="Search options">
          {controls.map(({ key, label, icon: Icon }) => (
            <Tooltip label={label} key={key}>
              <ActionIcon
                aria-label={label}
                aria-pressed={!!options[key]}
                variant={options[key] ? 'light' : 'subtle'}
                onClick={() => setOptions({ ...options, [key]: !options[key] })}
              >
                <Icon size={17} />
              </ActionIcon>
            </Tooltip>
          ))}
        </div>
      </div>
      <p className="workspace-search-summary" role="status">
        {!query
          ? 'Search all workspace files.'
          : current?.kind === 'failed'
            ? current.message
            : current?.kind === 'ready'
              ? searchSummary(current.result)
              : 'Searching…'}
      </p>
      <div className="workspace-search-results">
        {current?.kind === 'ready' &&
          current.result.files.map((file) => (
            <details open key={file.fileId}>
              <summary>
                <span>{file.path}</span>
                <small>{file.matches.length}</small>
              </summary>
              <ul aria-label={`Matches in ${file.path}`}>
                {file.matches.map((match) => (
                  <li key={`${match.range.start.line}:${match.range.start.character}`}>
                    <button
                      type="button"
                      onClick={() => onSelect(file.fileId, match)}
                      title={`${file.path}:${match.range.start.line + 1}:${match.range.start.character + 1}`}
                    >
                      <small>{match.range.start.line + 1}</small>
                      <span>
                        {match.preview.before}
                        <mark>{match.preview.match || '∣'}</mark>
                        {match.preview.after}
                      </span>
                    </button>
                  </li>
                ))}
              </ul>
            </details>
          ))}
      </div>
    </div>
  );
}

function searchSummary(result: WorkspaceSearchResult): string {
  const matches = result.count === 1 && !result.truncated ? 'match' : 'matches';
  const files = result.files.length === 1 ? 'file' : 'files';
  return `${result.count}${result.truncated ? '+' : ''} ${matches} in ${result.files.length} ${files}${result.truncated ? ' · result limit reached' : ''}`;
}
