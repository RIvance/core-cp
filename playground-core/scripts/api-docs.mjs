import { readFile, writeFile } from 'node:fs/promises';
import { fileURLToPath } from 'node:url';
import prettier from 'prettier';
import ts from 'typescript';

const root = new URL('../', import.meta.url);
const documentUrl = new URL('docs/playground-language-integration.md', root);
const sources = [
  'src/mount.tsx',
  'src/embedded/mount.tsx',
  'src/embedded/api.ts',
  'src/appearance/page-theme.ts',
  'src/appearance/ThemeProvider.tsx',
  'src/core/controller.ts',
  'src/core/session.ts',
  'src/core/settings.ts',
  'src/core/diagnostics.ts',
  'src/components/PlaygroundSurface.tsx',
  'src/components/RunButton.tsx',
  'src/components/WorkspaceSearchPanel.tsx',
  'src/components/ResultPanel.tsx',
  'src/editor/workspace.ts',
  'src/appearance/themes.ts',
  'src/core/contracts.ts',
  'src/workspace/path.ts',
  'src/workspace/model.ts',
  'src/workspace/search.ts',
  'src/workspace/archives.ts',
  'src/workspace/examples.ts',
  'src/editor/monaco.ts',
  'src/transport/rpc.ts',
  'src/transport/worker-runtime.ts',
  'src/transport/worker-host.ts',
  'src/adapters/lsp/service.ts',
];
const begin = '<!-- api-definitions:start -->';
const end = '<!-- api-definitions:end -->';
const factory = ts.factory;
const printer = ts.createPrinter({ removeComments: true, newLine: ts.NewLineKind.LineFeed });
const exported = (node) => node.modifiers?.some((modifier) => modifier.kind === ts.SyntaxKind.ExportKeyword);
const hidden = (node) =>
  node.modifiers?.some(
    (modifier) => modifier.kind === ts.SyntaxKind.PrivateKeyword || modifier.kind === ts.SyntaxKind.ProtectedKeyword,
  );
const ambient = [
  factory.createModifier(ts.SyntaxKind.ExportKeyword),
  factory.createModifier(ts.SyntaxKind.DeclareKeyword),
];
const parameters = (values) =>
  values.map((parameter) =>
    factory.updateParameterDeclaration(
      parameter,
      undefined,
      parameter.dotDotDotToken,
      parameter.name,
      parameter.questionToken ?? (parameter.initializer ? factory.createToken(ts.SyntaxKind.QuestionToken) : undefined),
      parameter.type,
      undefined,
    ),
  );

function declaration(node) {
  if (ts.isInterfaceDeclaration(node) || ts.isTypeAliasDeclaration(node)) return node;
  if (ts.isVariableStatement(node))
    return factory.updateVariableStatement(
      node,
      ambient,
      factory.updateVariableDeclarationList(
        node.declarationList,
        node.declarationList.declarations.map((value) => {
          if (!value.type) throw new Error('Public API constants must declare their type.');
          return factory.updateVariableDeclaration(value, value.name, value.exclamationToken, value.type, undefined);
        }),
      ),
    );
  if (ts.isFunctionDeclaration(node))
    return factory.updateFunctionDeclaration(
      node,
      ambient,
      node.asteriskToken,
      node.name,
      node.typeParameters,
      parameters(node.parameters),
      node.type,
      undefined,
    );
  if (!ts.isClassDeclaration(node)) return undefined;
  const properties = node.members
    .filter(ts.isConstructorDeclaration)
    .flatMap((constructor) =>
      constructor.parameters
        .filter((parameter) => !hidden(parameter) && ts.isParameterPropertyDeclaration(parameter, constructor))
        .map((parameter) =>
          factory.createPropertyDeclaration(
            parameter.modifiers,
            parameter.name,
            parameter.questionToken,
            parameter.type,
            undefined,
          ),
        ),
    );
  const members = node.members
    .filter((member) => !hidden(member))
    .map((member) => {
      if (ts.isConstructorDeclaration(member))
        return factory.updateConstructorDeclaration(member, member.modifiers, parameters(member.parameters), undefined);
      if (ts.isMethodDeclaration(member))
        return factory.updateMethodDeclaration(
          member,
          member.modifiers?.filter((modifier) => modifier.kind !== ts.SyntaxKind.AsyncKeyword),
          member.asteriskToken,
          member.name,
          member.questionToken,
          member.typeParameters,
          parameters(member.parameters),
          member.type,
          undefined,
        );
      if (ts.isGetAccessorDeclaration(member))
        return factory.updateGetAccessorDeclaration(
          member,
          member.modifiers,
          member.name,
          parameters(member.parameters),
          member.type,
          undefined,
        );
      if (ts.isPropertyDeclaration(member)) {
        const type =
          member.type ??
          (member.initializer && ts.isArrowFunction(member.initializer)
            ? factory.createFunctionTypeNode(
                undefined,
                parameters(member.initializer.parameters),
                member.initializer.type,
              )
            : undefined);
        if (!type) throw new Error(`Public property ${member.name.getText()} must declare its type.`);
        return factory.updatePropertyDeclaration(
          member,
          member.modifiers,
          member.name,
          member.questionToken,
          type,
          undefined,
        );
      }
      throw new Error(`Unsupported public declaration in ${node.name?.text}; update the API generator.`);
    });
  return factory.updateClassDeclaration(node, ambient, node.name, node.typeParameters, node.heritageClauses, [
    ...properties,
    ...members,
  ]);
}

const definitions = [];
for (const path of sources) {
  const source = ts.createSourceFile(path, await readFile(new URL(path, root), 'utf8'), ts.ScriptTarget.Latest, true);
  const statements = source.statements
    .filter((node) => exported(node) && !ts.getJSDocTags(node).some((tag) => tag.tagName.text === 'internal'))
    .map(declaration)
    .filter(Boolean);
  definitions.push(...statements.map((statement) => printer.printNode(ts.EmitHint.Unspecified, statement, source)));
}
const config = await prettier.resolveConfig(fileURLToPath(new URL('package.json', root)));
const code =
  'import type { ReactNode, RefObject } from "react";\n' +
  'import type * as monaco from "monaco-editor/editor/editor.api";\n' +
  'import type { RpcMessage } from "@language-playground/ide/transport";\n\n' +
  definitions.join('\n\n');
const formattedCode = await prettier.format(code, { ...config, parser: 'typescript' });
const block = await prettier.format('```ts\n' + formattedCode + '\n```\n', { ...config, parser: 'markdown' });
const current = await readFile(documentUrl, 'utf8');
const start = current.indexOf(begin);
const finish = current.indexOf(end);
if (start < 0 || finish <= start || current.indexOf(begin, start + begin.length) >= 0)
  throw new Error('The API document must contain one ordered pair of definition markers.');
const expected = current.slice(0, start + begin.length) + '\n\n' + block + '\n' + current.slice(finish);
if (process.argv.includes('--check')) {
  if (current !== expected) throw new Error('API definitions are out of date. Run npm run docs:api.');
} else {
  await writeFile(documentUrl, expected);
}
