import { Component, type ErrorInfo, type ReactNode } from 'react';
import { errorMessage } from '../core/controller';

/** Keeps rendering failures within the owning mount and reports them through the host's error channel. */
export class RenderBoundary extends Component<
  {
    children: ReactNode;
    onError?: (error: unknown) => void;
  },
  { message?: string }
> {
  state: { message?: string } = {};
  static getDerivedStateFromError(error: unknown): { message: string } {
    return { message: errorMessage(error) };
  }
  componentDidCatch(error: Error, info: ErrorInfo): void {
    if (this.props.onError) this.props.onError(error);
    else console.error('Playground rendering failed', error, info.componentStack);
  }
  render(): ReactNode {
    return this.state.message ? (
      <div className="application-error" role="alert">
        <h2>The workspace could not be displayed.</h2>
        <p>{this.state.message}</p>
      </div>
    ) : (
      this.props.children
    );
  }
}
