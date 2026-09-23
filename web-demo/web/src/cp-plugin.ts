import type { ExecutionInput, LanguagePlugin } from "@language-playground/ide/api";
import { LspLanguageService } from "@language-playground/ide/lsp";
import { MessageConnection, WorkerRuntime } from "@language-playground/ide/transport";
import { examples } from "./examples";

/** Execution and visualization own separate workers so stepping never waits for direct evaluation. */
export function createCpPlugin(onRun: (input: ExecutionInput, signal: AbortSignal) => void): LanguagePlugin {
  return {
    definition: {
      id: "cp",
      name: "CP",
      extension: ".cp",
      defaultFilePath: "Main.cp",
      defaultEntryPoint: "main",
      examples
    },
    createLanguageService({ workspaceUri }) {
      return new LspLanguageService(new MessageConnection(new Worker(
        new URL("./language-server.worker.ts", import.meta.url), { type: "module" }
      )), workspaceUri);
    },
    createRuntime() {
      const runtime = new WorkerRuntime(() => new Worker(new URL("./runtime.worker.ts", import.meta.url), {
        type: "module"
      }));
      return {
        execute(input, emit, signal) {
          onRun(input, signal);
          return runtime.execute(input, emit, signal);
        }
      };
    }
  };
}
