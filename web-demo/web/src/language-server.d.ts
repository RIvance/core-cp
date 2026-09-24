declare module "@core-cp/language-server/browser" {
  /** Starts the Scala.js LSP session in the current worker and returns a disposer. */
  export function startBrowserLanguageServer(): () => void;
}
