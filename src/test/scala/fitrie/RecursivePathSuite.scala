package cp.fitrie

import cp.naming.FieldLabel
import cp.primitive.PrimitiveType

class RecursivePathSuite extends munit.FunSuite {
  private val integer = ObservationPathInterface.termination(PrimitiveType.Integer)
  private val boolean = ObservationPathInterface.termination(PrimitiveType.Boolean)
  private val recursiveSelf = ObservationPathInterface.recursiveVariable(RecursivePathVariableIndex(0))
  private val polymorphicSelf = ObservationPathInterface.variable(PathVariableIndex(0))

  private def field(label: String, paths: ObservationPathInterface): ObservationPathInterface = {
    paths.prepend(RouteKey.Projection(FieldLabel(label)))
  }

  private val stream = ObservationPathInterface.Recursive(
    field("head", integer).prefixGroupedUnion(field("tail", recursiveSelf))
  )

  test("recursive paths retain a finite guarded representation with an unfold root") {
    assert(stream.isWellScoped(0))
    assertEquals(stream.currentRootKeys, Some(RootKeySet.one(RouteKey.Unfold.rootKey)))
    assertEquals(
      stream.unfolded,
      Some(field("head", integer).prefixGroupedUnion(field("tail", stream)))
    )
    assertEquals(
      ObservationPathInterface.Recursive(recursiveSelf).unfolded,
      Some(ObservationPathInterface.Recursive(recursiveSelf))
    )
  }

  test("a folded empty interface retains its unfold route") {
    val foldedEmpty = ObservationPathInterface.Recursive(ObservationPathInterface.exactTop)
    assertEquals(foldedEmpty.currentRootKeys, Some(RootKeySet.one(RouteKey.Unfold.rootKey)))
    assertEquals(foldedEmpty.unfolded, Some(ObservationPathInterface.exactTop))
    assertNotEquals(foldedEmpty.currentRootKeys, ObservationPathInterface.exactTop.currentRootKeys)
  }

  test("union preserves distinct recursive binders and finite shared prefixes") {
    val left = ObservationPathInterface.Recursive(field("left", recursiveSelf))
    val right = ObservationPathInterface.Recursive(field("right", recursiveSelf))
    val union = left.prefixGroupedUnion(right)
    assertEquals(union, ObservationPathInterface.Union(Set(left, right)))
    assertEquals(union.currentRootKeys, Some(RootKeySet.one(RouteKey.Unfold.rootKey)))
    assertEquals(
      field("tail", left).prefixGroupedUnion(field("tail", right)),
      field("tail", union)
    )
    assertNotEquals(union, ObservationPathInterface.Recursive(
      field("left", recursiveSelf).prefixGroupedUnion(field("right", recursiveSelf))
    ))
  }

  test("recursive union is associative, commutative, idempotent, and has the exact-top unit") {
    val values = List(stream, integer, boolean, field("tail", stream), ObservationPathInterface.exactTop)
    for (left <- values; right <- values) {
      assertEquals(left.prefixGroupedUnion(right), right.prefixGroupedUnion(left))
      for (third <- values) {
        assertEquals(
          left.prefixGroupedUnion(right).prefixGroupedUnion(third),
          left.prefixGroupedUnion(right.prefixGroupedUnion(third))
        )
      }
    }
    values.foreach { value =>
      assertEquals(value.prefixGroupedUnion(value), value)
      assertEquals(value.prefixGroupedUnion(ObservationPathInterface.exactTop), value)
      assertEquals(value.prefixGroupedUnion(ObservationPathInterface.Divergence), ObservationPathInterface.Divergence)
    }
  }

  test("polymorphic substitution does not capture a recursive reference") {
    val template = ObservationPathInterface.Recursive(polymorphicSelf.prefixGroupedUnion(recursiveSelf))
    val substituted = template.substitutePathVariable(PathVariableIndex(0), recursiveSelf)
    val outerReference = ObservationPathInterface.recursiveVariable(RecursivePathVariableIndex(1))
    assertEquals(substituted, ObservationPathInterface.Recursive(
      outerReference.prefixGroupedUnion(recursiveSelf)
    ))
    assert(substituted.isWellScoped(0, 1))
    assert(!substituted.isWellScoped(0))
  }

  test("recursive substitution does not capture a polymorphic variable") {
    val template = recursiveSelf.prepend(RouteKey.TypeApplication)
    assertEquals(
      template.substituteRecursiveVariable(RecursivePathVariableIndex(0), polymorphicSelf),
      ObservationPathInterface.variable(PathVariableIndex(1)).prepend(RouteKey.TypeApplication)
    )
  }

  test("recursive and polymorphic shifts respect their own binders") {
    val outerReference = ObservationPathInterface.recursiveVariable(RecursivePathVariableIndex(1))
    val nested = ObservationPathInterface.Recursive(
      recursiveSelf.prefixGroupedUnion(outerReference).prefixGroupedUnion(polymorphicSelf)
    )
    assertEquals(nested.shiftRecursiveVariables(1), ObservationPathInterface.Recursive(
      recursiveSelf
        .prefixGroupedUnion(ObservationPathInterface.recursiveVariable(RecursivePathVariableIndex(2)))
        .prefixGroupedUnion(polymorphicSelf)
    ))
    assertEquals(nested.shiftPathVariables(1), ObservationPathInterface.Recursive(
      recursiveSelf.prefixGroupedUnion(outerReference)
        .prefixGroupedUnion(ObservationPathInterface.variable(PathVariableIndex(1)))
    ))
    assert(!nested.isWellScoped(0, 1))
    assert(nested.isWellScoped(1, 1))
  }

  test("unfolding beneath a universal preserves the recursive interface's free variables") {
    val freeVariable = ObservationPathInterface.variable(PathVariableIndex(1))
    val body = recursiveSelf.prefixGroupedUnion(polymorphicSelf).prefixGroupedUnion(freeVariable)
      .prepend(RouteKey.TypeApplication)
    val recursive = ObservationPathInterface.Recursive(body)
    assertEquals(recursive.unfolded, Some(
      recursive.shiftPathVariables(1).prefixGroupedUnion(polymorphicSelf).prefixGroupedUnion(freeVariable)
        .prepend(RouteKey.TypeApplication)
    ))
    assert(!recursive.isWellScoped(0))
    assert(recursive.isWellScoped(1))
  }

  test("recursive substitution preserves an inner binder and removes the substituted outer binder") {
    val outer = ObservationPathInterface.recursiveVariable(RecursivePathVariableIndex(1))
    val beyondOuter = ObservationPathInterface.recursiveVariable(RecursivePathVariableIndex(2))
    val nested = ObservationPathInterface.Recursive(
      field("inner", recursiveSelf).prefixGroupedUnion(field("outer", outer))
        .prefixGroupedUnion(field("free", beyondOuter))
    )
    assertEquals(
      nested.substituteRecursiveVariable(RecursivePathVariableIndex(0), integer),
      ObservationPathInterface.Recursive(
        field("inner", recursiveSelf).prefixGroupedUnion(field("outer", integer))
          .prefixGroupedUnion(field("free", outer))
      )
    )
  }

  test("closed recursive arguments normalize symbolic fronts after polymorphic specialization") {
    val template = RootKeyExpression.front(polymorphicSelf.prefixGroupedUnion(boolean))
    assertEquals(template.normalize, None)
    assertEquals(
      template.substitutePathVariable(PathVariableIndex(0), stream).normalize,
      Some(RootKeySet.Finite(Set(RouteKey.Unfold.rootKey, RootKey.Termination(PrimitiveType.Boolean))))
    )
  }

  test("recursive path rendering is finite and distinguishes both variable scopes") {
    val paths = ObservationPathInterface.Recursive(polymorphicSelf.prefixGroupedUnion(recursiveSelf))
    val rendered = paths.toString
    assert(rendered.contains("μ"))
    assert(rendered.contains("α₀"))
    assert(rendered.contains("β₀"))
    assert(stream.toString.contains("tail"))
  }
}
