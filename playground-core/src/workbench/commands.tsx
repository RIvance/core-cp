import { useEffect, useRef, useState } from 'react';
import { Kbd, Modal, TextInput } from '@mantine/core';
import { IconSearch } from '@tabler/icons-react';

export interface KeyBinding {
  readonly key: string;
  readonly controlOrMeta?: boolean;
  readonly shift?: boolean;
  readonly alt?: boolean;
  readonly scope: 'workspace' | 'editor';
}
export interface Command {
  id: string;
  label: string;
  category: string;
  bindings?: readonly KeyBinding[];
  unavailable?: string;
  execute: () => void | Promise<void>;
}

export function shortcutLabel(binding: KeyBinding | undefined): string {
  if (!binding) return '';
  return [binding.controlOrMeta && 'Ctrl', binding.shift && 'Shift', binding.alt && 'Alt', binding.key]
    .filter(Boolean)
    .join('+');
}

export function useCommandShortcuts(
  commands: readonly Command[],
  execute: (command: Command) => void,
  host: HTMLElement,
): void {
  useEffect(() => {
    const handle = (event: KeyboardEvent) => {
      const eventHost = event.target instanceof Element ? event.target.closest('.language-playground') : null;
      if (eventHost && eventHost !== host) return;
      const command = commands.find((candidate) =>
        candidate.bindings?.some(
          (binding) =>
            binding.scope === 'workspace' &&
            event.key.toLowerCase() === binding.key.toLowerCase() &&
            (event.ctrlKey || event.metaKey) === Boolean(binding.controlOrMeta) &&
            event.shiftKey === Boolean(binding.shift) &&
            event.altKey === Boolean(binding.alt),
        ),
      );
      if (!command) return;
      event.preventDefault();
      event.stopPropagation();
      execute(command);
    };
    window.addEventListener('keydown', handle, true);
    return () => window.removeEventListener('keydown', handle, true);
  }, [commands, execute, host]);
}
interface PaletteProps {
  opened: boolean;
  close: () => void;
  commands: readonly Command[];
  execute: (command: Command) => void;
}
export function CommandPalette({ opened, close, commands, execute }: PaletteProps) {
  const options = useRef<HTMLDivElement>(null);
  const [query, setQuery] = useState('');
  const [selected, setSelected] = useState(0);
  const matches = commands.filter((command) =>
    `${command.category} ${command.label}`.toLowerCase().includes(query.toLowerCase()),
  );
  useEffect(() => {
    if (opened) {
      setQuery('');
      setSelected(0);
    }
  }, [opened]);
  useEffect(() => {
    options.current?.querySelector('[aria-selected="true"]')?.scrollIntoView({ block: 'nearest' });
  }, [selected, query, opened]);
  return (
    <Modal opened={opened} onClose={close} title="Command palette" size="lg" padding="md" centered>
      <TextInput
        autoFocus
        placeholder="What would you like to do?"
        aria-label="Search commands"
        leftSection={<IconSearch size={18} />}
        value={query}
        onChange={(event) => {
          setQuery(event.currentTarget.value);
          setSelected(0);
        }}
        role="combobox"
        aria-expanded
        aria-controls="command-options"
        aria-activedescendant={matches[selected] ? `command-${matches[selected].id}` : undefined}
        onKeyDown={(event) => {
          if (event.key === 'ArrowDown') {
            event.preventDefault();
            setSelected((value) => Math.min(matches.length - 1, value + 1));
          }
          if (event.key === 'ArrowUp') {
            event.preventDefault();
            setSelected((value) => Math.max(0, value - 1));
          }
          if (event.key === 'Enter') {
            event.preventDefault();
            const command = matches[selected];
            if (command && !command.unavailable) {
              close();
              execute(command);
            }
          }
        }}
      />
      <div ref={options} className="command-list" role="listbox" id="command-options" aria-label="Commands">
        {matches.map((command, index) => (
          <button
            key={command.id}
            id={`command-${command.id}`}
            role="option"
            aria-selected={selected === index}
            aria-disabled={Boolean(command.unavailable)}
            title={command.unavailable}
            className={`command-option ${index === selected ? 'selected' : ''}`}
            onMouseEnter={() => setSelected(index)}
            onClick={() => {
              if (!command.unavailable) {
                close();
                execute(command);
              }
            }}
          >
            <span>
              <small>{command.category}</small>
              {command.label}
              {command.unavailable && <em>{command.unavailable}</em>}
            </span>
            {command.bindings?.[0] && <Kbd>{shortcutLabel(command.bindings[0])}</Kbd>}
          </button>
        ))}
        {!matches.length && <div className="empty-state">No matching commands.</div>}
      </div>
    </Modal>
  );
}
