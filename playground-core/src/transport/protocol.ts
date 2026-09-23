import { z } from 'zod';

const identifier = z.union([z.number().int(), z.string()]);
const envelope = z.object({ jsonrpc: z.literal('2.0') });
export const requestSchema = envelope.extend({
  id: identifier,
  method: z.string().min(1),
  params: z.unknown().optional(),
});
const notificationSchema = envelope.extend({ method: z.string().min(1), params: z.unknown().optional() });
const successSchema = envelope
  .extend({ id: identifier, result: z.unknown() })
  .refine((value) => Object.hasOwn(value, 'result'), 'A successful response must contain a result.');
const errorSchema = envelope.extend({
  id: identifier.nullable(),
  error: z.object({ code: z.number().int(), message: z.string(), data: z.unknown().optional() }),
});
/**
 * Validate one decoded JSON-RPC 2.0 request, notification, success, or failure; batches are not accepted.
 * Requests may omit params. Request IDs must be strings or safe integers; failure IDs may also be null.
 * Mutually exclusive envelope fields are checked before unknown fields are stripped. parse throws ZodError.
 */
export const rpcMessageSchema = z.preprocess(
  (value, context) => {
    if (typeof value !== 'object' || value === null) return value;
    const hasMethod = 'method' in value;
    const hasResult = 'result' in value;
    const hasError = 'error' in value;
    const invalidRequestId =
      hasMethod &&
      'id' in value &&
      typeof value.id !== 'string' &&
      !(typeof value.id === 'number' && Number.isSafeInteger(value.id));
    if (
      (hasMethod && (hasResult || hasError || invalidRequestId)) ||
      (!hasMethod && (hasResult === hasError || !('id' in value)))
    ) {
      context.addIssue({ code: 'custom', message: 'Expected one JSON-RPC request, notification, result, or error.' });
      return z.NEVER;
    }
    return value;
  },
  z.union([requestSchema, notificationSchema, errorSchema, successSchema]),
);
/** Validated wire object accepted by MessageConnection and the worker hosts. Transport framing is not included. */
export type RpcMessage = z.infer<typeof rpcMessageSchema>;

const positionSchema = z.object({ line: z.number().int().nonnegative(), character: z.number().int().nonnegative() });
const rangeSchema = z
  .object({ start: positionSchema, end: positionSchema })
  .refine(
    ({ start, end }) => start.line < end.line || (start.line === end.line && start.character <= end.character),
    'A source range must end at or after its start',
  );
const diagnosticSchema = z.object({
  message: z.string(),
  severity: z.enum(['error', 'warning', 'info', 'hint']),
  location: z.object({ uri: z.string(), range: rangeSchema }).optional(),
  source: z.string().optional(),
  code: z.union([z.string(), z.number()]).optional(),
  data: z.unknown().optional(),
});
/** Validate an execution snapshot, including unique document URIs and membership of the entry URI. */
export const executionInputSchema = z
  .object({
    documents: z.array(
      z.object({ uri: z.string(), languageId: z.string(), version: z.number().int(), text: z.string() }),
    ),
    entryDocumentUri: z.string(),
    entryPoint: z.string(),
    stdin: z.string(),
  })
  .refine(
    (input) => input.documents.some((document) => document.uri === input.entryDocumentUri),
    'The entry document must belong to the execution workspace.',
  )
  .refine(
    (input) => new Set(input.documents.map((document) => document.uri)).size === input.documents.length,
    'Execution documents must have distinct URIs.',
  );
/** Validate a stdout/stderr chunk. Text is preserved verbatim; the worker host suppresses empty chunks. */
export const outputChunkSchema = z.object({ channel: z.enum(['stdout', 'stderr']), text: z.string() });
/** Validate a terminal result, including diagnostic positions/ranges and text artifacts; strip unknown fields. */
export const executionResultSchema = z.object({
  status: z.enum(['success', 'error']),
  value: z.string().optional(),
  diagnostics: z.array(diagnosticSchema),
  artifacts: z.array(z.object({ name: z.string(), mediaType: z.string(), content: z.string() })),
});
