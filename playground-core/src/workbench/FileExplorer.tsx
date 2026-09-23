import { useEffect, useRef, useState, type KeyboardEvent } from 'react';
import { ActionIcon, Menu, Tooltip } from '@mantine/core';
import {
  IconChevronDown,
  IconChevronRight,
  IconDots,
  IconFileCode,
  IconFilePlus,
  IconFolder,
  IconFolderOpen,
  IconFolderPlus,
} from '@tabler/icons-react';
import type { Workspace, WorkspaceEntry } from '../workspace/model';
import { parentPath, pathName, type WorkspacePath } from '../workspace/path';
import type { ExplorerActions } from './file-actions';

interface FileExplorerProps {
  workspace: Workspace;
  activeFileId: string | null;
  entryFileId: string | null;
  open: (id: string) => void;
  actions: ExplorerActions;
}
interface VisibleEntry {
  entry: WorkspaceEntry;
  level: number;
  position: number;
  siblings: number;
}

function ancestors(path: WorkspacePath | undefined): WorkspacePath[] {
  const parents: WorkspacePath[] = [];
  for (let parent = path ? parentPath(path) : ''; parent; parent = parentPath(parent)) parents.push(parent);
  return parents;
}

function EntryActions({
  entry,
  actions,
  open,
  button,
}: {
  entry: WorkspaceEntry;
  actions: ExplorerActions;
  open: (id: string) => void;
  button: React.RefObject<HTMLButtonElement | null>;
}) {
  const directory = entry.kind === 'directory' ? entry.path : parentPath(entry.path);
  return (
    <Menu position="bottom-end" withinPortal>
      <Menu.Target>
        <ActionIcon
          ref={button}
          size="xs"
          variant="subtle"
          color="neutral"
          tabIndex={-1}
          className="file-entry-menu"
          aria-label={`Actions for ${entry.path}`}
          onClick={(event) => event.stopPropagation()}
        >
          <IconDots size={15} />
        </ActionIcon>
      </Menu.Target>
      <Menu.Dropdown onClick={(event) => event.stopPropagation()}>
        {entry.kind === 'file' && <Menu.Item onClick={() => open(entry.file.id)}>Open file</Menu.Item>}
        <Menu.Item onClick={() => actions.createFile(directory)}>New file here</Menu.Item>
        <Menu.Item onClick={() => actions.createDirectory(directory)}>New folder here</Menu.Item>
        <Menu.Divider />
        <Menu.Item onClick={() => actions.move(entry)}>Move or rename</Menu.Item>
        {entry.kind === 'file' && (
          <Menu.Item onClick={() => actions.selectEntry(entry.file.id)}>Use as entry file</Menu.Item>
        )}
        <Menu.Item color="error" onClick={() => actions.remove(entry)}>
          Delete
        </Menu.Item>
      </Menu.Dropdown>
    </Menu>
  );
}

function ExplorerRow({
  item,
  expanded,
  selected,
  active,
  entryFile,
  activate,
  navigate,
  actions,
  open,
  setElement,
  focus,
}: {
  item: VisibleEntry;
  expanded: boolean;
  selected: boolean;
  active: boolean;
  entryFile: boolean;
  activate: () => void;
  navigate: (event: KeyboardEvent<HTMLLIElement>) => void;
  actions: ExplorerActions;
  open: (id: string) => void;
  setElement: (element: HTMLLIElement | null) => void;
  focus: () => void;
}) {
  const menu = useRef<HTMLButtonElement>(null);
  const { entry, level, position, siblings } = item;
  const folder = entry.kind === 'directory';
  const Icon = folder ? (expanded ? IconFolderOpen : IconFolder) : IconFileCode;
  return (
    <li
      ref={setElement}
      role="treeitem"
      aria-label={entry.path}
      aria-level={level}
      aria-posinset={position}
      aria-setsize={siblings}
      aria-selected={selected}
      aria-expanded={folder ? expanded : undefined}
      tabIndex={selected ? 0 : -1}
      className={`workspace-entry ${active ? 'active' : ''}`}
      style={{ paddingInlineStart: 8 + (level - 1) * 16 }}
      onFocus={focus}
      onClick={activate}
      title={entry.path}
      onContextMenu={(event) => {
        event.preventDefault();
        menu.current?.click();
      }}
      onKeyDown={(event) => {
        if (event.target !== event.currentTarget) return;
        if (event.key === 'F2') {
          event.preventDefault();
          actions.move(entry);
        } else if (event.key === 'Delete') {
          event.preventDefault();
          actions.remove(entry);
        } else if (event.key === 'ContextMenu' || (event.key === 'F10' && event.shiftKey)) {
          event.preventDefault();
          menu.current?.click();
        } else navigate(event);
      }}
    >
      <span className="file-entry-chevron">
        {folder && (expanded ? <IconChevronDown size={13} /> : <IconChevronRight size={13} />)}
      </span>
      <Icon size={16} className="file-entry-icon" />
      <span className="file-entry-name">{pathName(entry.path)}</span>
      {entryFile && <small>entry</small>}
      <EntryActions entry={entry} actions={actions} open={open} button={menu} />
    </li>
  );
}

/** Accessible, keyboard-navigable projection of the workspace; expansion and focus are presentation state only. */
export function FileExplorer({ workspace, activeFileId, entryFileId, open, actions }: FileExplorerProps) {
  const active = activeFileId ? workspace.file(activeFileId)?.path : undefined;
  const [expanded, setExpanded] = useState<ReadonlySet<string>>(() => new Set(ancestors(active)));
  const [focused, setFocused] = useState<string | undefined>(active);
  const elements = useRef(new Map<string, HTMLLIElement>());
  useEffect(() => {
    // Revealing a file from a tab or language-service navigation exposes its containing folders.
    setExpanded((previous) => new Set([...previous, ...ancestors(active)]));
  }, [active]);
  const visible: VisibleEntry[] = [];
  function visit(path: string, level: number) {
    const children = workspace.readDirectory(path);
    children.forEach((entry, index) => {
      visible.push({ entry, level, position: index + 1, siblings: children.length });
      if (entry.kind === 'directory' && expanded.has(entry.path)) visit(entry.path, level + 1);
    });
  }
  visit('', 1);
  const focusPath = visible.some(({ entry }) => entry.path === focused)
    ? focused
    : (visible.find(({ entry }) => entry.path === active)?.entry.path ?? visible[0]?.entry.path);
  const selected = focusPath ? workspace.entry(focusPath) : undefined;
  const directory = selected?.kind === 'directory' ? selected.path : selected ? parentPath(selected.path) : '';
  const focus = (path: string | undefined) => {
    if (path) {
      setFocused(path);
      elements.current.get(path)?.focus();
    }
  };
  const toggle = (path: string, value = !expanded.has(path)) =>
    setExpanded((previous) => {
      const next = new Set(previous);
      if (value) next.add(path);
      else next.delete(path);
      return next;
    });
  const activate = (entry: WorkspaceEntry) => {
    focus(entry.path);
    if (entry.kind === 'file') open(entry.file.id);
    else toggle(entry.path);
  };
  function navigate(event: KeyboardEvent<HTMLLIElement>, item: VisibleEntry, index: number) {
    const { entry } = item;
    if (['ArrowDown', 'ArrowUp', 'Home', 'End', 'ArrowLeft', 'ArrowRight', 'Enter', ' '].includes(event.key))
      event.preventDefault();
    if (event.key === 'ArrowDown') focus(visible[Math.min(index + 1, visible.length - 1)]?.entry.path);
    else if (event.key === 'ArrowUp') focus(visible[Math.max(index - 1, 0)]?.entry.path);
    else if (event.key === 'Home') focus(visible[0]?.entry.path);
    else if (event.key === 'End') focus(visible.at(-1)?.entry.path);
    else if (event.key === 'ArrowRight' && entry.kind === 'directory') {
      if (!expanded.has(entry.path)) toggle(entry.path, true);
      else if (visible[index + 1]?.level === item.level + 1) focus(visible[index + 1]?.entry.path);
    } else if (event.key === 'ArrowLeft') {
      if (entry.kind === 'directory' && expanded.has(entry.path)) toggle(entry.path, false);
      else focus(parentPath(entry.path));
    } else if (event.key === 'Enter' || event.key === ' ') activate(entry);
    else if (event.key.length === 1 && !event.ctrlKey && !event.metaKey && !event.altKey) {
      const sequence = [...visible.slice(index + 1), ...visible.slice(0, index + 1)];
      focus(
        sequence.find(({ entry }) => pathName(entry.path).toLocaleLowerCase().startsWith(event.key.toLocaleLowerCase()))
          ?.entry.path,
      );
    }
  }
  return (
    <section className="workspace-files" aria-label="File explorer">
      <div className="explorer-toolbar">
        <span>FILES</span>
        <div>
          <Tooltip label="New file">
            <ActionIcon
              variant="subtle"
              color="neutral"
              size="sm"
              aria-label="New file in folder"
              onClick={() => actions.createFile(directory)}
            >
              <IconFilePlus size={16} />
            </ActionIcon>
          </Tooltip>
          <Tooltip label="New folder">
            <ActionIcon
              variant="subtle"
              color="neutral"
              size="sm"
              aria-label="New folder"
              onClick={() => actions.createDirectory(directory)}
            >
              <IconFolderPlus size={16} />
            </ActionIcon>
          </Tooltip>
        </div>
      </div>
      <ul role="tree" aria-label="Workspace files" className="workspace-tree">
        {visible.map((item, index) => (
          <ExplorerRow
            key={item.entry.path}
            item={item}
            expanded={expanded.has(item.entry.path)}
            selected={item.entry.path === focusPath}
            active={item.entry.path === active}
            entryFile={item.entry.kind === 'file' && item.entry.file.id === entryFileId}
            open={open}
            actions={actions}
            focus={() => setFocused(item.entry.path)}
            activate={() => activate(item.entry)}
            navigate={(event) => navigate(event, item, index)}
            setElement={(element) => {
              if (element) elements.current.set(item.entry.path, element);
              else elements.current.delete(item.entry.path);
            }}
          />
        ))}
      </ul>
      {!visible.length && <div className="empty-state compact">No files or folders.</div>}
    </section>
  );
}
