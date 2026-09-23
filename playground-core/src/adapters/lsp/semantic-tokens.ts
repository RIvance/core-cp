import { z } from 'zod';
import type { Range, SemanticToken, SemanticTokenLegend, SourceDocument } from '../../core/contracts';

const resultSchema = z.object({ data: z.array(z.number().int().min(0).max(0x7fffffff)) }).nullable();

export function documentRange(document: SourceDocument): Range {
  const lines = document.text.split(/\r\n|\r|\n/);
  return { start: { line: 0, character: 0 }, end: { line: lines.length - 1, character: lines.at(-1)!.length } };
}

/** LSP relative integer tuples are decoded once into absolute, language-neutral semantic tokens. */
export function decodeSemanticTokens(
  input: unknown,
  legend: SemanticTokenLegend,
  document: SourceDocument,
): SemanticToken[] {
  const result = resultSchema.parse(input);
  if (!result) return [];
  if (
    result.data.length % 5 !== 0 ||
    legend.tokenModifiers.length > 31 ||
    new Set(legend.tokenTypes).size !== legend.tokenTypes.length ||
    new Set(legend.tokenModifiers).size !== legend.tokenModifiers.length
  )
    throw new Error('The language service supplied invalid semantic token data or a duplicate legend.');
  const lines = document.text.split(/\r\n|\r|\n/);
  const tokens: SemanticToken[] = [];
  let line = 0;
  let character = 0;
  let previousEnd = 0;
  for (let index = 0; index < result.data.length; index += 5) {
    const [lineDelta, characterDelta, length, typeIndex, modifierBits] = result.data.slice(index, index + 5) as [
      number,
      number,
      number,
      number,
      number,
    ];
    line += lineDelta;
    character = lineDelta === 0 ? character + characterDelta : characterDelta;
    const type = legend.tokenTypes[typeIndex];
    const lineText = lines[line];
    if (
      type === undefined ||
      lineText === undefined ||
      length === 0 ||
      character + length > lineText.length ||
      (lineDelta === 0 && character < previousEnd) ||
      modifierBits >= 2 ** legend.tokenModifiers.length
    )
      throw new Error('The language service supplied an invalid or overlapping semantic token.');
    tokens.push({
      range: { start: { line, character }, end: { line, character: character + length } },
      type,
      modifiers: legend.tokenModifiers.filter((_value, modifier) => (modifierBits & (2 ** modifier)) !== 0),
    });
    previousEnd = character + length;
  }
  return tokens;
}
