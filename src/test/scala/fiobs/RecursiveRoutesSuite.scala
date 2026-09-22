package cp.fiobs

import cp.fiobs.typing.Disjointness
import cp.util.Result

class RecursiveRoutesSuite extends munit.FunSuite {
  private val disjointness = Disjointness(TypeContext.empty)

  private def assertDisjoint(left: Type, right: Type, expected: Boolean): Unit = {
    assertEquals(disjointness.relates(left, right), expected)
    assertEquals(disjointness.relates(right, left), expected)
  }

  test("different unfolding depths can still reach the same primitive observation") {
    val repeated = Type.Recursive(Type.Intersection(Type.Integer, Type.Variable(0)))
    val nested = Type.Recursive(Type.Recursive(Type.Integer))

    assertDisjoint(repeated, nested, expected = false)
    assertDisjoint(repeated, Type.Recursive(Type.Recursive(Type.Boolean)), expected = true)
  }

  test("cycles without a finite primitive observation are silent") {
    List(
      Type.Recursive(Type.Top),
      Type.Recursive(Type.Variable(0)),
      Type.Recursive(Type.Record("next", Type.Variable(0))),
      Type.Recursive(Type.Arrow(Type.Integer, Type.Variable(0)))
    ).foreach { recursiveType =>
      assert(recursiveType.isSilent())
      assertDisjoint(recursiveType, Type.Bottom, expected = true)
    }
    assert(!Type.Recursive(Type.Bottom).isSilent())
    assert(!Type.Recursive(Type.Intersection(Type.Variable(0), Type.Integer)).isSilent())
  }

  test("two silent folds can be merged and checked at their shared recursive interface") {
    val empty = Type.Recursive(Type.Top)
    val folded = Term.Fold(empty, Term.Top)
    val merged = Term.Merge(folded, folded)

    assertDisjoint(empty, empty, expected = true)
    Fiobs.infer(Term.Unfold(empty, merged)) match {
      case Result.Ok(program) => assertEquals(program.programType, Type.Top)
      case Result.Err(error) => fail(s"silent folded interfaces should merge and unfold: $error")
    }
    assert(!Type.Top.isSubtypeOf(empty))
  }

  test("shared recursive tails do not create a collision when terminal fields differ") {
    def cell(label: String, fieldType: Type): Type = Type.Recursive(Type.Intersection(
      Type.Record(label, fieldType),
      Type.Record("next", Type.Variable(0))
    ))

    assertDisjoint(cell("value", Type.Integer), cell("other", Type.Integer), expected = true)
    assertDisjoint(cell("value", Type.Integer), cell("value", Type.Boolean), expected = true)
    assertDisjoint(cell("value", Type.Integer), cell("value", Type.Integer), expected = false)
  }

  test("recursive types retain an unfold observation constructor") {
    val folded = Type.Recursive(Type.Integer)
    List(
      Type.Integer,
      Type.Record("value", Type.Integer),
      Type.Arrow(Type.Integer, Type.Integer),
      Type.ForAll(Type.Top, Type.Integer)
    ).foreach(other => assertDisjoint(folded, other, expected = true))
    assertDisjoint(folded, Type.Bottom, expected = false)
  }

  test("recursive references keep their scope through universal binders") {
    def recursiveUniversal(label: String): Type = Type.Recursive(Type.Intersection(
      Type.Record(label, Type.Integer),
      Type.ForAll(Type.Top, Type.Variable(1))
    ))

    assertDisjoint(recursiveUniversal("left"), recursiveUniversal("right"), expected = true)
    assertDisjoint(recursiveUniversal("same"), recursiveUniversal("same"), expected = false)

    val opaqueResponse = Type.Recursive(Type.ForAll(Type.Top, Type.Variable(0)))
    assert(!opaqueResponse.isSilent())
    assertDisjoint(opaqueResponse, Type.Recursive(Type.ForAll(Type.Top, Type.Integer)), expected = false)
  }

  test("nested recursive binders distinguish references to the outer and inner binders") {
    val outerReference = Type.Recursive(Type.Recursive(Type.Intersection(
      Type.Integer,
      Type.Record("next", Type.Variable(1))
    )))
    val innerReference = Type.Recursive(Type.Recursive(Type.Record("next", Type.Intersection(
      Type.Integer,
      Type.Variable(0)
    ))))

    // The former observes next only after two unfolds; the latter needs one before another next.
    assertDisjoint(outerReference, innerReference, expected = true)
  }

  test("recursive quantifier bounds terminate without imposing positivity restrictions") {
    val negativeBound = Type.Recursive(Type.ForAll(Type.Variable(0), Type.Integer))
    val sameResponse = Type.Recursive(Type.ForAll(Type.Bottom, Type.Integer))
    val otherResponse = Type.Recursive(Type.ForAll(Type.Top, Type.Boolean))

    assert(TypeContext.empty.accepts(negativeBound))
    assert(!negativeBound.isSilent())
    assertDisjoint(negativeBound, sameResponse, expected = false)
    assertDisjoint(negativeBound, otherResponse, expected = true)
  }

  test("existing quantifier-bound certificates remain available outside recursive route abstraction") {
    val opaque = Type.ForAll(Type.Integer, Type.Variable(0))
    val integer = Type.ForAll(Type.Integer, Type.Integer)

    assertDisjoint(opaque, integer, expected = true)
    // The recursive route certificate deliberately erases universal guards, so it cannot prove this pair.
    assertDisjoint(Type.Recursive(opaque), Type.Recursive(integer), expected = false)
  }

  test("two disjoint recursive sources cannot upcast to a common observable recursive target") {
    val seeds = List(Type.Integer, Type.Boolean, Type.Top, Type.Bottom, Type.Variable(0))
    val bodies = seeds ++ seeds.flatMap { body =>
      List(
        Type.Record("value", body),
        Type.Arrow(Type.Variable(0), body),
        Type.Intersection(Type.Record("next", Type.Variable(0)), Type.Record("value", body)),
        Type.Recursive(body.shiftTypeVariables(1))
      )
    }
    val recursiveTypes = bodies.map(Type.Recursive(_))

    recursiveTypes.filterNot(_.isSilent()).foreach { target =>
      val sources = recursiveTypes.filter(_.isSubtypeOf(target))
      for {
        first <- sources
        second <- sources
      } {
        assert(
          !disjointness.relates(first, second),
          s"both recursive sources reach the observable target $target: $first and $second"
        )
      }
    }
  }
}
