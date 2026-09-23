import type { EditorLanguage } from "@language-playground/ide/api";

export const cpLanguage: EditorLanguage = {
  install(monaco, { id }) {
    monaco.languages.register({ id });
    const configuration = monaco.languages.setLanguageConfiguration(id, {
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
    const tokens = monaco.languages.setMonarchTokensProvider(id, {
      keywords: [
        "and", "def", "else", "extends", "false", "fold", "forall", "from", "if", "impl",
        "import", "implements", "in", "inherits", "interface", "let", "module", "mu", "new", "open",
        "override", "rec", "self", "super", "then", "top", "trait", "true", "type",
        "unfold", "where", "with"
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
          [/[λΛ∀μ⊤⊥]/, "keyword"],
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
          [/\\(?:["'\\bfnrt]|u[0-9a-fA-F]{4})/, "string.escape"],
          [/\\./, "string.escape.invalid"],
          [/"/, { token: "string.quote", bracket: "@close", next: "@pop" }]
        ]
      }
    });
    return { dispose() { configuration.dispose(); tokens.dispose(); } };
  }
};
