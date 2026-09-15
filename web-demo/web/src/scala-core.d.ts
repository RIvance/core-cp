declare module "scala-core" {
  export class CpTrieWorkbench {
    compile(source: string, fileName: string): import("./model").WorkbenchResult;
    step(): import("./model").WorkbenchResult;
    restart(): import("./model").WorkbenchResult;
  }
}
