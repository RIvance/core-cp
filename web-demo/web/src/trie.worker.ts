import { executionInputSchema, RpcError, serveJsonRpc } from "@language-playground/ide/transport";
import { CpTrieWorkbench } from "scala-core";
import { compileWorkspace } from "./cp-runtime";

serveJsonRpc(self, (emit) => {
  const workbench = new CpTrieWorkbench();
  return {
    receive(message) {
      if (!("method" in message) || !("id" in message)) return;
      let result;
      switch (message.method) {
        case "trie/start": {
          const input = executionInputSchema.parse(message.params);
          const compiled = compileWorkspace(workbench, input);
          result = compiled.ok ? workbench.start() : compiled;
          break;
        }
        case "trie/step": result = workbench.step(); break;
        case "trie/restart": result = workbench.restart(); break;
        default: throw new RpcError(`Unknown trie operation: ${message.method}`, -32601);
      }
      emit({ jsonrpc: "2.0", id: message.id, result });
    }
  };
});
