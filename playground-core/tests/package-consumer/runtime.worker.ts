import { serveExecution } from '@language-playground/ide/transport';

// The consumer owns its runtime. This test language returns the entry document unchanged.
serveExecution(self, {
  execute(input, emit) {
    const entry = input.documents.find((document) => document.uri === input.entryDocumentUri);
    if (!entry) throw new Error('The entry document is missing.');
    emit({ channel: 'stdout', text: input.stdin });
    return { status: 'success', value: entry.text, diagnostics: [], artifacts: [] };
  },
});
