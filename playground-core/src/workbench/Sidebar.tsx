import { useState } from 'react';
import { Badge, Button, TextInput } from '@mantine/core';
import { IconBraces, IconChevronRight, IconHistory, IconSearch } from '@tabler/icons-react';
import type { DocumentSymbol, Example, Location } from '../core/contracts';
import { checkpointLimit } from './session';
import { activeFile, type Session } from '../core/session';
import { FileExplorer } from './FileExplorer';
import type { ExplorerActions } from './file-actions';
import type { WorkbenchController, WorkbenchState } from './controller';

export type SidebarView = 'outline' | 'examples' | 'history';
interface SidebarProps {
  fileActions: ExplorerActions;
  view: SidebarView;
  controller: WorkbenchController;
  state: WorkbenchState;
  reveal: (location: Location) => void;
  example: (example: Example) => void;
  restore: (session: Session) => void;
}
function SymbolTree({ symbols, reveal }: { symbols: readonly DocumentSymbol[]; reveal: (location: Location) => void }) {
  return (
    <ul className="symbol-tree">
      {symbols.map((symbol) => (
        <li key={`${symbol.name}:${symbol.location.range.start.line}:${symbol.location.range.start.character}`}>
          <button onClick={() => reveal(symbol.location)} title={symbol.detail ?? symbol.name}>
            <span className={`symbol-kind ${symbol.kind}`}>
              {symbol.kind === 'function' ? 'ƒ' : symbol.kind === 'type' ? 'T' : '◇'}
            </span>
            <span className="ellipsis">{symbol.name}</span>
            <small>{symbol.location.range.start.line + 1}</small>
          </button>
          {symbol.children.length > 0 && <SymbolTree symbols={symbol.children} reveal={reveal} />}
        </li>
      ))}
    </ul>
  );
}
export function Sidebar({ fileActions, view, controller, state, reveal, example, restore }: SidebarProps) {
  const [query, setQuery] = useState('');
  const symbols = state.documents.find((document) => document.fileId === state.session.activeFileId)?.symbols ?? [];
  const title = { outline: 'Workspace', examples: 'Explore examples', history: 'Local history' }[view];
  const matching = (symbols: readonly DocumentSymbol[]): DocumentSymbol[] =>
    symbols.flatMap((symbol) => {
      const children = matching(symbol.children);
      return symbol.name.toLowerCase().includes(query.toLowerCase())
        ? [symbol]
        : children.length
          ? [{ ...symbol, children }]
          : [];
    });
  return (
    <aside className="sidebar" aria-label={title}>
      <div className="sidebar-heading">
        <span>{title}</span>
        <Badge size="xs" variant="light">
          LOCAL
        </Badge>
      </div>
      {view === 'outline' && (
        <>
          <FileExplorer
            workspace={state.session.workspace}
            activeFileId={state.session.activeFileId}
            entryFileId={state.session.entryFileId}
            open={(id) => controller.selectFile(id)}
            actions={fileActions}
          />
          <div className="sidebar-section-heading">
            <IconBraces size={14} /> OUTLINE <span>{symbols.length}</span>
          </div>
          <div className="sidebar-filter">
            <TextInput
              size="xs"
              placeholder="Filter symbols…"
              aria-label="Filter symbols"
              leftSection={<IconSearch size={13} />}
              value={query}
              onChange={(event) => setQuery(event.currentTarget.value)}
            />
          </div>
          <div className="sidebar-scroll">
            <SymbolTree symbols={matching(symbols)} reveal={reveal} />
            {!symbols.length && (
              <div className="empty-state compact">
                {state.service.kind === 'starting'
                  ? 'Starting language services…'
                  : 'Symbols in your file will appear here.'}
              </div>
            )}
          </div>
        </>
      )}
      {view === 'examples' && (
        <div className="sidebar-scroll example-list">
          {controller.plugin.definition.examples.map((item, index) => (
            <button className="example-card" key={item.id} onClick={() => example(item)}>
              <small>EXAMPLE {String(index + 1).padStart(2, '0')}</small>
              <strong>{item.title}</strong>
              <p>{item.description}</p>
              <span>
                Open example <IconChevronRight size={14} />
              </span>
            </button>
          ))}
        </div>
      )}
      {view === 'history' && (
        <>
          <div className="history-intro">
            <p className="muted">
              Save a checkpoint before trying something new. Your latest {checkpointLimit} are kept locally.
            </p>
            <Button
              variant="light"
              size="xs"
              leftSection={<IconHistory size={14} />}
              onClick={() => controller.checkpoint()}
            >
              Save checkpoint
            </Button>
          </div>
          <div className="sidebar-scroll">
            {state.history.map((snapshot) => (
              <button className="history-item" key={snapshot.id} onClick={() => restore(snapshot.session)}>
                <strong>{activeFile(snapshot.session)?.path ?? 'Empty workspace'}</strong>
                <span>{new Date(snapshot.createdAt).toLocaleString()}</span>
                <small>{snapshot.session.workspace.files.length} files · Restore</small>
              </button>
            ))}
            {!state.history.length && <div className="empty-state compact">No checkpoints yet.</div>}
          </div>
        </>
      )}
    </aside>
  );
}
