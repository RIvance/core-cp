import { forwardRef, useEffect, useImperativeHandle, useRef, useState } from 'react';
import { ActionIcon, Combobox, Group, Tooltip, useCombobox } from '@mantine/core';
import { IconCheck, IconPalette } from '@tabler/icons-react';
import type { PlaygroundTheme } from '../appearance/themes';

interface ThemePickerProps {
  themes: readonly PlaygroundTheme[];
  selected: PlaygroundTheme;
  onSelect: (id: string) => void;
}

export interface ThemePickerHandle {
  /** Focus the header control and open its theme list, including when invoked from another popup. */
  open(): void;
}

/** Header theme control. Searching only filters choices; selecting a choice applies the palette. */
export const ThemePicker = forwardRef<ThemePickerHandle, ThemePickerProps>(function ThemePicker(
  { themes, selected, onSelect },
  ref,
) {
  const button = useRef<HTMLButtonElement>(null);
  const [query, setQuery] = useState('');
  const combobox = useCombobox({
    onDropdownClose: (source) => {
      combobox.resetSelectedOption();
      setQuery('');
      if (source === 'keyboard') combobox.focusTarget();
    },
  });
  useImperativeHandle(ref, () => ({
    open() {
      // The popup returns focus to its opener, even when the command palette initiated the action.
      button.current?.focus();
      combobox.openDropdown('keyboard');
    },
  }));
  const opened = combobox.dropdownOpened;
  const { focusSearchInput } = combobox;
  useEffect(() => {
    if (opened) focusSearchInput();
  }, [opened, focusSearchInput]);
  const matches = themes.filter((theme) => theme.label.toLowerCase().includes(query.trim().toLowerCase()));
  return (
    <Combobox
      store={combobox}
      position="bottom-end"
      width={280}
      onOptionSubmit={(id) => {
        onSelect(id);
        combobox.closeDropdown();
        combobox.focusTarget();
      }}
    >
      <Combobox.Target targetType="button">
        <ActionIcon
          ref={button}
          variant="subtle"
          color="neutral"
          aria-label="Color theme"
          aria-description={`Current theme: ${selected.label}`}
          aria-expanded={opened}
          onClick={() => combobox.toggleDropdown('mouse')}
        >
          <IconPalette size={19} />
        </ActionIcon>
      </Combobox.Target>
      <Tooltip target={button} label={`Color theme: ${selected.label}`} disabled={opened} />
      <Combobox.Dropdown className="theme-picker-dropdown">
        <Combobox.Search
          aria-label="Search color themes"
          role="combobox"
          aria-expanded={opened}
          placeholder="Search color themes"
          value={query}
          onChange={(event) => {
            setQuery(event.currentTarget.value);
            combobox.resetSelectedOption();
          }}
        />
        <Combobox.Options className="theme-picker-options" aria-label="Color themes">
          {matches.map((theme) => (
            <Combobox.Option
              key={theme.id}
              value={theme.id}
              active={theme.id === selected.id}
              aria-description={theme.id === selected.id ? 'Current theme' : undefined}
            >
              <Group gap="sm" wrap="nowrap">
                <span
                  className="theme-swatch"
                  aria-hidden="true"
                  style={{
                    backgroundColor: theme.colors.surface,
                    color: theme.syntax.keyword,
                    borderColor: theme.colors.border,
                  }}
                >
                  Aa
                </span>
                <span className="theme-picker-label">{theme.label}</span>
                {theme.id === selected.id && <IconCheck size={15} aria-hidden="true" />}
              </Group>
            </Combobox.Option>
          ))}
          {matches.length === 0 && <Combobox.Empty>No matching themes</Combobox.Empty>}
        </Combobox.Options>
      </Combobox.Dropdown>
    </Combobox>
  );
});
