package cp.fiobs

import cp.naming.Namespace
import cp.primitive.PrimitiveValue

class FiobsRenderingSuite extends munit.FunSuite {
  test("render elaborated de Bruijn binders with readable scoped names") {
    val term = Term.Lambda(Term.Lambda(
      Term.Application(Term.Variable(1), Term.Variable(0))
    ))

    assertEquals(term.render(), "λx₀. λx₁. x₀ x₁")
  }

  test("render the complete Fiobs source term family") {
    val main = Namespace("Main").identifier("main")
    val term = Term.TypeLambda(
      Type.Integer,
      Term.Annotation(
        Term.If(
          Term.Literal(PrimitiveValue.Boolean(true)),
          Term.Record("value", Term.Global(main)),
          Term.Record("value", Term.Top)
        ),
        Type.Record("value", Type.Top)
      )
    )

    val rendered = term.render(34)

    assert(rendered.contains("Λ(α₀ * Int)."))
    assert(rendered.contains("if true"))
    assert(rendered.contains("{value ="))
    assert(rendered.contains("global Main::main"))
    assert(rendered.contains("{value : ⊤}"))
    assert(rendered.contains('\n'))
  }

  test("applications and projections distinguish unfolding their receiver from unfolding their result") {
    val recursive = Type.Recursive(Type.Arrow(Type.Integer, Type.Integer))
    val receiver = Term.Variable(0)
    val argument = Term.Literal(PrimitiveValue.Integer(42))
    val unfold = Term.Unfold(recursive, receiver)

    assertEquals(Term.Application(unfold, argument).render(), "(unfold[μα₀. Int → Int] x₀) 42")
    assertEquals(
      Term.Unfold(recursive, Term.Application(receiver, argument)).render(),
      "unfold[μα₀. Int → Int] (x₀ 42)"
    )
    val record = Term.Unfold(Type.Recursive(Type.Record("value", Type.Integer)), receiver)
    assertEquals(Term.Projection(record, "value").render(), "(unfold[μα₀. {value : Int}] x₀).value")
  }
}
