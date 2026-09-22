# CP examples

Every `.cp` file uses the current CP syntax. Examples are grouped by the
language feature they demonstrate, and ordinary source filenames use
PascalCase. Files in one directory form a compilation set, which permits
multi-module examples.

The first `// expected:` directive marks an evaluated module and gives a closed
CP initializer whose value is the expected result. Dependency-only modules do
not need that directive or a `main` definition. Every compilation unit contains
declarations only. The example test suite discovers `.cp` files recursively,
compiles every directory as a module set, and evaluates each marked entry, so
adding an example requires no Scala test registration.

Recursive object examples use `interface Exp { ... }`, which expands to
`type Exp = μ Exp. { ... }`. They keep `fold[Exp]` and `unfold[Exp]` explicit.
The examples exercise lazy recursive fields and binary methods whose arguments
have the same recursive type as their receiver.
