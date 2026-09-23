import { useEffect, useRef, useState, type ReactNode } from 'react';
import { Button, Group, Modal, Text, TextInput } from '@mantine/core';
import { sessionFromWorkspace } from './session';
import { activeFile, type Session } from '../core/session';
import type { WorkbenchController, WorkbenchState } from './controller';
import { errorMessage } from '../core/controller';
import type { Command } from './commands';
import type { Replacement } from './Dialogs';
import { downloadBlob, downloadFile } from '../persistence/browser-files';
import { exportSession, importSession, maximumProjectBytes, projectFileExtension } from '../persistence/session-store';
import { Workspace, workspaceLimits, type WorkspaceEntry, type WorkspaceInput } from '../workspace/model';
import { pathName, parentPath } from '../workspace/path';
import { decodeWorkspaceText } from '../workspace/text';
import { getWorkspaceArchiveFormats } from '../workspace/archives';

export interface ExplorerActions {
  createFile(directory?: string): void;
  createDirectory(directory?: string): void;
  move(entry: WorkspaceEntry): void;
  remove(entry: WorkspaceEntry): void;
  selectEntry(fileId: string): void;
}
type PathDialog =
  { kind: 'file' | 'directory' } | { kind: 'move'; entry: WorkspaceEntry } | { kind: 'remove'; entry: WorkspaceEntry };
interface FileActionsProps {
  controller: WorkbenchController;
  state: WorkbenchState;
  replace: (replacement: Replacement) => void;
  report: (error: unknown) => void;
}

/** Owns browser file operations and their dialogs; every workspace mutation goes through the controller/model. */
export function useFileActions({ controller, state, replace, report }: FileActionsProps): {
  actions: ExplorerActions;
  commands: Command[];
  dialogs: ReactNode;
} {
  const [dialog, setDialog] = useState<PathDialog | null>(null);
  const [path, setPath] = useState('');
  const [failure, setFailure] = useState<string>();
  const [busy, setBusy] = useState<string>();
  const operation = useRef<AbortController | null>(null);
  const upload = useRef<HTMLInputElement>(null);
  const definition = controller.plugin.definition;
  const current = activeFile(state.session);
  useEffect(
    () => () => {
      operation.current?.abort();
      operation.current = null;
    },
    [],
  );
  const show = (dialog: PathDialog, path = '') => {
    setFailure(undefined);
    setPath(path);
    setDialog(dialog);
  };
  const suggestedDirectory = () => (current ? parentPath(current.path) : '');
  const actions: ExplorerActions = {
    createFile(directory = suggestedDirectory()) {
      let number = 1;
      const prefix = directory ? `${directory}/` : '';
      while (state.session.workspace.entry(`${prefix}untitled${number}${definition.extension}`)) number++;
      show({ kind: 'file' }, `${prefix}untitled${number}${definition.extension}`);
    },
    createDirectory(directory = suggestedDirectory()) {
      show({ kind: 'directory' }, directory ? `${directory}/` : '');
    },
    move: (entry) => show({ kind: 'move', entry }, entry.path),
    remove: (entry) => show({ kind: 'remove', entry }),
    selectEntry: (fileId) => controller.updateSession({ ...controller.getSnapshot().session, entryFileId: fileId }),
  };
  async function runFileOperation(label: string, execute: (signal: AbortSignal) => Promise<void>) {
    if (operation.current) return;
    const cancellation = new AbortController();
    operation.current = cancellation;
    setBusy(label);
    try {
      await execute(cancellation.signal);
    } catch (error) {
      if (!cancellation.signal.aborted) report(error);
    } finally {
      if (operation.current === cancellation) {
        operation.current = null;
        setBusy(undefined);
      }
    }
  }
  async function openFiles(files: readonly File[], signal: AbortSignal) {
    const formats = getWorkspaceArchiveFormats();
    const containers = files.filter(
      (file) =>
        file.name.toLowerCase().endsWith(projectFileExtension) ||
        formats.some((format) => format.extensions.some((extension) => file.name.toLowerCase().endsWith(extension))),
    );
    if (containers.length) {
      if (files.length !== 1) throw new Error('Open one project or archive at a time.');
      const input = containers[0]!;
      const format = formats.find((format) =>
        format.extensions.some((extension) => input.name.toLowerCase().endsWith(extension)),
      );
      let session: Session;
      if (format) session = sessionFromWorkspace(definition, await format.read(input, signal));
      else {
        if (input.size > maximumProjectBytes) throw new Error('The project exceeds the import size limit.');
        session = importSession(await input.text());
        if (session.languageId !== definition.id)
          throw new Error(`This project needs the “${session.languageId}” language adapter.`);
      }
      signal.throwIfAborted();
      replace({ title: `Open “${input.name}”?`, session });
      return;
    }
    if (files.length > workspaceLimits.files) throw new Error('The selection exceeds the workspace file limit.');
    let bytes = 0;
    const imported: WorkspaceInput['files'][number][] = [];
    for (const file of files) {
      signal.throwIfAborted();
      bytes += file.size;
      if (file.size > workspaceLimits.fileBytes || bytes > workspaceLimits.totalBytes)
        throw new Error('The selection exceeds the workspace source size limit.');
      imported.push({
        id: crypto.randomUUID(),
        path: file.webkitRelativePath || file.name,
        text: decodeWorkspaceText(new Uint8Array(await file.arrayBuffer()), file.name),
      });
    }
    signal.throwIfAborted();
    if (!imported.length) return;
    const session = controller.getSnapshot().session;
    const incoming = new Workspace({ files: imported });
    const workspace = session.workspace.merge(incoming);
    const active = workspace.files.find((file) => file.path === imported[0]!.path)!;
    const result: Session = {
      ...session,
      workspace,
      openFileIds: [...new Set([...session.openFileIds, active.id])],
      activeFileId: active.id,
      entryFileId: session.entryFileId ?? active.id,
    };
    if (incoming.files.some((file) => session.workspace.entry(file.path)))
      replace({ title: 'Replace existing files?', session: result });
    else controller.updateSession(result);
  }
  const unavailable = busy ? 'A file operation is in progress' : undefined;
  const commands: Command[] = [
    {
      id: 'import',
      label: 'Open files, project, or archive',
      category: 'File',
      unavailable,
      execute: () => upload.current?.click(),
    },
    { id: 'new', label: 'New source file', category: 'File', execute: () => actions.createFile() },
    { id: 'new-folder', label: 'New folder', category: 'File', execute: () => actions.createDirectory() },
    {
      id: 'move-file',
      label: 'Move or rename file',
      category: 'File',
      unavailable: current ? undefined : 'No file is open',
      execute: () => {
        if (current) actions.move({ kind: 'file', file: current, path: current.path });
      },
    },
    {
      id: 'close-file',
      label: 'Close file tab',
      category: 'File',
      unavailable: current ? undefined : 'No file is open',
      execute: () => {
        if (current) controller.closeFile(current.id);
      },
    },
    {
      id: 'delete-file',
      label: 'Delete file',
      category: 'File',
      unavailable: current ? undefined : 'No file is open',
      execute: () => {
        if (current) actions.remove({ kind: 'file', file: current, path: current.path });
      },
    },
    {
      id: 'download',
      label: 'Download source',
      category: 'File',
      unavailable: current ? undefined : 'No file is open',
      execute: () => {
        if (current) downloadFile(pathName(current.path), current.text);
      },
    },
    {
      id: 'export',
      label: 'Export project',
      category: 'File',
      execute: () => downloadFile(`workspace${projectFileExtension}`, exportSession(state.session), 'application/json'),
    },
    ...getWorkspaceArchiveFormats().map((format): Command => ({
      id: `export-${format.id}`,
      label: `Export ${format.label} archive`,
      category: 'File',
      unavailable,
      execute: () =>
        runFileOperation(`Exporting ${format.label}…`, async (signal) => {
          const archive = await format.write(state.session.workspace, signal);
          signal.throwIfAborted();
          downloadBlob(`workspace${format.extensions[0]}`, archive);
        }),
    })),
  ];
  const submit = () => {
    try {
      if (dialog?.kind === 'file') controller.addFile(path);
      else if (dialog?.kind === 'directory') controller.addDirectory(path);
      else if (dialog?.kind === 'move') controller.movePath(dialog.entry.path, path);
      else if (dialog?.kind === 'remove') controller.removePath(dialog.entry.path);
      setDialog(null);
    } catch (error) {
      setFailure(errorMessage(error));
    }
  };
  const title =
    dialog?.kind === 'file'
      ? 'New source file'
      : dialog?.kind === 'directory'
        ? 'New folder'
        : dialog?.kind === 'move'
          ? 'Move or rename'
          : 'Delete entry';
  return {
    actions,
    commands,
    dialogs: (
      <>
        <input
          ref={upload}
          type="file"
          multiple
          className="sr-only"
          tabIndex={-1}
          aria-label="Import files, project, or archive"
          onChange={(event) => {
            const files = Array.from(event.currentTarget.files ?? []);
            event.currentTarget.value = '';
            if (files.length) void runFileOperation('Opening files…', (signal) => openFiles(files, signal));
          }}
        />
        <Modal opened={dialog !== null} onClose={() => setDialog(null)} title={title} centered size="md">
          <form
            onSubmit={(event) => {
              event.preventDefault();
              submit();
            }}
          >
            {dialog?.kind === 'remove' ? (
              <Text size="sm">
                Delete “{dialog.entry.path}”{dialog.entry.kind === 'directory' ? ' and everything inside it' : ''}? A
                checkpoint will be saved in local history.
              </Text>
            ) : (
              <TextInput
                label="Path"
                description="Relative to workspace; use / for folders."
                value={path}
                onChange={(event) => setPath(event.currentTarget.value)}
                error={failure}
                data-autofocus
              />
            )}
            {dialog?.kind === 'remove' && failure && (
              <Text c="error" size="sm" mt="sm">
                {failure}
              </Text>
            )}
            <Group justify="flex-end" mt="lg">
              <Button variant="default" onClick={() => setDialog(null)}>
                Cancel
              </Button>
              <Button type="submit" color={dialog?.kind === 'remove' ? 'error' : undefined}>
                {dialog?.kind === 'remove' ? 'Delete' : dialog?.kind === 'move' ? 'Move' : 'Create'}
              </Button>
            </Group>
          </form>
        </Modal>
        <Modal
          opened={Boolean(busy)}
          onClose={() => operation.current?.abort()}
          title={busy}
          centered
          closeOnClickOutside={false}
        >
          <Text size="sm">Processing workspace files…</Text>
          <Button mt="lg" variant="default" onClick={() => operation.current?.abort()}>
            Cancel
          </Button>
        </Modal>
      </>
    ),
  };
}
