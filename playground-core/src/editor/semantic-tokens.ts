import type { SemanticToken, SemanticTokenLegend } from '../core/contracts';
import type { monaco } from './monaco';

/** Converts semantic names and absolute ranges to Monaco's relative token representation. */
export function encodeSemanticTokens(
  tokens: readonly SemanticToken[],
  legend: SemanticTokenLegend,
  model: monaco.editor.ITextModel,
): Uint32Array {
  if (
    new Set(legend.tokenTypes).size !== legend.tokenTypes.length ||
    new Set(legend.tokenModifiers).size !== legend.tokenModifiers.length ||
    legend.tokenModifiers.length > 31
  )
    throw new Error('The language service supplied an invalid semantic token legend.');
  const sorted = [...tokens].sort(
    (left, right) =>
      left.range.start.line - right.range.start.line || left.range.start.character - right.range.start.character,
  );
  const data: number[] = [];
  let previousLine = 0;
  let previousStart = 0;
  let previousEnd = 0;
  for (const token of sorted) {
    const { start, end } = token.range;
    const type = legend.tokenTypes.indexOf(token.type);
    const coordinates = [start.line, start.character, end.line, end.character];
    if (
      coordinates.some((value) => !Number.isSafeInteger(value) || value < 0) ||
      start.line !== end.line ||
      start.character >= end.character ||
      start.line >= model.getLineCount() ||
      end.character >= model.getLineMaxColumn(start.line + 1) ||
      (data.length > 0 && start.line === previousLine && start.character < previousEnd) ||
      type < 0
    )
      throw new Error('The language service supplied an invalid or overlapping semantic token.');
    let modifiers = 0;
    for (const modifier of token.modifiers) {
      const index = legend.tokenModifiers.indexOf(modifier);
      if (index < 0) throw new Error(`Unknown semantic token modifier: ${modifier}`);
      modifiers |= 2 ** index;
    }
    const lineDelta = start.line - previousLine;
    data.push(
      lineDelta,
      lineDelta === 0 ? start.character - previousStart : start.character,
      end.character - start.character,
      type,
      modifiers,
    );
    previousLine = start.line;
    previousStart = start.character;
    previousEnd = end.character;
  }
  return new Uint32Array(data);
}
