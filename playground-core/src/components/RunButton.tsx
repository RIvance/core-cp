import type { ReactNode } from 'react';
import { Button } from '@mantine/core';
import { IconPlayerPlayFilled, IconPlayerStopFilled } from '@tabler/icons-react';
import type { PlaygroundState } from '../core/controller';

/** Inputs for the shared run/stop control. The host handles errors and keyboard bindings. */
export interface RunButtonProps {
  /** Execution state chooses Run or Stop; an absent entry file disables Run. */
  readonly state: PlaygroundState;
  /** Start execution. The host may also select its output panel here. */
  readonly run: () => void;
  /** Cancel the current execution. */
  readonly stop: () => void;
}

/** The same execution control is used by the full application and embedded editors. */
export function RunButton({ state, run, stop }: RunButtonProps): ReactNode {
  return state.execution.kind === 'running' ? (
    <Button size="xs" color="error" leftSection={<IconPlayerStopFilled size={13} />} onClick={stop}>
      Stop
    </Button>
  ) : (
    <Button
      size="xs"
      disabled={!state.session.entryFileId}
      className="run-button"
      leftSection={<IconPlayerPlayFilled size={13} />}
      onClick={run}
    >
      Run <kbd>Ctrl ↵</kbd>
    </Button>
  );
}
