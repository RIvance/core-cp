import { currentDiagnostics } from '../core/diagnostics';
import { useRef, useState, useSyncExternalStore } from 'react';
import { ActionIcon, Alert, Button, Drawer, Kbd, Menu, Modal, TextInput, Tooltip } from '@mantine/core';
import { useMediaQuery } from '@mantine/hooks';
import { notifications, type NotificationsStore } from '@mantine/notifications';
import {
  IconAdjustments,
  IconAlertCircle,
  IconBook2,
  IconBraces,
  IconChevronDown,
  IconCode,
  IconCommand,
  IconDownload,
  IconFileCode,
  IconHistory,
  IconKeyboard,
  IconLayoutSidebar,
  IconPlus,
  IconSearch,
  IconShare2,
  IconSparkles,
} from '@tabler/icons-react';
import { Group as PanelGroup, Panel, Separator } from 'react-resizable-panels';
import type { EditorHandle } from '../editor/workspace';
import type { EditorLanguage } from '../editor/monaco';
import { shareFragment } from '../persistence/session-store';
import { copyText, downloadFile } from '../persistence/browser-files';
import type { Example } from '../core/contracts';
import { sessionFromExample } from './session';
import { activeFile } from '../core/session';
import { useFileActions } from './file-actions';
import type { WorkbenchController } from './controller';
import { errorMessage } from '../core/controller';
import { CommandPalette, shortcutLabel, useCommandShortcuts, type Command } from './commands';
import { Sidebar, type SidebarView } from './Sidebar';
import type { ResultPanelId } from '../components/ResultPanel';
import { PlaygroundSurface } from '../components/PlaygroundSurface';
import { RunButton } from '../components/RunButton';
import { getTheme, onDidChangeTheme } from '../appearance/page-theme';
import { CapabilitiesDialog, HelpDialog, ReplaceDialog, SettingsDialog, type Replacement } from './Dialogs';
import { ThemePicker, type ThemePickerHandle } from './ThemePicker';

interface WorkbenchProps {
  controller: WorkbenchController;
  editorLanguage: EditorLanguage;
  startupWarnings: readonly string[];
  notificationStore: NotificationsStore;
  title?: string;
  host: HTMLElement;
}
type Dialog = 'settings' | 'help' | 'capabilities' | null;

export function Workbench({
  controller,
  editorLanguage,
  startupWarnings,
  notificationStore,
  title,
  host,
}: WorkbenchProps) {
  const theme = useSyncExternalStore(onDidChangeTheme, getTheme);
  const state = useSyncExternalStore(controller.subscribe, controller.getSnapshot);
  const file = activeFile(state.session);
  const entryFile = state.session.workspace.files.find((file) => file.id === state.session.entryFileId);
  const [sidebar, setSidebar] = useState<SidebarView>('outline');
  const compact = useMediaQuery('(max-width: 650px)');
  const [sidebarVisible, setSidebarVisible] = useState(() => !window.matchMedia('(max-width: 650px)').matches);
  const [panel, setPanel] = useState<ResultPanelId>('output');
  const [palette, setPalette] = useState(false);
  const [dialog, setDialog] = useState<Dialog>(null);
  const [replacement, setReplacement] = useState<Replacement | null>(null);
  const [shareLink, setShareLink] = useState<string | null>(null);
  const [position, setPosition] = useState({ line: 1, column: 1, selected: 0 });
  const [warningsVisible, setWarningsVisible] = useState(true);
  const editor = useRef<EditorHandle>(null);
  const themePicker = useRef<ThemePickerHandle>(null);
  const definition = controller.plugin.definition;
  const capabilities = state.service.kind === 'ready' ? state.service.service.capabilities : {};
  const diagnostics = currentDiagnostics(state);
  const running = state.execution.kind === 'running';
  const report = (error: unknown) =>
    notifications.show(
      { title: 'Could not complete the action', message: errorMessage(error), color: 'error' },
      notificationStore,
    );
  const perform = (operation: () => void | Promise<void>) => {
    try {
      void Promise.resolve(operation()).catch(report);
    } catch (error) {
      report(error);
    }
  };
  const editorAction = (id: string) => {
    const source = editor.current?.getEditor();
    if (!source) throw new Error('The source editor is still loading.');
    source.focus();
    source.trigger('workbench', id, undefined);
  };
  const showSidebar = (view: SidebarView) => {
    setSidebar(view);
    setSidebarVisible(true);
  };
  const execute = (command: Command) => {
    if (!command.unavailable) perform(command.execute);
  };
  const copy = (text: string) =>
    perform(async () => {
      await copyText(text);
      notifications.show({ message: 'Copied to clipboard', color: 'success' }, notificationStore);
    });
  const run = () => {
    setPanel('output');
    return controller.run();
  };
  const openExample = (example: Example) =>
    setReplacement({
      title: `Open “${example.title}”?`,
      session: sessionFromExample(definition, example),
    });

  const fileActions = useFileActions({ controller, state, replace: setReplacement, report });

  const commands: Command[] = [
    {
      id: 'palette',
      label: 'Open command palette',
      category: 'Workspace',
      bindings: [
        { key: 'P', controlOrMeta: true, shift: true, scope: 'workspace' },
        { key: 'F1', scope: 'workspace' },
      ],
      execute: () => setPalette((value) => !value),
    },
    {
      id: 'run',
      label: 'Run program',
      category: 'Run',
      bindings: [{ key: 'Enter', controlOrMeta: true, scope: 'workspace' }],
      unavailable: entryFile ? undefined : 'Add an entry file before running',
      execute: run,
    },
    {
      id: 'stop',
      label: 'Stop execution',
      category: 'Run',
      bindings: [{ key: 'Escape', shift: true, scope: 'workspace' }],
      unavailable: running ? undefined : 'No program is running',
      execute: () => controller.stop(),
    },
    {
      id: 'checkpoint',
      label: 'Save checkpoint',
      category: 'File',
      bindings: [{ key: 'S', controlOrMeta: true, scope: 'workspace' }],
      execute: () => {
        controller.checkpoint();
        if (controller.getSnapshot().persistence === 'saved')
          notifications.show({ message: 'Checkpoint saved', color: 'success' }, notificationStore);
      },
    },
    ...fileActions.commands,
    {
      id: 'share',
      label: 'Share source link',
      category: 'File',
      execute: () => {
        const url = new URL(window.location.href);
        url.hash = shareFragment(state.session);
        setShareLink(url.toString());
      },
    },
    {
      id: 'find',
      label: 'Find in file',
      category: 'Editor',
      bindings: [{ key: 'F', controlOrMeta: true, scope: 'editor' }],
      execute: () => editorAction('actions.find'),
    },
    {
      id: 'replace',
      label: 'Find and replace',
      category: 'Editor',
      bindings: [{ key: 'H', controlOrMeta: true, scope: 'editor' }],
      execute: () => editorAction('editor.action.startFindReplaceAction'),
    },
    {
      id: 'goto-line',
      label: 'Go to line',
      category: 'Navigate',
      bindings: [{ key: 'G', controlOrMeta: true, scope: 'editor' }],
      execute: () => editorAction('editor.action.gotoLine'),
    },
    {
      id: 'symbols',
      label: 'Go to symbol',
      category: 'Navigate',
      bindings: [{ key: 'O', controlOrMeta: true, shift: true, scope: 'editor' }],
      unavailable: capabilities.symbols ? undefined : 'Not provided by this language service',
      execute: () => editorAction('editor.action.quickOutline'),
    },
    {
      id: 'definition',
      label: 'Go to definition',
      category: 'Navigate',
      bindings: [{ key: 'F12', scope: 'editor' }],
      unavailable: capabilities.definitions ? undefined : 'Not provided by this language service',
      execute: () => editorAction('editor.action.revealDefinition'),
    },
    {
      id: 'references',
      label: 'Find references',
      category: 'Navigate',
      bindings: [{ key: 'F12', shift: true, scope: 'editor' }],
      unavailable: capabilities.references ? undefined : 'Not provided by this language service',
      execute: () => editorAction('editor.action.referenceSearch.trigger'),
    },
    {
      id: 'rename-symbol',
      label: 'Rename symbol',
      category: 'Editor',
      bindings: [{ key: 'F2', scope: 'editor' }],
      unavailable: capabilities.rename ? undefined : 'Not provided by this language service',
      execute: () => editorAction('editor.action.rename'),
    },
    {
      id: 'quick-fix',
      label: 'Quick fix',
      category: 'Editor',
      bindings: [{ key: '.', controlOrMeta: true, scope: 'editor' }],
      unavailable: capabilities.codeActions ? undefined : 'Not provided by this language service',
      execute: () => editorAction('editor.action.quickFix'),
    },
    {
      id: 'refactor',
      label: 'Refactor',
      category: 'Editor',
      unavailable: capabilities.codeActions ? undefined : 'Not provided by this language service',
      execute: () => editorAction('editor.action.refactor'),
    },
    {
      id: 'format',
      label: 'Format document',
      category: 'Editor',
      bindings: [{ key: 'F', shift: true, alt: true, scope: 'editor' }],
      unavailable: capabilities.format ? undefined : 'Not provided by this language service',
      execute: () => editorAction('editor.action.formatDocument'),
    },
    {
      id: 'comment',
      label: 'Toggle line comment',
      category: 'Editor',
      bindings: [{ key: '/', controlOrMeta: true, scope: 'editor' }],
      execute: () => editorAction('editor.action.commentLine'),
    },
    { id: 'fold', label: 'Fold all regions', category: 'Editor', execute: () => editorAction('editor.foldAll') },
    { id: 'unfold', label: 'Unfold all regions', category: 'Editor', execute: () => editorAction('editor.unfoldAll') },
    {
      id: 'editor-commands',
      label: 'All editor commands',
      category: 'Editor',
      execute: () => editorAction('editor.action.quickCommand'),
    },
    {
      id: 'wrap',
      label: 'Toggle word wrap',
      category: 'View',
      execute: () => controller.configure({ wordWrap: !state.settings.wordWrap }),
    },
    {
      id: 'theme',
      label: 'Choose color theme',
      category: 'View',
      execute: () => themePicker.current?.open(),
    },
    ...controller.themes.themes.map((theme): Command => ({
      id: `theme-${theme.id}`,
      label: `Use ${theme.label} theme`,
      category: 'Appearance',
      execute: () => controller.configure({ theme: theme.id }),
    })),
    {
      id: 'sidebar',
      label: 'Toggle sidebar',
      category: 'View',
      bindings: [{ key: 'B', controlOrMeta: true, scope: 'workspace' }],
      execute: () => setSidebarVisible((value) => !value),
    },
    { id: 'examples', label: 'Explore examples', category: 'View', execute: () => showSidebar('examples') },
    { id: 'history', label: 'Open local history', category: 'View', execute: () => showSidebar('history') },
    {
      id: 'settings',
      label: 'Workspace settings',
      category: 'View',
      bindings: [{ key: ',', controlOrMeta: true, scope: 'workspace' }],
      execute: () => setDialog('settings'),
    },
    { id: 'services', label: 'Language capabilities', category: 'View', execute: () => setDialog('capabilities') },
    { id: 'restart', label: 'Restart language service', category: 'Workspace', execute: () => controller.connect() },
    { id: 'help', label: 'Keyboard shortcuts', category: 'Help', execute: () => setDialog('help') },
  ];

  useCommandShortcuts(commands, execute, host);

  const selectedCommand = (id: string) => {
    const command = commands.find((candidate) => candidate.id === id);
    if (command) execute(command);
  };
  const tools = [
    { id: 'outline' as const, title: 'Workspace and outline', icon: IconFileCode },
    { id: 'examples' as const, title: 'Explore examples', icon: IconBook2 },
    { id: 'history' as const, title: 'Local history', icon: IconHistory },
  ];

  return (
    <main className="workbench" data-theme={theme.id}>
      <header className="app-header">
        <div className="brand">
          <span className="brand-mark">
            <IconCode size={23} stroke={2.2} />
          </span>
          <strong>
            {title ?? (
              <>
                playground<span className="brand-dot">.</span>
              </>
            )}
          </strong>
          <span className="brand-divider" />
          <span className="language-name">{definition.name}</span>
        </div>
        <button className="command-trigger" onClick={() => setPalette(true)}>
          <IconSearch size={15} />
          <span>Search commands…</span>
          <Kbd>Ctrl ⇧ P</Kbd>
        </button>
        <div className="header-actions">
          <ThemePicker
            ref={themePicker}
            themes={controller.themes.themes}
            selected={theme}
            onSelect={(theme) => perform(() => controller.configure({ theme }))}
          />
          <Tooltip label="Keyboard shortcuts">
            <ActionIcon
              className="keyboard-shortcuts-button"
              variant="subtle"
              color="neutral"
              aria-label="Keyboard shortcuts"
              onClick={() => setDialog('help')}
            >
              <IconKeyboard size={19} />
            </ActionIcon>
          </Tooltip>
          <Button
            variant="default"
            size="xs"
            leftSection={<IconShare2 size={15} />}
            onClick={() => selectedCommand('share')}
          >
            Share
          </Button>
        </div>
      </header>
      <div className="workspace-toolbar">
        <div className="toolbar-left">
          <Menu shadow="md" position="bottom-start">
            <Menu.Target>
              <button className="file-menu-trigger">
                File <IconChevronDown size={12} />
              </button>
            </Menu.Target>
            <Menu.Dropdown>
              {commands
                .filter((command) => command.category === 'File')
                .map((command) => (
                  <Menu.Item
                    key={command.id}
                    onClick={() => execute(command)}
                    disabled={Boolean(command.unavailable)}
                    rightSection={command.bindings?.[0] && <Kbd>{shortcutLabel(command.bindings[0])}</Kbd>}
                  >
                    {command.label}
                  </Menu.Item>
                ))}
            </Menu.Dropdown>
          </Menu>
          <span className="toolbar-divider" />
          <button className="toolbar-button" onClick={() => showSidebar('examples')}>
            <IconSparkles size={15} /> Examples
          </button>
          <button className="toolbar-button" onClick={() => setPalette(true)}>
            <IconCommand size={15} /> Commands
          </button>
        </div>
        <div className="run-controls">
          <span className="entry-label">
            {entryFile ? `${entryFile.path} · ${state.session.entryPoint}` : 'No entry file'}
          </span>
          <RunButton state={state} run={() => perform(run)} stop={() => controller.stop()} />
        </div>
      </div>
      {warningsVisible && startupWarnings.length > 0 && (
        <Alert color="warning" withCloseButton onClose={() => setWarningsVisible(false)}>
          {startupWarnings.join(' ')}
        </Alert>
      )}
      {state.persistence === 'failed' && (
        <Alert color="error" icon={<IconAlertCircle size={18} />} title="Local save failed">
          {state.persistenceError} Download your source to keep a copy.
        </Alert>
      )}
      {state.service.kind === 'failed' && (
        <Alert color="warning" icon={<IconAlertCircle size={18} />} title="Language services unavailable">
          {state.service.message}{' '}
          <button className="text-button" onClick={() => perform(() => controller.connect())}>
            Retry
          </button>
        </Alert>
      )}
      <div className="workspace-body">
        <nav className="activity-bar" aria-label="Workspace navigation">
          {tools.map((tool) => (
            <Tooltip key={tool.id} label={tool.title} position="right">
              <button
                className={sidebarVisible && sidebar === tool.id ? 'active' : ''}
                aria-label={tool.title}
                aria-pressed={sidebarVisible && sidebar === tool.id}
                onClick={() =>
                  sidebarVisible && sidebar === tool.id ? setSidebarVisible(false) : showSidebar(tool.id)
                }
              >
                <tool.icon size={22} stroke={1.6} />
              </button>
            </Tooltip>
          ))}
          <Tooltip label="Find in file" position="right">
            <button aria-label="Find in file" onClick={() => selectedCommand('find')}>
              <IconSearch size={22} stroke={1.6} />
            </button>
          </Tooltip>
          <div className="activity-spacer" />
          <Tooltip label="Workspace settings" position="right">
            <button aria-label="Workspace settings" onClick={() => setDialog('settings')}>
              <IconAdjustments size={22} stroke={1.6} />
            </button>
          </Tooltip>
        </nav>
        <PanelGroup orientation="horizontal" id="workspace-layout">
          {sidebarVisible && !compact && (
            <>
              <Panel id="sidebar" defaultSize="22%" minSize="180px" maxSize="40%" className="sidebar-panel">
                <Sidebar
                  view={sidebar}
                  fileActions={fileActions.actions}
                  controller={controller}
                  state={state}
                  reveal={(location) => editor.current?.reveal(location)}
                  example={openExample}
                  restore={(session) => setReplacement({ session, title: 'Restore this checkpoint?' })}
                />
              </Panel>
              <Separator className="resize-handle vertical" aria-label="Resize sidebar" />
            </>
          )}
          <Panel id="main" minSize="280px">
            <PlaygroundSurface
              controller={controller.core}
              editorLanguage={editorLanguage}
              editorRef={editor}
              panel={panel}
              onPanelChange={setPanel}
              onError={report}
              onPosition={(line, column, selected) => setPosition({ line, column, selected })}
              onCloseFile={(id) => controller.closeFile(id)}
              copy={copy}
              download={downloadFile}
              tabActions={
                <div className="editor-tab-actions">
                  <Tooltip label="New source file">
                    <ActionIcon
                      variant="subtle"
                      color="neutral"
                      aria-label="New source file"
                      onClick={() => selectedCommand('new')}
                    >
                      <IconPlus size={16} />
                    </ActionIcon>
                  </Tooltip>
                  <Tooltip label="Download source">
                    <ActionIcon
                      variant="subtle"
                      color="neutral"
                      disabled={!file}
                      aria-label="Download source"
                      onClick={() => selectedCommand('download')}
                    >
                      <IconDownload size={15} />
                    </ActionIcon>
                  </Tooltip>
                  <Tooltip label="Toggle sidebar">
                    <ActionIcon
                      variant="subtle"
                      color="neutral"
                      aria-label="Toggle sidebar"
                      onClick={() => setSidebarVisible((value) => !value)}
                    >
                      <IconLayoutSidebar size={15} />
                    </ActionIcon>
                  </Tooltip>
                </div>
              }
              emptyContent={
                <div className="empty-workspace">
                  <IconFileCode size={34} />
                  <strong>
                    {state.session.workspace.files.length ? 'No file is open' : 'Your workspace is empty'}
                  </strong>
                  <p>Select a file in the explorer, create a file, or open a project.</p>
                  <Button onClick={() => selectedCommand('new')}>New source file</Button>
                </div>
              }
            />
          </Panel>
        </PanelGroup>
      </div>
      <footer className="status-bar">
        <div>
          <button className="service-status" onClick={() => setDialog('capabilities')}>
            <span className={`small-dot ${state.service.kind === 'ready' ? '' : 'warning'}`} />
            {state.service.kind === 'ready'
              ? 'Language service ready'
              : state.service.kind === 'starting'
                ? 'Starting language service…'
                : 'Language service unavailable'}
          </button>
          <button onClick={() => setPanel('problems')} aria-label={`${diagnostics.length} problems`}>
            <IconAlertCircle size={13} /> {diagnostics.filter((item) => item.severity === 'error').length}
            <span className="status-warning">△</span>
            {diagnostics.filter((item) => item.severity === 'warning').length}
          </button>
        </div>
        <div>
          <span className="saved-label">
            {state.persistence === 'saved' ? 'Saved locally' : state.persistence === 'pending' ? 'Saving…' : 'Unsaved'}
          </span>
          <button onClick={() => selectedCommand('goto-line')}>
            Ln {position.line}, Col {position.column}
            {position.selected ? ` (${position.selected} selected)` : ''}
          </button>
          <button onClick={() => setDialog('settings')}>Spaces: {state.settings.tabSize}</button>
          <span>UTF-8</span>
          <span>
            <IconBraces size={13} /> {definition.name}
          </span>
        </div>
      </footer>
      <div className="sr-only" role="status" aria-live="polite">
        {running
          ? 'Program running'
          : state.execution.kind === 'finished'
            ? `Execution ${state.execution.result.status}`
            : ''}
      </div>
      {fileActions.dialogs}
      <CommandPalette opened={palette} close={() => setPalette(false)} commands={commands} execute={execute} />
      <Drawer
        opened={Boolean(compact && sidebarVisible)}
        onClose={() => setSidebarVisible(false)}
        title="Workspace"
        size="85%"
        padding={0}
      >
        <div className="mobile-sidebar">
          <Sidebar
            view={sidebar}
            fileActions={fileActions.actions}
            controller={controller}
            state={state}
            reveal={(location) => {
              editor.current?.reveal(location);
              setSidebarVisible(false);
            }}
            example={openExample}
            restore={(session) => setReplacement({ session, title: 'Restore this checkpoint?' })}
          />
        </div>
      </Drawer>
      <SettingsDialog
        opened={dialog === 'settings'}
        close={() => setDialog(null)}
        controller={controller}
        state={state}
      />
      <HelpDialog opened={dialog === 'help'} close={() => setDialog(null)} commands={commands} />
      <CapabilitiesDialog
        opened={dialog === 'capabilities'}
        close={() => setDialog(null)}
        state={state}
        restart={() => perform(() => controller.connect())}
      />
      <ReplaceDialog
        replacement={replacement}
        close={() => setReplacement(null)}
        change={(session) => setReplacement((current) => (current ? { ...current, session } : null))}
        confirm={() => {
          if (replacement) {
            perform(() => {
              controller.replaceSession(replacement.session);
              setReplacement(null);
              setPanel('output');
            });
          }
        }}
      />
      <Modal opened={shareLink !== null} onClose={() => setShareLink(null)} title="Share workspace" centered>
        <p className="muted">The link contains a copy of the workspace. Later edits do not update that copy.</p>
        <TextInput
          aria-label="Share link"
          readOnly
          value={shareLink ?? ''}
          onFocus={(event) => event.currentTarget.select()}
        />
        <Button mt="md" leftSection={<IconShare2 size={16} />} onClick={() => copy(shareLink ?? '')}>
          Copy link
        </Button>
      </Modal>
    </main>
  );
}
