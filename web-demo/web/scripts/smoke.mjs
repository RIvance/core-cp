import assert from "node:assert/strict";
import { CpTrieWorkbench } from "../scalajs/main.js";
import { examples } from "../src/examples.ts";

const workbench = new CpTrieWorkbench();
for (const example of examples) {
  const result = workbench.compile(example.source, "Main.cp");
  assert.equal(result.ok, true, `${example.name}: ${describeFailure(result)}`);
}

const compilation = workbench.compile("def main: Int = 20 + 22\n", "Main.cp");

assert.equal(compilation.ok, true, describeFailure(compilation));
assert.equal(compilation.entryPoint, "Main::main");
assert.equal(compilation.step, 1);
assert.match(compilation.elaboratedMainTerm, /20 \+ 22/);
assert.deepEqual(compilation.fiobsResult, { ok: true, value: "42" });
assert.equal(isOnlyGlobalMain(compilation.snapshot), false);

let state = compilation;
for (let fuel = 100; fuel > 0 && !state.complete; fuel -= 1) {
  state = workbench.step();
  assert.equal(state.ok, true, describeFailure(state));
}

assert.equal(state.complete, true, "evaluation did not reach normal form within 100 steps");
const root = state.snapshot.nodes.find((node) => node.id === state.snapshot.root);
assert.ok(root, "the final snapshot has no root node");
assert.deepEqual(root.terminations, [{ key: "int", value: "42" }]);

const restarted = workbench.restart();
assert.equal(restarted.ok, true, describeFailure(restarted));
assert.equal(restarted.step, 1);
assert.deepEqual(restarted.fiobsResult, { ok: true, value: "42" });
assert.equal(isOnlyGlobalMain(restarted.snapshot), false);

const coercion = workbench.compile(`// expected: 42

def replaceBoolean(value: Int) = false ,, value;

def main: Int = ((replaceBoolean : Bool & Int -> Bool & Int)(true ,, 42) : Int);
`, "Main.cp");

assert.equal(coercion.ok, true, describeFailure(coercion));
state = coercion;
let largestCoercionStep = 0;
for (let fuel = 100; fuel > 0 && !state.complete; fuel -= 1) {
  const previousStep = state.step;
  state = workbench.step();
  assert.equal(state.ok, true, describeFailure(state));
  if (state.ok) {
    largestCoercionStep = Math.max(largestCoercionStep, state.step - previousStep);
    assertNoRedundantFilters(state.snapshot);
  }
}

assert.equal(state.complete, true, "intersection coercion did not reach normal form within 100 steps");
assert.ok(largestCoercionStep > 1, "the browser session did not coalesce presentation-equivalent filters");
const coercionRoot = state.snapshot.nodes.find((node) => node.id === state.snapshot.root);
assert.ok(coercionRoot, "the intersection coercion has no final root node");
assert.deepEqual(coercionRoot.responses, []);
assert.deepEqual(coercionRoot.terminations, [{ key: "int", value: "42" }]);

const directEvaluationFailure = workbench.compile("def main: Int = 1 / 0\n", "Main.cp");
assert.equal(directEvaluationFailure.ok, true, describeFailure(directEvaluationFailure));
assert.deepEqual(directEvaluationFailure.fiobsResult, {
  ok: false,
  message: "Operator '/' attempted division by zero."
});

const recordMerge = workbench.compile("def main = { l = 1 } ,, { l = true }\n", "Main.cp");
assert.equal(recordMerge.ok, true, describeFailure(recordMerge));
assert.deepEqual(recordMerge.fiobsResult, { ok: true, value: "{ l = 1 ,, true }" });

const invalid = workbench.compile("def main: Int =", "Main.cp");
assert.equal(invalid.ok, false);
assert.equal(invalid.fiobsResult, null);
assert.equal(invalid.error.phase, "parse");
assert.equal(typeof invalid.error.line, "number");

const invalidEscape = workbench.compile('def main = "\\q";', "Main.cp");
assert.equal(invalidEscape.ok, false);
assert.equal(invalidEscape.error.phase, "parse");
assert.match(invalidEscape.error.message, /invalid string escape/);
assert.equal(invalidEscape.error.column, 13);

const signatureMisuse = workbench.compile(`type Signature<Sort> = { field: Sort };
def main = Signature[Int];`, "Main.cp");
assert.equal(signatureMisuse.ok, false);
assert.equal(signatureMisuse.error.phase, "compile");
assert.match(signatureMisuse.error.message, /Expected a term.*names a type: Main::Signature/);

const signatureHomonym = workbench.compile(`type Signature<Sort> = { field: Sort };
def Signature[T](value: T) = value;
def main = Signature[Int](42);`, "Main.cp");
assert.equal(signatureHomonym.ok, true, describeFailure(signatureHomonym));
assert.deepEqual(signatureHomonym.fiobsResult, { ok: true, value: "42" });

const invalidMergeSource = "def main = 1 ,, 2\n";
const invalidMerge = workbench.compile(invalidMergeSource, "Main.cp");
assert.equal(invalidMerge.ok, false);
assert.equal(invalidMerge.fiobsResult, null);
assert.equal(invalidMerge.error.phase, "compile");
assert.match(invalidMerge.error.message, /Cannot merge Int with Int: the types are not disjoint\./);
assert.doesNotMatch(invalidMerge.error.message, /TypesAreNotDisjoint|Primitive\(/);
assert.deepEqual(
  {
    line: invalidMerge.error.line,
    column: invalidMerge.error.column,
    endLine: invalidMerge.error.endLine,
    endColumn: invalidMerge.error.endColumn
  },
  { line: 1, column: 12, endLine: 1, endColumn: 18 }
);

function describeFailure(result) {
  return result.ok ? "" : `${result.error.phase}: ${result.error.message}`;
}

function isOnlyGlobalMain(snapshot) {
  if (snapshot.nodes.length !== 1) {
    return false;
  }
  const root = snapshot.nodes[0];
  return root.id === snapshot.root &&
    root.responses.length === 1 &&
    root.responses[0].kind === "global" &&
    root.responses[0].notation === "global Main::main" &&
    root.routes.length === 0 &&
    root.terminations.length === 0;
}

function assertNoRedundantFilters(snapshot) {
  const nodes = new Map(snapshot.nodes.map((node) => [node.id, node]));
  for (const node of snapshot.nodes) {
    for (const response of node.responses) {
      if (response.kind !== "filter") {
        continue;
      }
      const receiver = nodes.get(response.receiver);
      const nested = receiver?.responses.length === 1 &&
        receiver.routes.length === 0 &&
        receiver.terminations.length === 0
        ? receiver.responses[0]
        : undefined;
      assert.notEqual(
        nested?.kind === "filter" && nested.selectedRootKeys === response.selectedRootKeys,
        true,
        "the Scala presentation snapshot contains an identical nested filter"
      );
    }
  }
}
