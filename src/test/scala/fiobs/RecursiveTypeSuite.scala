package cp.fiobs

import cp.fiobs.typing.{ConversionDirection, SubtypeDerivation, SubtypeRule, Subtyping}

class RecursiveTypeSuite extends munit.FunSuite {
  private val self = Type.Variable(0)

  test("recursive binders are well formed without acquiring a disjointness assumption") {
    val context = TypeContext.empty.extend(Type.Record("outer", Type.Top)).extendRecursive

    assert(context.accepts(Type.Variable(0)))
    assertEquals(context.lookup(0), None)
    assertEquals(context.lookup(1), Some(Type.Record("outer", Type.Top)))
    assert(!context.accepts(Type.Variable(2)))
    assert(Type.Recursive(self).isWellFormed())
    assert(Type.Recursive(Type.Arrow(self, Type.Integer)).isWellFormed())
    assert(!Type.Recursive(Type.Variable(1)).isWellFormed())
  }

  test("unfolding substitutes the recursive type without capturing universal binders") {
    val recursive = Type.Recursive(Type.ForAll(
      self,
      Type.Arrow(Type.Variable(0), Type.Intersection(Type.Variable(1), Type.Variable(2)))
    ))

    assertEquals(recursive.unfolded, Some(Type.ForAll(
      recursive,
      Type.Arrow(Type.Variable(0), Type.Intersection(recursive.shiftTypeVariables(1), Type.Variable(1)))
    )))
    assertEquals(Type.Integer.unfolded, None)
  }

  test("shifting and substitution preserve recursive and universal scope") {
    val input = Type.Recursive(Type.ForAll(
      Type.Variable(1),
      Type.Arrow(Type.Variable(1), Type.Variable(2))
    ))

    assertEquals(input.shiftTypeVariables(2), Type.Recursive(Type.ForAll(
      Type.Variable(3),
      Type.Arrow(Type.Variable(1), Type.Variable(4))
    )))
    assertEquals(input.substituteType(0, Type.Text), Type.Recursive(Type.ForAll(
      Type.Text,
      Type.Arrow(Type.Variable(1), Type.Text)
    )))
  }

  test("type transformations retain shared closed recursive interfaces") {
    val closed = Type.Recursive(Type.ForAll(self, Type.Arrow(Type.Variable(0), Type.Variable(1))))
    val open = Type.Record("value", Type.Variable(2))

    assertEquals(closed.requiredTypeDepth, 0L)
    assert(closed.shiftTypeVariables(5) eq closed)
    assert(closed.substituteType(0, Type.Variable(3)) eq closed)
    assert(open.shiftTypeVariables(0) eq open)
    assert(open.shiftTypeVariables(4, cutoff = 3) eq open)
    assert(open.substituteType(3, Type.Text) eq open)
    assertEquals(open.substituteType(1, Type.Text), Type.Record("value", Type.Variable(1)))
  }

  test("free type depth includes universal bounds and excludes both kinds of binder") {
    val open = Type.ForAll(Type.Variable(2), Type.Recursive(Type.Intersection(
      Type.Variable(0), Type.Arrow(Type.Variable(1), Type.Variable(4))
    )))

    assertEquals(open.requiredTypeDepth, 3L)
    assertEquals(open.shiftTypeVariables(2), Type.ForAll(Type.Variable(4), Type.Recursive(Type.Intersection(
      Type.Variable(0), Type.Arrow(Type.Variable(1), Type.Variable(6))
    ))))
    assertEquals(open.substituteType(0, Type.Text), Type.ForAll(Type.Variable(1), Type.Recursive(Type.Intersection(
      Type.Variable(0), Type.Arrow(Type.Variable(1), Type.Variable(3))
    ))))
    assertEquals(Type.Variable(Int.MaxValue).requiredTypeDepth, Int.MaxValue.toLong + 1L)
    assertEquals(Type.Variable(Int.MaxValue).shiftTypeVariables(-1), Type.Variable(Int.MaxValue - 1))
    assertEquals(Type.Variable(Int.MaxValue).substituteType(Int.MaxValue, Type.Text), Type.Text)
  }

  test("positive recursive subtyping follows arrow variance and record width") {
    val source = Type.Recursive(Type.Intersection(
      Type.Record("next", Type.Arrow(Type.Top, self)),
      Type.Record("value", Type.Integer)
    ))
    val target = Type.Recursive(Type.Record("next", Type.Arrow(Type.Integer, self)))

    assert(source.isSubtypeOf(target))
    assert(!target.isSubtypeOf(source))
    assert(source.unfolded.get.isSubtypeOf(target.unfolded.get))
  }

  test("negative recursive occurrences preserve reflexivity but reject invalid widening") {
    val first = Type.Recursive(Type.Arrow(self, Type.Integer))
    val second = Type.Recursive(Type.Arrow(self, Type.Top))

    assert(first.isSubtypeOf(first))
    assert(!first.isSubtypeOf(second))
    assert(!second.isSubtypeOf(first))
    assert(Type.Recursive(Type.Arrow(Type.Top, self)).isSubtypeOf(Type.Recursive(Type.Arrow(self, self))))
  }

  test("an inner negative recursive binder exposes constraints on its enclosing binder") {
    val inner = Type.Recursive(Type.Arrow(self, Type.Variable(1)))
    val first = Type.Recursive(Type.Arrow(Type.Top, inner))
    val second = Type.Recursive(Type.Arrow(Type.Integer, inner))

    assert(first.isSubtypeOf(first))
    assert(!first.isSubtypeOf(second))
  }

  test("nominal unfolding preserves BCD distribution inside recursive bodies") {
    val sourceBody = Type.Intersection(
      Type.Record("field", Type.Record("next", self)),
      Type.Record("field", Type.Record("value", Type.Integer))
    )
    val targetBody = Type.Record("field", Type.Intersection(
      Type.Record("next", self),
      Type.Record("value", Type.Integer)
    ))
    val source = Type.Recursive(sourceBody)
    val target = Type.Recursive(targetBody)

    assert(sourceBody.isSubtypeOf(targetBody, TypeContext.empty.extendRecursive))
    assert(targetBody.isSubtypeOf(sourceBody, TypeContext.empty.extendRecursive))
    assert(source.isSubtypeOf(target))
    assert(target.isSubtypeOf(source))
    assert(source.unfolded.get.isSubtypeOf(target.unfolded.get))
    assert(target.unfolded.get.isSubtypeOf(source.unfolded.get))
  }

  test("recursive types remain rigid and require explicit unfolding") {
    val recursive = Type.Recursive(Type.Intersection(Type.Integer, Type.Text))

    assert(recursive.isRigid)
    assertEquals(recursive.split, None)
    assert(!recursive.isSubtypeOf(recursive.unfolded.get))
    assert(!recursive.unfolded.get.isSubtypeOf(recursive))
    assert(Type.Recursive(Type.Top).isSubtypeOf(Type.Top))
    assert(!Type.Top.isSubtypeOf(Type.Recursive(Type.Top)))
  }

  test("recursive subtyping keeps recursive references separate from universal variables") {
    val source = Type.Recursive(Type.ForAll(
      Type.Top,
      Type.Record("next", Type.Arrow(Type.Variable(0), Type.Variable(1)))
    ))
    val target = Type.Recursive(Type.ForAll(
      Type.Integer,
      Type.Record("next", Type.Arrow(Type.Variable(0), Type.Variable(1)))
    ))
    val wrongScope = Type.Recursive(Type.ForAll(
      Type.Integer,
      Type.Record("next", Type.Arrow(Type.Variable(1), Type.Variable(0)))
    ))

    assert(source.isSubtypeOf(target))
    assert(!source.isSubtypeOf(wrongScope))
    assert(source.unfolded.get.isSubtypeOf(target.unfolded.get))
  }

  test("accepted recursive subtyping remains valid after unfolding across type constructors") {
    val seeds = List(Type.Integer, Type.Top, Type.Bottom, self)
    val binaryBodies = for {
      first <- seeds
      second <- seeds
      body <- List(Type.Arrow(first, second), Type.Intersection(first, second))
    } yield body
    val bodies = seeds ++ binaryBodies ++ seeds.flatMap { body =>
      List(
        Type.Record("field", body),
        Type.ForAll(Type.Top, body.shiftTypeVariables(1)),
        Type.Recursive(Type.Arrow(self, body.shiftTypeVariables(1)))
      )
    }

    for {
      firstBody <- bodies
      secondBody <- bodies
    } {
      val first = Type.Recursive(firstBody)
      val second = Type.Recursive(secondBody)
      if (first.isSubtypeOf(second)) {
        assert(
          first.unfolded.get.isSubtypeOf(second.unfolded.get),
          s"unfolding must preserve $first <: $second"
        )
      }
    }
  }

  test("finite recursive evidence records both directions required by contravariance") {
    val integerField = Type.Record("integer", Type.Integer)
    val textField = Type.Record("text", Type.Text)
    val source = Type.Recursive(Type.Arrow(self, Type.Intersection(integerField, textField)))
    val target = Type.Recursive(Type.Arrow(self, Type.Intersection(textField, integerField)))
    val proof = Subtyping(TypeContext.empty).derivation(source, target).getOrElse(fail("expected a subtype proof"))

    proof.rule match {
      case SubtypeRule.Recursive(identity, forward, Some(reverse)) =>
        assertEquals(forward.sourceType, source.unfolded.get)
        assertEquals(forward.targetType, target.unfolded.get)
        assertEquals(reverse.sourceType, target.unfolded.get)
        assertEquals(reverse.targetType, source.unfolded.get)
        forward.rule match {
          case SubtypeRule.Arrow(parameter, _) =>
            assertEquals(parameter.sourceType, target)
            assertEquals(parameter.targetType, source)
            assertEquals(parameter.rule, SubtypeRule.RecursiveReference(identity, ConversionDirection.Reverse))
          case other => fail(s"expected arrow evidence, received $other")
        }
        reverse.rule match {
          case SubtypeRule.Arrow(parameter, _) =>
            assertEquals(parameter.sourceType, source)
            assertEquals(parameter.targetType, target)
            assertEquals(parameter.rule, SubtypeRule.RecursiveReference(identity, ConversionDirection.Forward))
          case other => fail(s"expected arrow evidence, received $other")
        }
      case other => fail(s"expected bidirectional recursive evidence, received $other")
    }
  }

  test("nominal readback preserves universal scope and removes proof-local recursive binders") {
    val source = Type.ForAll(Type.Top, Type.Recursive(Type.Intersection(
      Type.Record("next", self),
      Type.Record("value", Type.Variable(1))
    )))
    val target = Type.ForAll(Type.Top, Type.Recursive(Type.Record("next", self)))
    val proof = Subtyping(TypeContext.empty).derivation(source, target).getOrElse(fail("expected a subtype proof"))

    def checkScopes(current: SubtypeDerivation, context: TypeContext): Unit = {
      assert(current.sourceType.isWellFormed(context), s"ill-scoped source ${current.sourceType}")
      assert(current.targetType.isWellFormed(context), s"ill-scoped target ${current.targetType}")
      current.rule match {
        case SubtypeRule.Universal(bound, body) =>
          checkScopes(bound, context)
          checkScopes(body, context.extend(bound.sourceType))
        case SubtypeRule.Recursive(_, forward, reverse) =>
          assertEquals(forward.sourceType, current.sourceType.unfolded.get)
          assertEquals(forward.targetType, current.targetType.unfolded.get)
          checkScopes(forward, context)
          reverse.foreach(checkScopes(_, context))
        case _ => current.rule.children.foreach(checkScopes(_, context))
      }
    }

    checkScopes(proof, TypeContext.empty)
    assertEquals(proof.shiftTypeVariables(1).sourceType, source.shiftTypeVariables(1))
    checkScopes(proof.shiftTypeVariables(1), TypeContext.empty.extendRecursive)
  }
}
