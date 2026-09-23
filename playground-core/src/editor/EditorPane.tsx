import { forwardRef, useEffect, useImperativeHandle, useRef } from 'react';
import type { EditorLanguage } from './monaco';
import { EditorWorkspace, type EditorHandle } from './workspace';
import type { PlaygroundController } from '../core/controller';

interface EditorProps {
  language: EditorLanguage;
  controller: PlaygroundController;
  onPosition: (line: number, column: number, selected: number) => void;
  onError: (error: unknown) => void;
  id: string;
  onReady?: (editor: EditorHandle) => void;
}
export const EditorPane = forwardRef<EditorHandle, EditorProps>(function EditorPane(
  { controller, language, onPosition, onError, id, onReady },
  ref,
) {
  const container = useRef<HTMLDivElement>(null);
  const workspace = useRef<EditorWorkspace | null>(null);
  const callbacks = useRef({ onPosition, onError, onReady });
  useEffect(() => {
    callbacks.current = { onPosition, onError, onReady };
  }, [onPosition, onError, onReady]);
  useImperativeHandle(
    ref,
    () => ({
      async action(identifier) {
        await workspace.current?.action(identifier);
      },
      reveal: (location) => workspace.current?.reveal(location),
      focus: () => workspace.current?.focus(),
      getEditor: () => workspace.current?.getEditor() ?? null,
    }),
    [],
  );
  useEffect(() => {
    if (!container.current) return;
    const editor = new EditorWorkspace(
      container.current,
      language,
      controller,
      (line, column, selected) => callbacks.current.onPosition(line, column, selected),
      (error) => callbacks.current.onError(error),
    );
    workspace.current = editor;
    callbacks.current.onReady?.(editor);
    return () => {
      editor.dispose();
      workspace.current = null;
    };
  }, [controller, language]);
  return <div id={id} className="editor-surface" ref={container} data-testid="source-editor" />;
});
