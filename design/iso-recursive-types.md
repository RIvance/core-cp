# Iso-recursive types

CP uses `μ X. A` (also written `mu X. A`) for an iso-recursive type.
`fold[R] e` constructs a value of recursive type `R`; `unfold[R] e` exposes
its body. The annotation must name a recursive type. Neither operation is
inserted implicitly.

An `interface` declaration is shorthand for a recursive record type:

```text
interface Exp { eval: Int; double: Exp; eq: Exp -> Bool; }
  ≔ type Exp = μ Exp. { eval: Int; double: Exp; eq: Exp -> Bool; }
```

The parser produces the same type declaration for both spellings. Sort
parameters are also allowed: `interface Stream<Element> { value: Element;
next: Stream; }` expands to `type Stream<Element> = μ Stream. { value: Element;
next: Stream; }`. The bare `Stream` in its body refers to that recursive binder.
An empty body means `Top`, so `interface Empty {}` defines `μ Empty. Top`.
Values still require explicit `fold` and `unfold`; the declaration adds no
implicit conversions or new typing rules.

```cp
type Stream = μ S. { head: Int; tail: S };

def naturals(n: Int): Stream = fold[Stream] {
  head = n;
  tail = naturals(n + 1);
};

def main = (unfold[Stream] (unfold[Stream] naturals(40)).tail).head;
// 41
```

The tail is lazy. Producing one folded stream does not produce the entire
stream. Observing another element requires another explicit unfold.

## Types, binding, and typing

Let `R = μ α. A`. Define its one-step unfolding as `U(R) = A[α ↦ R]`, using
capture-avoiding substitution. A recursive binder has no disjointness bound:
`α` bound by `μ` and `α ∗ B` bound by `∀` are different context entries.

```text
Δ, α ⊢ A ✔
────────────── WF-Rec
Δ ⊢ μ α. A ✔

R = μ α. A    Δ ⊢ R ✔    Δ ; Γ ⊢ e ⇐ U(R)
────────────────────────────────────────── T-Fold
Δ ; Γ ⊢ fold[R] e ⇒ R

R = μ α. A    Δ ⊢ R ✔    Δ ; Γ ⊢ e ⇐ R
────────────────────────────────────── T-Unfold
Δ ; Γ ⊢ unfold[R] e ⇒ U(R)
```

These introduction and elimination rules follow
[A Calculus with Recursive Types, Record Concatenation and Subtyping](https://i.cs.hku.hk/~bruno/papers/aplas22recursive.pdf),
Figure 5. The recursive annotation on unfold also selects an interface from
an intersection, through ordinary checking and subtyping.

For example, these programs are rejected:

```cp
type Box = μ X. { value: Int };
def missingFold: Box = { value = 42 };
def missingUnfold = (fold[Box] { value = 42 }).value;
def wrongAnnotation = fold[Int] 42;
```

A binder's scope is its type body. Renaming it does not change the type.
Nested recursive and universal binders retain distinct lexical identities.
Negative occurrences, such as `μ X. X -> Int`, are permitted; so is `μ X. X`.
Well-formedness does not assert that a type has a terminating inhabitant.

Ordinary aliases remain acyclic. Recursive types must use an explicit binder:
`type Stream = μ S. { tail: S }` is valid; `type Stream = { tail: Stream }`
is not an alternative recursive-type notation. Recursive term definitions
still require their existing type signatures.

## Recursive subtyping

Recursive types are compared by nominal unfolding. Here `Aᵅ` is a proof-local
label carrying body `A`; it is neither a user record nor a runtime value.
The label identity `α` is fresh for this comparison. Occurrences of `α` in
the label's payload remain rigid variables.

```text
Δ, α ⊢ A[α ↦ Aᵅ] <: B[α ↦ Bᵅ]
────────────────────────────────── S-Rec
Δ ⊢ μ α. A <: μ α. B

Δ ⊢ A <: B
────────────────── S-Label
Δ ⊢ Aᵅ <: Bᵅ
```

This is the nominal rule from
[Revisiting Iso-Recursive Subtyping](https://i.cs.hku.hk/~bruno/papers/toplas2022.pdf).
Comparing the bodies once under a shared variable would incorrectly accept
`μ X. X -> Int <: μ X. X -> Top`. Nominal unfolding also checks the
contravariant obligation exposed by the recursive occurrence and rejects it.

Width subtyping works inside recursive records:

```text
μ S. { value: Int; extra: Bool; next: S }
  <:
μ S. { value: Int; next: S }
```

Crossing a fold boundary remains explicit. There is no rule deriving
`μ α. A <: U(μ α. A)` or its converse merely by unfolding. There is also no
rule distributing `μ` through an intersection. In particular, splitting
`μ X. (A & B)` into `(μ X. A) & (μ X. B)` would change what `X` denotes.
Existing splitting through function results, records, and universal bodies
continues to operate inside the recursive comparison.

## Evaluation and casts

Folds suspend their payloads, consistently with CP's call by name evaluation
and lazy record fields. This differs from the strict fold dynamics in the
APLAS paper. A fold is ready even when its payload fails or diverges.

Write `⟨fold r⟩ᴿ` for a runtime fold carrying its checked recursive interface.

```text
──────────────── Ready-Fold
◉ʳ ⟨fold r⟩ᴿ

────────────────────────── Step-Unfold-Fold
unfold ⟨fold r⟩ᴿ ↪ r

r ↪ r′
───────────────────────── Step-Unfold
unfold r ↪ unfold r′

R = μ α. A    S = μ β. B    ∅ ⊢ R <: S
───────────────────────────────────────────── Cast-Rec
⟨fold r⟩ᴿ ⟶[S] ⟨fold (r : U(S))⟩ˢ
```

The payload conversion in `Cast-Rec` stays suspended. Its required property
is the unfolding lemma: `R <: S` implies `U(R) <: U(S)`.
Evaluating `fold[μ X. Int] (1 / 0)` produces a fold. Unfolding it and demanding
the integer exposes division by zero. The separate API for fully evaluating
values forces folded payloads as well as record fields; it need not terminate
on infinite structures.

There is no TopLike conversion:

```text
μ X. Top <: Top                 Top ≮: μ X. Top
```

Thus `fold[μ X. Top] top` is valid, but `(top : μ X. Top)` is not. A folded
empty payload still has an unfold interface.

The ordinary merge rule remains unchanged:

```text
Δ ; Γ ⊢ E₁ ⇒ A ↝ e₁    Δ ; Γ ⊢ E₂ ⇒ B ↝ e₂    Δ ⊢ A ∗ B
────────────────────────────────────────────────────────── E-Merge
Δ ; Γ ⊢ E₁ ,, E₂ ⇒ A ∧ B ↝ e₁ ,, e₂
```

## Recursive disjointness

The APLAS paper defines disjointness through common supertypes and TopLike.
Fiobs instead uses the absence of a common finite collision route. Its new
recursive case must therefore be justified separately.

A collision route records eliminations ending at a primitive result. For
example, `unfold · proj_head · int` observes a stream's head. Function
arguments are omitted from these routes; their result paths determine
collisions. With recursion, paths may be arbitrarily long even though each
observation is finite.

Define `Mη(A)` as a conservative set of possible routes. Let `𝒰` contain
every finite route ending at any primitive, and let `η` assign route sets to
recursive variables. Opaque polymorphic variables are assigned `𝒰`.

```text
Mη(p) = {p}                       Mη(Top) = ∅
Mη(Bottom) = 𝒰                    Mη(α) = η(α), or 𝒰 if opaque
Mη(A & B) = Mη(A) ∪ Mη(B)
Mη(A -> B) = app · Mη(B)
Mη({label: A}) = proj_label · Mη(A)
Mη(∀(α ∗ A). B) = tapp · Mη[α ↦ 𝒰](B)
Mη(μ α. A) = least S satisfying S = unfold · Mη[α ↦ S](A)
```

Universal guards and type-argument tokens are deliberately erased in `M`.
This makes its recursive equations monotone and its representation finite:
recursive occurrences point back to their binder. Reachability decides
silence; reachability in the product of two graphs decides overlap.

```text
M(μ α. A) = ∅
────────────────── Sil-Rec
Δ ⊢ μ α. A ∅ᵒ

M(μ α. A) ∩ M(μ β. B) = ∅
─────────────────────────── D-Rec
Δ ⊢ μ α. A ∗ μ β. B
```

The existing contextual rules for disjoint polymorphism remain available.
The recursive graph certificate is conservative: for example,
`μ X. ∀(A ∗ Bottom). A` is not recognized as silent by `M`, because `M`
forgets the bound. This can reject merges. It does not justify a TopLike cast.

Recursive variables must not be replaced by silent placeholders. Consider:

```text
R = μ X. (Int & X)
S = μ Y. (μ Z. Int)
```

Both admit `unfold · unfold · int`, so they must not be declared disjoint.
Conversely, `μ X. { left: Int; next: X }` and
`μ X. { right: Int; next: X }` have no common complete route, despite sharing
the prefixes `unfold` and `proj_next`.


## References

[Recursive Subtyping for All](https://i.cs.hku.hk/~bruno/papers/jfp25.pdf)
and [QuickSub](https://i.cs.hku.hk/~bruno/papers/popl25_quicksub.pdf) are useful
comparisons for recursive subtyping. Their results should not be cited as a
proof for unrestricted CP intersections. Likewise,
[Full Iso-Recursive Types](https://i.cs.hku.hk/~bruno/papers/oopsla24_full.pdf)
studies a different, richer cast system; that is not what explicit fold and
unfold add here.