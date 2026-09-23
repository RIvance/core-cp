import { mountPlayground } from "@language-playground/ide";
import "@language-playground/ide/styles.css";
import "./style.css";
import { cpLanguage } from "./cp-language";
import { createCpPlugin } from "./cp-plugin";
import { TrieSession } from "./trie-session";
import { TrieExplorer } from "./trie-explorer";

const session = new TrieSession(() => new Worker(new URL("./trie.worker.ts", import.meta.url), { type: "module" }));
const playground = mountPlayground(document.getElementById("playground")!, {
  plugin: createCpPlugin((input, signal) => { void session.load(input, signal); }),
  editor: cpLanguage,
  title: "FiTrie Observatory",
  documentTitle: "CP · FiTrie Observatory"
});
const explorer = new TrieExplorer(document.getElementById("trie-explorer")!, session);

// Vite replaces the application without leaving workers, graph canvases or global listeners behind.
if (import.meta.hot) {
  import.meta.hot.dispose(() => {
    playground.dispose();
    explorer.dispose();
    session.dispose();
  });
}
