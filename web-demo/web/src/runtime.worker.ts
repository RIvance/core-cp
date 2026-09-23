import { serveExecution } from "@language-playground/ide/transport";
import { CpTrieWorkbench } from "scala-core";
import { executeWorkspace } from "./cp-runtime";

serveExecution(self, {
  execute: (input) => executeWorkspace(new CpTrieWorkbench(), input)
});
