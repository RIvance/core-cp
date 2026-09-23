import type { LanguageCommand, SourceDocument } from '../core/contracts';
import type { monaco } from './monaco';

/** @internal Captured model/workspace revision, invalidated by edits, closure, or provider disposal. */
export interface RequestSnapshot {
  isCurrent(): boolean;
}

/** @internal Cancels requests on disposal and filters results computed for a superseded model/workspace revision. */
export class LanguageRequests {
  private readonly pending = new Set<AbortController>();
  private disposed = false;
  constructor(
    private readonly model: monaco.editor.ITextModel,
    private readonly document: () => SourceDocument,
    private readonly workspaceVersion: () => number,
    private readonly reportError: (error: unknown) => void,
  ) {}

  /** Capture before starting a provider request; retain the result for any lazy resolution or command. */
  capture(): RequestSnapshot {
    const version = this.model.getVersionId();
    const workspace = this.workspaceVersion();
    return {
      isCurrent: () =>
        !this.disposed &&
        !this.model.isDisposed() &&
        this.model.getVersionId() === version &&
        this.workspaceVersion() === workspace,
    };
  }

  /** Return undefined for cancellation/stale results. Report other failures once and remove the request listener. */
  async run<T>(
    target: monaco.editor.ITextModel,
    token: monaco.CancellationToken,
    operation: (document: SourceDocument, signal: AbortSignal) => Promise<T>,
    snapshot = this.capture(),
  ): Promise<T | undefined> {
    if (target !== this.model || token.isCancellationRequested || !snapshot.isCurrent()) return undefined;
    const cancellation = new AbortController();
    this.pending.add(cancellation);
    const listener = token.onCancellationRequested(() => cancellation.abort());
    try {
      const result = await operation(this.document(), cancellation.signal);
      return !cancellation.signal.aborted && snapshot.isCurrent() ? result : undefined;
    } catch (error) {
      if (!cancellation.signal.aborted && snapshot.isCurrent()) this.reportError(error);
      return undefined;
    } finally {
      listener.dispose();
      this.pending.delete(cancellation);
    }
  }

  /** Execute only a current command; omit snapshot only after the action has applied its own versioned edits. */
  async execute(command: LanguageCommand, snapshot?: RequestSnapshot): Promise<void> {
    if (this.disposed || (snapshot && !snapshot.isCurrent())) return;
    const cancellation = new AbortController();
    this.pending.add(cancellation);
    try {
      await command.execute(cancellation.signal);
    } catch (error) {
      if (!cancellation.signal.aborted && !this.disposed) this.reportError(error);
    } finally {
      this.pending.delete(cancellation);
    }
  }

  /** Abort active operations and permanently invalidate every snapshot captured by this instance. */
  dispose(): void {
    this.disposed = true;
    for (const cancellation of this.pending) cancellation.abort();
    this.pending.clear();
  }
}
