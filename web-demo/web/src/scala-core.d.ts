declare module "scala-core" {
  export class CpTrieWorkbench {
    compile(
      files: readonly import("./model").BrowserSourceFile[], entryFile: string
    ): import("./model").CompilationResult;
    evaluate(): import("./model").FiobsEvaluationResult;
    start(): import("./model").WorkbenchResult;
    step(): import("./model").WorkbenchResult;
    restart(): import("./model").WorkbenchResult;
  }
}
