import { Badge, Button, Group, Modal, NumberInput, Select, Stack, Switch, Text, TextInput } from '@mantine/core';
import { useEffect, useState } from 'react';
import type { ZodNumber } from 'zod';
import type { LanguageCapabilities } from '../core/contracts';
import { playgroundSettingsSchema } from '../core/settings';
import type { Session } from '../core/session';
import type { WorkbenchController, WorkbenchState } from './controller';
import { shortcutLabel, type Command } from './commands';

interface SettingsProps {
  opened: boolean;
  close: () => void;
  controller: WorkbenchController;
  state: WorkbenchState;
}

/** A partially typed number stays in the control; only valid settings reach the workspace. */
function IntegerSetting({
  value,
  change,
  schema,
  label,
  suffix,
  description,
}: {
  value: number;
  change: (value: number) => void;
  schema: ZodNumber;
  label: string;
  suffix: string;
  description?: string;
}) {
  const [draft, setDraft] = useState<number | string>(value);
  useEffect(() => setDraft(value), [value]);
  return (
    <NumberInput
      label={label}
      suffix={suffix}
      description={description}
      min={schema.minValue ?? undefined}
      max={schema.maxValue ?? undefined}
      clampBehavior="none"
      allowDecimal={false}
      value={draft}
      onChange={(next) => {
        setDraft(next);
        const parsed = schema.safeParse(next);
        if (parsed.success) change(parsed.data);
      }}
      onBlur={() => setDraft(value)}
    />
  );
}

export function SettingsDialog({ opened, close, controller, state }: SettingsProps) {
  const settings = state.settings;
  return (
    <Modal opened={opened} onClose={close} title="Workspace settings" size="md" centered>
      <Stack gap="lg">
        <Text size="sm" c="dimmed">
          Settings are saved in this browser.
        </Text>
        <Group grow>
          <IntegerSetting
            label="Font size"
            suffix=" px"
            schema={playgroundSettingsSchema.shape.fontSize}
            value={settings.fontSize}
            change={(fontSize) => controller.configure({ fontSize })}
          />
          <Select
            label="Indentation"
            data={[2, 4, 8].map((value) => ({ value: String(value), label: `${value} spaces` }))}
            value={String(settings.tabSize)}
            onChange={(value) => {
              if (value) controller.configure({ tabSize: Number(value) });
            }}
          />
        </Group>
        <Switch
          label="Wrap long lines"
          checked={settings.wordWrap}
          onChange={(event) => controller.configure({ wordWrap: event.currentTarget.checked })}
        />
        <Switch
          label="Show minimap"
          checked={settings.minimap}
          onChange={(event) => controller.configure({ minimap: event.currentTarget.checked })}
        />
        <Switch
          label="Show line numbers"
          checked={settings.lineNumbers}
          onChange={(event) => controller.configure({ lineNumbers: event.currentTarget.checked })}
        />
        <Switch
          label="Show inlay hints"
          checked={settings.inlayHints}
          onChange={(event) => controller.configure({ inlayHints: event.currentTarget.checked })}
        />
        <Switch
          label="Semantic highlighting"
          checked={settings.semanticHighlighting}
          onChange={(event) => controller.configure({ semanticHighlighting: event.currentTarget.checked })}
        />
        <Switch
          label="Run after editing"
          description="Runs one second after you stop typing."
          checked={settings.autoRun}
          onChange={(event) => controller.configure({ autoRun: event.currentTarget.checked })}
        />
        <IntegerSetting
          label="Execution time limit"
          description="Stops long-running programs without blocking the editor."
          suffix=" seconds"
          schema={playgroundSettingsSchema.shape.timeout}
          value={settings.timeout}
          change={(timeout) => controller.configure({ timeout })}
        />
        <TextInput
          label="Entry point"
          description="The value or function name your runtime will evaluate."
          value={state.session.entryPoint}
          maxLength={200}
          onChange={(event) => controller.updateSession({ ...state.session, entryPoint: event.currentTarget.value })}
        />
        <Select
          label="Entry file"
          description="All workspace files are compiled; execution starts in this file."
          data={state.session.workspace.files.map((file) => ({ value: file.id, label: file.path }))}
          value={state.session.entryFileId}
          disabled={state.session.workspace.files.length === 0}
          onChange={(entryFileId) => {
            if (entryFileId) controller.updateSession({ ...state.session, entryFileId });
          }}
        />
      </Stack>
    </Modal>
  );
}

export function CapabilitiesDialog({
  opened,
  close,
  state,
  restart,
}: {
  opened: boolean;
  close: () => void;
  state: WorkbenchState;
  restart: () => void;
}) {
  const capabilities = state.service.kind === 'ready' ? state.service.service.capabilities : {};
  const labels: { key: keyof LanguageCapabilities; label: string }[] = [
    { key: 'hover', label: 'Hover information' },
    { key: 'definitions', label: 'Go to definition' },
    { key: 'references', label: 'Find references' },
    { key: 'symbols', label: 'Document symbols' },
    { key: 'completions', label: 'Semantic completion' },
    { key: 'inlayHints', label: 'Inlay hints' },
    { key: 'semanticTokens', label: 'Semantic highlighting' },
    { key: 'codeActions', label: 'Code actions and quick fixes' },
    { key: 'signature', label: 'Signature help' },
    { key: 'folding', label: 'Semantic folding' },
    { key: 'format', label: 'Format document' },
    { key: 'rename', label: 'Rename symbol' },
  ];
  return (
    <Modal opened={opened} onClose={close} title="Language services" size="md" centered>
      <Text size="sm" c="dimmed" mb="lg">
        These features depend on the connected language adapter. Editing, search, snippets, and execution work
        independently.
      </Text>
      <Stack gap="sm">
        {labels.map((feature) => (
          <Group key={feature.key} justify="space-between">
            <Text size="sm">{feature.label}</Text>
            <Badge color={capabilities[feature.key] ? 'success' : 'neutral'} variant="light">
              {capabilities[feature.key] ? 'Available' : 'Not provided'}
            </Badge>
          </Group>
        ))}
      </Stack>
      {state.service.kind === 'failed' && (
        <Text c="error" size="sm" mt="lg">
          {state.service.message}
        </Text>
      )}
      <Button mt="lg" variant="light" onClick={restart}>
        Restart language service
      </Button>
    </Modal>
  );
}

export function HelpDialog({
  opened,
  close,
  commands,
}: {
  opened: boolean;
  close: () => void;
  commands: readonly Command[];
}) {
  return (
    <Modal opened={opened} onClose={close} title="Your keyboard, supercharged" size="md" centered>
      <Text size="sm" c="dimmed" mb="lg">
        Use ⌘ instead of Ctrl on macOS. Monaco’s editor menu includes more commands.
      </Text>
      <div className="shortcut-list">
        {commands
          .filter((command) => command.bindings?.length)
          .map((command) => (
            <div key={command.id}>
              <span>{command.label}</span>
              <kbd>{shortcutLabel(command.bindings?.[0])}</kbd>
            </div>
          ))}
      </div>
      <Text size="sm" c="dimmed" mt="lg">
        Also try Alt+Click for multiple cursors, Ctrl+D to select the next match, and Alt+↑/↓ to move a line.
      </Text>
    </Modal>
  );
}

export interface Replacement {
  session: Session;
  title: string;
}
export function ReplaceDialog({
  replacement,
  close,
  confirm,
  change,
}: {
  replacement: Replacement | null;
  close: () => void;
  confirm: () => void;
  change: (session: Session) => void;
}) {
  return (
    <Modal
      opened={replacement !== null}
      onClose={close}
      title={replacement?.title ?? 'Replace source'}
      size="sm"
      centered
    >
      <Text size="sm" c="dimmed">
        This updates the workspace files. A checkpoint of the current workspace is kept in local history first.
      </Text>
      {replacement && (
        <>
          <Text size="sm" mt="sm">
            {replacement.session.workspace.files.length} files · {replacement.session.workspace.directories.length}{' '}
            folders
          </Text>
          <Select
            mt="md"
            label="Entry file"
            searchable
            data={replacement.session.workspace.files.map((file) => ({ value: file.id, label: file.path }))}
            value={replacement.session.entryFileId}
            disabled={!replacement.session.workspace.files.length}
            onChange={(id) => {
              if (id)
                change({
                  ...replacement.session,
                  entryFileId: id,
                  activeFileId: id,
                  openFileIds: [...new Set([...replacement.session.openFileIds, id])],
                });
            }}
          />
        </>
      )}
      <Group justify="flex-end" mt="lg">
        <Button variant="default" onClick={close}>
          Cancel
        </Button>
        <Button onClick={confirm}>Replace source</Button>
      </Group>
    </Modal>
  );
}
