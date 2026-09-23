# Core CP

Core CP implements [CP (Compositional Programming)](https://github.com/yzyzsun/CP-next),
a statically typed functional language with first-class traits and intersection
types. Records, functions, and traits can be combined with the merge operator
`,,`, with the type system checking that their composition is unambiguous.

This experimental implementation is written in Scala 3 and uses
**Fᵢᵒᵇˢ (observational Fᵢ⁺)** as its core calculus.

```cp
def greet(person: { name: String }): String =
  "Hello, " ++ person.name ++ "!"

def main: String = {
  let person = { name = "Alice" } ,, { active = true }
  greet(person)
}
```

The result is `"Hello, Alice!"`. The merged record has both fields, and `greet`
accepts it through the interface it needs: `{ name: String }`.

## Features

- **Disjoint intersection types:** combine interfaces with `&` and values with
  `,,`, including records and functions, with distributive subtyping.
- **First-class traits:** pass traits to functions, compose them, and instantiate
  them with a shared self reference. Supports inheritance, `super`, and forwarding.
- **Compositional interfaces:** define families of constructors and extend their
  behavior through independently written trait implementations.
- **Disjoint polymorphism:** write generic functions with explicit constraints
  on which types can be merged.
- **Pure, lazy evaluation:** immutable values, higher-order functions, and
  recursive and mutually recursive definitions.
- **Modules:** named namespaces, explicit imports, and separate module compilation.
- **Browser playground:** edit programs, inspect results, and step through an
  interactive view of evaluation.

## Getting started

To run the browser playground locally, install **Java 21**, **sbt**, and
**Node.js 24 with npm**. From the repository root:

```sh
cd web-demo/web
npm run setup
npm run dev
```

Open the URL printed by Vite. Paste a program into the editor and select
**Compile** to see its result. The example selector includes arithmetic,
recursion, records, and lazy evaluation. **Step** advances the evaluation graph.

Each example below is a complete program that can be pasted into the editor.
The playground currently edits one source file; the Scala compiler API also
supports programs consisting of multiple modules. See the
[web demo README](web-demo/README.md) for more controls and build details.

## Language tour

### Functions and expressions

Functions use typed parameters and an optional result annotation. Blocks
return their final expression, and `let` introduces a local binding. Calls
such as `f(x, y)` are curried applications, equivalent to `f x y`.

```cp
def factorial(value: Int): Int =
  if value == 0 then 1 else value * factorial(value - 1)

def main: Int = factorial(6)
```

This evaluates to `720`. Recursive definitions require a result type; ordinary
functions can infer it. Local recursion uses `let rec` with an explicit type.
The primitive types are `Int`, `Float`, `Bool`, `String`, and `Unit`.

Semicolons are optional. In a block, a final expression following an
unterminated `let` starts at or before the binding's indentation; continuation lines
are indented farther or enclosed in delimiters.

### Traits and composition

A trait provides part of an object's behavior. A self requirement describes
what it needs from the object that will contain it.

```cp
type Named = { name: String }
type Greeting = { greeting: String }

def person = trait implements Named => {
  name = "Ada"
}

def greeter = trait [self: Named] implements Greeting => {
  greeting = "Hello, " ++ self.name ++ "!"
}

def main: String = (new (person ,, greeter)).greeting
```

`person` supplies the name that `greeter` needs. Merging the traits and
instantiating them with `new` gives both a shared `self`; the result is
`"Hello, Ada!"`.

A trait type `Trait[Required, Provided]` records these two interfaces.
`Trait[Provided]` abbreviates a trait with no self requirements. Traits can
also inherit from an existing trait with `inherits`, access inherited members
through `super`, or receive an explicit self value through forwarding with `^`.

### Disjoint polymorphism

A generic function can extend a value while preserving its existing fields:

```cp
def withLabel[T * { label: String }](value: T): T & { label: String } =
  value ,, { label = "answer" }

def main: Int = withLabel[{ value: Int }]({ value = 42 }).value
```

The result is `42`. The constraint `T * { label: String }` requires the two
types to be disjoint, making the merge unambiguous. Square brackets introduce
type parameters in definitions and supply type arguments at calls.

### Compositional interfaces

A signature describes a family of constructors. Traits implement operations
for that family using constructor method patterns:

```cp
type Eval = { eval: Int }

type ExpSig<Exp> = {
  Lit: Int -> Exp
  Add: Exp -> Exp -> Exp
}

def evaluate = trait implements ExpSig<Eval> => {
  (Lit value).eval = value
  (Add left right).eval = left.eval + right.eval
}

def main: Int = {
  let expressions = new evaluate
  let sum = new expressions.Add(
    new expressions.Lit(20),
    new expressions.Lit(22)
  )
  sum.eval
}
```

This evaluates to `42`. Another trait can implement a different operation for
the same constructors, such as printing, and compose with `evaluate` using
`,,`. Signatures can also extend other signatures to introduce new constructors.

In `ExpSig<Eval>`, angle brackets instantiate the signature's sort parameter.
This is distinct from ordinary polymorphic type application such as `f[T]`.
Signatures also support dependency pairs, written `ExpSig<A % B>`, for an
implementation that provides `B` and depends on the combined interface `A & B`.

### Modules

Source files use PascalCase names ending in `.cp`. A file named `Geometry.cp`
has namespace `Geometry` by default; an explicit declaration such as
`module Math::Geometry` supplies its full namespace.

Imports appear before definitions:

| Import | Access |
| --- | --- |
| `import module Geometry` | Qualified names such as `Geometry::area`. |
| `import Geometry::area` | The selected member as `area` or `Geometry::area`. |
| `import Geometry::*` | All members of `Geometry` without a qualifier. |

Namespace paths are absolute and use `::`; `.` projects a record field.
Imports are not transitive. Each module contains definitions, and evaluating
it selects its `main` definition. Library modules can omit `main`.

## Development

Run the JVM test suite from the repository root:

```sh
sbt root/Test/testFull
```

The suite checks parsing, typing, elaboration, both evaluators, and the example
corpus, including multi-module programs.

For the web demo, after installing its npm dependencies, run from the
repository root:

```sh
cd web-demo/web
npm test
npm run test:integration
npm run build
```

These commands run the TypeScript tests, check compilation and evaluation
through Scala.js, and build the production site.

## Implementation

CP source is parsed into a semantic core and elaborated to Fᵢᵒᵇˢ. The Scala
implementation includes a direct evaluator and a compiler to FiTrie, a trie
representation used by the playground's evaluation graph. The JVM and Scala.js
builds share the same language implementation.
