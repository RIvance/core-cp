import * as Lsp from 'vscode-languageserver-protocol';
import type { Diagnostic } from '../../core/contracts';

export function diagnostic(value: Lsp.Diagnostic, uri: string): Diagnostic {
  return {
    message: typeof value.message === 'string' ? value.message : value.message.value,
    severity:
      value.severity === Lsp.DiagnosticSeverity.Warning
        ? 'warning'
        : value.severity === Lsp.DiagnosticSeverity.Information
          ? 'info'
          : value.severity === Lsp.DiagnosticSeverity.Hint
            ? 'hint'
            : 'error',
    location: { uri, range: value.range },
    source: value.source,
    code: value.code,
    data: value.data,
  };
}

export function protocolDiagnostic(value: Diagnostic, uri: string): Lsp.Diagnostic | undefined {
  if (value.location?.uri !== uri) return undefined;
  return {
    range: value.location.range,
    message: value.message,
    source: value.source,
    code: value.code,
    data: value.data,
    severity:
      value.severity === 'warning'
        ? Lsp.DiagnosticSeverity.Warning
        : value.severity === 'info'
          ? Lsp.DiagnosticSeverity.Information
          : value.severity === 'hint'
            ? Lsp.DiagnosticSeverity.Hint
            : Lsp.DiagnosticSeverity.Error,
  };
}
