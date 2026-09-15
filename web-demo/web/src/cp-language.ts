import type * as Monaco from "monaco-editor/esm/vs/editor/editor.api.js";

export function registerCpLanguage(monaco: typeof Monaco): void {
  monaco.languages.register({ id: "cp" });
  monaco.languages.setLanguageConfiguration("cp", {
    comments: { lineComment: "//", blockComment: ["/*", "*/"] },
    brackets: [
      ["{", "}"],
      ["[", "]"],
      ["(", ")"]
    ],
    autoClosingPairs: [
      { open: "{", close: "}" },
      { open: "[", close: "]" },
      { open: "(", close: ")" },
      { open: '"', close: '"' }
    ]
  });
  monaco.languages.setMonarchTokensProvider("cp", {
    keywords: [
      "and", "def", "else", "extends", "false", "forall", "from", "if", "impl",
      "import", "implements", "in", "inherits", "let", "module", "new", "open",
      "override", "rec", "self", "super", "then", "top", "trait", "true", "type",
      "where", "with"
    ],
    typeKeywords: ["Int", "Decimal", "Bool", "String", "Unit", "Top", "Bottom"],
    operators: [
      "=", ">", "<", "!", "~", "?", ":", "==", "<=", ">=", "!=", "&&", "||",
      "+", "-", "*", "/", "%", "++", "->", "&", ",,", "@", "*"
    ],
    tokenizer: {
      root: [
        [/\b[A-Z][\w$]*\b/, { cases: { "@typeKeywords": "type.keyword", "@default": "type.identifier" } }],
        [/[a-z_$][\w$]*/, { cases: { "@keywords": "keyword", "@default": "identifier" } }],
        [/\d+\.\d+/, "number.float"],
        [/\d+/, "number"],
        [/"([^"\\]|\\.)*$/, "string.invalid"],
        [/"/, { token: "string.quote", bracket: "@open", next: "@string" }],
        [/\/\*/, "comment", "@comment"],
        [/\/\/.*$/, "comment"],
        [/--.*$/, "comment"],
        [/[{}()[\]]/, "@brackets"],
        [/[;,.]/, "delimiter"],
        [/[λΛ∀⊤⊥]/, "keyword"],
        [/[=!<>?:&|+\-*\/%@]+/, { cases: { "@operators": "operator", "@default": "" } }]
      ],
      comment: [
        [/[^/*]+/, "comment"],
        [/\/\*/, "comment", "@push"],
        [/\*\//, "comment", "@pop"],
        [/[/*]/, "comment"]
      ],
      string: [
        [/[^\\"]+/, "string"],
        [/\\./, "string.escape.invalid"],
        [/"/, { token: "string.quote", bracket: "@close", next: "@pop" }]
      ]
    }
  });
}
