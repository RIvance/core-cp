export interface Example {
  readonly name: string;
  readonly source: string;
}

export const examples: readonly Example[] = [
  {
    name: "Arithmetic",
    source: `// Compile, then step until 42 becomes a termination payload.
def maximum(left right: Int): Int =
  if left > right then left else right

def main: Int = maximum(20, 42)
`
  },
  {
    name: "Recursive factorial",
    source: `def main: Int =
  let rec factorial: Int -> Int = λ(value: Int) .
    if value == 0 then 1 else value * factorial(value - 1)
  in factorial(6)
`
  },
  {
    name: "Record projection",
    source: `def main: Int =
  let point = { x = 20 } ,, { y = 22 }
  in point.x + point.y
`
  },
  {
    name: "Intersection coercion",
    source: `// The Bool component is replaced while the Int component remains observable.
def replaceBoolean(value: Int) = false ,, value;

def main: Int =
  ((replaceBoolean : Bool & Int -> Bool & Int)(true ,, 42) : Int)
`
  },
  {
    name: "Lazy branch",
    source: `def main: Int =
  let rec diverge: Int = diverge
  in if true then 42 else diverge
`
  }
];
