import type { Example } from "@language-playground/ide/api";

function singleFile(id: string, title: string, description: string, text: string): Example {
  return { id, title, description, entryPath: "Main.cp", files: [{ path: "Main.cp", text }] };
}

export const examples: readonly Example[] = [
  singleFile("arithmetic", "Arithmetic", "Evaluate a function call, then follow its reductions in the trie.", `
def maximum(left right: Int): Int =
  if left > right then left else right

def main: Int = maximum(20, 42)
`.trimStart()),
  singleFile("factorial", "Recursive factorial", "A local recursive function computes 6!.", `
def main: Int =
  let rec factorial: Int -> Int = λ(value: Int) .
    if value == 0 then 1 else value * factorial(value - 1)
  in factorial(6)
`.trimStart()),
  singleFile("records", "Record projection", "Merge disjoint fields and project their values.", `
def main: Int =
  let point = { x = 20 } ,, { y = 22 }
  in point.x + point.y
`.trimStart()),
  singleFile("coercion", "Intersection coercion", "Replace a Bool component while retaining the Int component.", `
def replaceBoolean(value: Int) = false ,, value;

def main: Int =
  ((replaceBoolean : Bool & Int -> Bool & Int)(true ,, 42) : Int)
`.trimStart()),
  singleFile("lazy", "Lazy branch", "The unused branch diverges; the selected branch returns 42.", `
def main: Int =
  let rec diverge: Int = diverge
  in if true then 42 else diverge
`.trimStart()),
  {
    id: "modules",
    title: "Module imports",
    description: "Import a definition from another file. The entry file stays selected when switching editor tabs.",
    entryPath: "Application.cp",
    files: [
      {
        path: "Application.cp",
        text: "module Examples::Application\nimport Examples::Library::*\n\ndef main: Int = twice(21)\n"
      },
      {
        path: "lib/Library.cp",
        text: "module Examples::Library\n\ndef twice(value: Int): Int = value + value\n"
      }
    ]
  }
];
