package cp.fiobs

import cp.fiobs.runtime.RuntimeBinding.*
import cp.fiobs.runtime.RuntimeTerm

class RuntimeBindingSuite extends munit.FunSuite {
  private val recursive = Type.Recursive(Type.Record("next", Type.Variable(0)))

  test("closed recursive subtrees survive shifts and substitutions without reconstruction") {
    val closed = RuntimeTerm.Fix(recursive, RuntimeTerm.Fold(
      recursive,
      RuntimeTerm.Record("next", RuntimeTerm.Cast(RuntimeTerm.Variable(0), recursive), recursive)
    ))
    assert(closed.shiftTermVariables(3) eq closed)
    assert(closed.substituteTerm(0, RuntimeTerm.Variable(2)) eq closed)
    assert(RuntimeTerm.Merge(closed, RuntimeTerm.Variable(0)).shiftTermVariables(2) match {
      case RuntimeTerm.Merge(left, RuntimeTerm.Variable(2)) => left eq closed
      case _ => false
    })
  }

  test("shifts respect the cutoff beneath term binders and ignore type binders") {
    val body = RuntimeTerm.Merge(
      RuntimeTerm.Variable(0),
      RuntimeTerm.Merge(RuntimeTerm.Variable(1), RuntimeTerm.Variable(3))
    )
    val term = RuntimeTerm.TypeLambda(
      Type.Variable(0),
      RuntimeTerm.Lambda(Type.Variable(1), body, Type.Variable(1)),
      Type.Arrow(Type.Variable(1), Type.Variable(1))
    )
    val expected = RuntimeTerm.TypeLambda(
      Type.Variable(0),
      RuntimeTerm.Lambda(Type.Variable(1), RuntimeTerm.Merge(
        RuntimeTerm.Variable(0),
        RuntimeTerm.Merge(RuntimeTerm.Variable(1), RuntimeTerm.Variable(5))
      ), Type.Variable(1)),
      Type.Arrow(Type.Variable(1), Type.Variable(1))
    )
    assertEquals(term.shiftTermVariables(2, cutoff = 1), expected)
    assertEquals(term.shiftTermVariables(2).shiftTermVariables(-2), term)
    assert(term.shiftTermVariables(0) eq term)
    assert(term.shiftTermVariables(2, cutoff = 3) eq term)
  }

  test("substitution crosses lambda and fix binders without capturing its replacement") {
    val replacement = RuntimeTerm.Record("free", RuntimeTerm.Variable(0), Type.Integer)
    val term = RuntimeTerm.Lambda(Type.Integer, RuntimeTerm.Fix(Type.Integer, RuntimeTerm.Merge(
      RuntimeTerm.Merge(RuntimeTerm.Variable(0), RuntimeTerm.Variable(1)),
      RuntimeTerm.Merge(RuntimeTerm.Variable(2), RuntimeTerm.Variable(3))
    )), Type.Integer)
    val expected = RuntimeTerm.Lambda(Type.Integer, RuntimeTerm.Fix(Type.Integer, RuntimeTerm.Merge(
      RuntimeTerm.Merge(RuntimeTerm.Variable(0), RuntimeTerm.Variable(1)),
      RuntimeTerm.Merge(
        RuntimeTerm.Record("free", RuntimeTerm.Variable(2), Type.Integer),
        RuntimeTerm.Variable(2)
      )
    )), Type.Integer)
    assertEquals(term.substituteTerm(0, replacement), expected)
  }

  test("removing an unused binding still decrements higher free indices") {
    val term = RuntimeTerm.Merge(RuntimeTerm.Variable(0), RuntimeTerm.Variable(2))
    assertEquals(
      term.substituteTerm(1, RuntimeTerm.Top),
      RuntimeTerm.Merge(RuntimeTerm.Variable(0), RuntimeTerm.Variable(1))
    )
    assert(term.substituteTerm(3, RuntimeTerm.Top) eq term)
  }

  test("free term depth does not overflow at the largest represented index") {
    val term = RuntimeTerm.Variable(Int.MaxValue)
    val decremented = RuntimeTerm.Variable(Int.MaxValue - 1)
    assertEquals(term.shiftTermVariables(-1), decremented)
    assertEquals(term.substituteTerm(0, RuntimeTerm.Top), decremented)
  }

  test("substitution under a universal shifts free types in folded replacements") {
    val replacement = RuntimeTerm.Fold(
      Type.Recursive(Type.Variable(1)),
      RuntimeTerm.Cast(RuntimeTerm.Variable(0), Type.Variable(0))
    )
    val term = RuntimeTerm.TypeLambda(Type.Top, RuntimeTerm.Lambda(
      Type.Variable(0),
      RuntimeTerm.Merge(RuntimeTerm.Variable(0), RuntimeTerm.Variable(1)),
      Type.Variable(0)
    ), Type.Arrow(Type.Variable(0), Type.Variable(0)))
    val expected = RuntimeTerm.TypeLambda(Type.Top, RuntimeTerm.Lambda(
      Type.Variable(0),
      RuntimeTerm.Merge(RuntimeTerm.Variable(0), RuntimeTerm.Fold(
        Type.Recursive(Type.Variable(2)),
        RuntimeTerm.Cast(RuntimeTerm.Variable(1), Type.Variable(1))
      )),
      Type.Variable(0)
    ), Type.Arrow(Type.Variable(0), Type.Variable(0)))
    assertEquals(term.substituteTerm(0, replacement), expected)
  }

  test("eliminations and suspended fields retain all affected free occurrences") {
    val term = RuntimeTerm.If(
      RuntimeTerm.Variable(0),
      RuntimeTerm.TypeApplication(RuntimeTerm.Application(
        RuntimeTerm.Unfold(RuntimeTerm.Variable(1)),
        RuntimeTerm.Projection(RuntimeTerm.Record("value", RuntimeTerm.Variable(2), Type.Integer), "value")
      ), recursive),
      RuntimeTerm.Cast(RuntimeTerm.Fold(recursive, RuntimeTerm.Variable(3)), recursive)
    )
    val expected = RuntimeTerm.If(
      RuntimeTerm.Variable(0),
      RuntimeTerm.TypeApplication(RuntimeTerm.Application(
        RuntimeTerm.Unfold(RuntimeTerm.Top),
        RuntimeTerm.Projection(RuntimeTerm.Record("value", RuntimeTerm.Variable(1), Type.Integer), "value")
      ), recursive),
      RuntimeTerm.Cast(RuntimeTerm.Fold(recursive, RuntimeTerm.Variable(2)), recursive)
    )
    assertEquals(term.substituteTerm(1, RuntimeTerm.Top), expected)
  }

  test("type-closed recursive values retain sharing beneath fresh universal binders") {
    val term = RuntimeTerm.TypeLambda(Type.Top, RuntimeTerm.Lambda(
      Type.Variable(0),
      RuntimeTerm.Fold(recursive, RuntimeTerm.Record("next", RuntimeTerm.Variable(1), recursive)),
      recursive
    ), Type.Arrow(Type.Variable(0), recursive))
    assert(term.shiftTypeVariables(2) eq term)
    assert(term.substituteType(0, Type.Boolean) eq term)
    assert(term.shiftTermVariables(0) eq term)
  }

  test("type substitution reaches bounds and fold interfaces without capturing nested binders") {
    val localResult = Type.Recursive(Type.Variable(1))
    val term = RuntimeTerm.TypeLambda(Type.Variable(0), RuntimeTerm.Fold(
      Type.Recursive(Type.Arrow(Type.Variable(2), Type.Variable(0))),
      RuntimeTerm.Cast(RuntimeTerm.Variable(0), Type.Variable(1))
    ), localResult)
    val shifted = RuntimeTerm.TypeLambda(Type.Variable(2), RuntimeTerm.Fold(
      Type.Recursive(Type.Arrow(Type.Variable(4), Type.Variable(0))),
      RuntimeTerm.Cast(RuntimeTerm.Variable(0), Type.Variable(3))
    ), localResult)
    val substituted = RuntimeTerm.TypeLambda(Type.Integer, RuntimeTerm.Fold(
      Type.Recursive(Type.Arrow(Type.Integer, Type.Variable(0))),
      RuntimeTerm.Cast(RuntimeTerm.Variable(0), Type.Integer)
    ), localResult)
    assertEquals(term.shiftTypeVariables(2), shifted)
    assertEquals(term.substituteType(0, Type.Integer), substituted)
    assert(term.shiftTypeVariables(2, cutoff = 1) eq term)
  }
}
