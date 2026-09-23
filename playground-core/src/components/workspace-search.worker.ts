import { Workspace, type WorkspaceSnapshot } from '../workspace/model';
import { searchWorkspace, type WorkspaceSearchOptions, type WorkspaceSearchResult } from '../workspace/search';

/** One request per worker. Terminating the worker cancels even a non-terminating regexp evaluation. */
export interface SearchRequest {
  readonly workspace: WorkspaceSnapshot;
  readonly query: string;
  readonly options: WorkspaceSearchOptions;
}
export type SearchReply =
  | { readonly kind: 'result'; readonly result: WorkspaceSearchResult }
  | { readonly kind: 'error'; readonly message: string };

self.onmessage = (event: MessageEvent<SearchRequest>) => {
  let reply: SearchReply;
  try {
    reply = {
      kind: 'result',
      result: searchWorkspace(Workspace.parse(event.data.workspace), event.data.query, event.data.options),
    };
  } catch (error) {
    reply = { kind: 'error', message: error instanceof Error ? error.message : String(error) };
  }
  self.postMessage(reply);
};
