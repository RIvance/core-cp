import type {
  CodeAction,
  Diagnostic,
  InlayHint,
  LanguageCapabilities,
  LanguageCommand,
  Range,
} from '../core/contracts';
import type { LanguageRequests, RequestSnapshot } from './language-requests';
import { monaco } from './monaco';
import { encodeSemanticTokens } from './semantic-tokens';
import { projectWorkspaceEdits, type WorkspaceEditContext } from './workspace-edits';

/** @internal Snapshot lookups used while translating diagnostics and applying actions in the editor. */
export interface LanguageWorkspaceContext extends WorkspaceEditContext {
  /** Return original source diagnostics, preserving their codes and provider-owned data. */
  diagnostics(uri: string): readonly Diagnostic[];
}

function sourceRange(range: monaco.IRange): Range {
  return {
    start: { line: range.startLineNumber - 1, character: range.startColumn - 1 },
    end: { line: range.endLineNumber - 1, character: range.endColumn - 1 },
  };
}
function editorRange(range: Range): monaco.Range {
  return new monaco.Range(range.start.line + 1, range.start.character + 1, range.end.line + 1, range.end.character + 1);
}
function diagnosticMarker(value: Diagnostic, uri: string): monaco.editor.IMarkerData[] {
  if (value.location?.uri !== uri) return [];
  return [
    {
      ...editorRange(value.location.range),
      message: value.message,
      source: value.source,
      code: value.code === undefined ? undefined : String(value.code),
      severity:
        value.severity === 'error'
          ? monaco.MarkerSeverity.Error
          : value.severity === 'warning'
            ? monaco.MarkerSeverity.Warning
            : value.severity === 'info'
              ? monaco.MarkerSeverity.Info
              : monaco.MarkerSeverity.Hint,
    },
  ];
}
function changed(subscribe: ((listener: () => void) => () => void) | undefined): monaco.IEvent<void> | undefined {
  return subscribe
    ? (listener) => ({
        dispose: subscribe(() => {
          listener();
        }),
      })
    : undefined;
}

/**
 * @internal Register optional hint, semantic-token, and code-action providers for this model only.
 * Disposal removes provider registrations and their refresh subscriptions. The caller owns requests and its disposal.
 * Provider results are validated against the queried snapshot; failures are sent through the supplied request scope.
 */
export function attachSemanticFeatures(
  model: monaco.editor.ITextModel,
  capabilities: Readonly<LanguageCapabilities>,
  requests: LanguageRequests,
  workspace: LanguageWorkspaceContext,
): monaco.IDisposable {
  const registrations: monaco.IDisposable[] = [];
  const selector = { language: model.getLanguageId(), scheme: model.uri.scheme, pattern: model.uri.path };
  const commandId = `playground.languageAction.${crypto.randomUUID()}`;
  registrations.push(
    monaco.editor.registerCommand(
      commandId,
      (_accessor: unknown, command: LanguageCommand, snapshot?: RequestSnapshot) => {
        void requests.execute(command, snapshot);
      },
    ),
  );
  const command = (value: LanguageCommand, snapshot?: RequestSnapshot): monaco.languages.Command => ({
    id: commandId,
    title: value.title,
    arguments: [value, snapshot],
  });

  if (capabilities.inlayHints) {
    const provider = capabilities.inlayHints;
    const hints = new WeakMap<monaco.languages.InlayHint, { value: InlayHint; snapshot: RequestSnapshot }>();
    const project = (value: InlayHint, snapshot: RequestSnapshot): monaco.languages.InlayHint => {
      const position = new monaco.Position(value.position.line + 1, value.position.character + 1);
      if (
        ![value.position.line, value.position.character].every(
          (coordinate) => Number.isSafeInteger(coordinate) && coordinate >= 0,
        ) ||
        !position.equals(model.validatePosition(position)) ||
        (typeof value.label === 'string'
          ? !value.label.length
          : !value.label.length || value.label.some((part) => !part.value.length))
      )
        throw new Error('The language service supplied an invalid inlay hint.');
      const textEdits = workspace.canEdit(model.uri.toString()) ? value.textEdits : undefined;
      if (textEdits) {
        const edit = projectWorkspaceEdits([{ uri: model.uri.toString(), edits: textEdits }], workspace);
        if (edit.kind === 'rejected') throw edit.error;
      }
      const result: monaco.languages.InlayHint = {
        position,
        label:
          typeof value.label === 'string'
            ? value.label
            : value.label.map((part) => ({
                label: part.value,
                tooltip: part.tooltip,
                location: part.location && {
                  uri: monaco.Uri.parse(part.location.uri),
                  range: editorRange(part.location.range),
                },
                command: part.command && command(part.command, snapshot),
              })),
        kind:
          value.kind === 'parameter'
            ? monaco.languages.InlayHintKind.Parameter
            : value.kind === 'type'
              ? monaco.languages.InlayHintKind.Type
              : undefined,
        tooltip: value.tooltip,
        paddingLeft: value.paddingLeft,
        paddingRight: value.paddingRight,
        textEdits: textEdits?.map((edit) => ({ range: editorRange(edit.range), text: edit.text })),
      };
      hints.set(result, { value, snapshot });
      return result;
    };
    registrations.push(
      monaco.languages.registerInlayHintsProvider(selector, {
        onDidChangeInlayHints: changed(provider.onDidChange?.bind(provider)),
        async provideInlayHints(target, range, token) {
          const snapshot = requests.capture();
          return requests.run(
            target,
            token,
            async (document, signal) => ({
              hints: (await provider.provide(document, sourceRange(range), signal)).map((value) =>
                project(value, snapshot),
              ),
              dispose() {},
            }),
            snapshot,
          );
        },
        async resolveInlayHint(hint, token) {
          const source = hints.get(hint);
          if (!source?.value.resolve) return hint;
          const resolve = source.value.resolve;
          return (
            (await requests.run(
              model,
              token,
              async (_document, signal) => project(await resolve(signal), source.snapshot),
              source.snapshot,
            )) ?? hint
          );
        },
      }),
    );
  }

  if (capabilities.semanticTokens) {
    const provider = capabilities.semanticTokens;
    registrations.push(
      monaco.languages.registerDocumentSemanticTokensProvider(selector, {
        getLegend: () => ({
          tokenTypes: [...provider.legend.tokenTypes],
          tokenModifiers: [...provider.legend.tokenModifiers],
        }),
        onDidChange: changed(provider.onDidChange?.bind(provider)),
        async provideDocumentSemanticTokens(target, _lastResultId, token) {
          return requests.run(target, token, async (document, signal) => ({
            data: encodeSemanticTokens(await provider.provide(document, signal), provider.legend, target),
          }));
        },
        releaseDocumentSemanticTokens() {},
      }),
    );
  }

  if (capabilities.codeActions) {
    const provider = capabilities.codeActions;
    const actions = new WeakMap<monaco.languages.CodeAction, { value: CodeAction; snapshot: RequestSnapshot }>();
    const project = (value: CodeAction, snapshot: RequestSnapshot): monaco.languages.CodeAction => {
      const projected = value.edit && projectWorkspaceEdits(value.edit, workspace);
      const disabled =
        value.disabled ??
        (projected?.kind === 'rejected'
          ? projected.error.message
          : !value.edit?.some((change) => change.edits.length > 0) && !value.command && !value.resolve
            ? 'This action provides no edit or command.'
            : undefined);
      const result: monaco.languages.CodeAction = {
        title: value.title,
        kind: value.kind,
        isPreferred: value.isPreferred,
        disabled,
        diagnostics: value.diagnostics?.flatMap((item) => diagnosticMarker(item, model.uri.toString())),
        edit: !disabled && projected?.kind === 'ready' ? projected.edit : undefined,
        // An edit may change the revision before its following command is invoked.
        command:
          !disabled && value.command ? command(value.command, value.edit?.length ? undefined : snapshot) : undefined,
      };
      actions.set(result, { value, snapshot });
      return result;
    };
    registrations.push(
      monaco.languages.registerCodeActionProvider(
        selector,
        {
          async provideCodeActions(target, range, context, token) {
            const snapshot = requests.capture();
            const values = await requests.run(
              target,
              token,
              (document, signal) =>
                provider.provide(
                  document,
                  sourceRange(range),
                  {
                    diagnostics: workspace
                      .diagnostics(document.uri)
                      .filter(
                        (value) =>
                          value.location?.uri === document.uri &&
                          monaco.Range.areIntersectingOrTouching(editorRange(value.location.range), range),
                      ),
                    only: context.only ? [context.only] : undefined,
                    trigger: context.trigger === monaco.languages.CodeActionTriggerType.Auto ? 'automatic' : 'invoked',
                  },
                  signal,
                ),
              snapshot,
            );
            return { actions: (values ?? []).map((value) => project(value, snapshot)), dispose() {} };
          },
          async resolveCodeAction(action, token) {
            const source = actions.get(action);
            if (!source) return action;
            if (!source.snapshot.isCurrent())
              return { title: action.title, disabled: 'The source changed. Request this action again.' };
            const resolve = source.value.resolve;
            if (!resolve) return action;
            const value = await requests.run(model, token, (_document, signal) => resolve(signal), source.snapshot);
            return value
              ? project(value, source.snapshot)
              : { title: action.title, disabled: 'This action is no longer available.' };
          },
        },
        { providedCodeActionKinds: provider.kinds ? [...provider.kinds] : undefined },
      ),
    );
  }

  return { dispose: () => registrations.forEach((registration) => registration.dispose()) };
}
