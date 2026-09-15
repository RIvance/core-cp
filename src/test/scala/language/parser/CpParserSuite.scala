package cp.language.parser

import cp.language.core.*
import cp.naming.{Identifier, NameReference, Namespace}
import cp.primitive.{BinaryOperator, PrimitiveValue}
import cp.util.Result

class CpParserSuite extends munit.FunSuite {
  test("module declarations and all import forms preserve absolute namespaces") {
    val source =
      """
        |module A::B
        |import module SomeOtherModule
        |import SomeModule::someImportedThing
        |import WildcardModule::*
        |def main = SomeModule::someImportedThing;
        |""".stripMargin

    CpParser.parseModule(source) match {
      case Result.Ok(Module(
            Some(namespace),
            List(
              ImportDeclaration.Module(moduleOnly),
              ImportDeclaration.Member(importedMember),
              ImportDeclaration.All(wildcardModule)
            ),
            List(Declaration.Term(
              "main",
              Expression.Variable(NameReference.Qualified(reference))
            ))
          )) =>
        assertEquals(namespace, Namespace("A", "B"))
        assertEquals(moduleOnly, Namespace("SomeOtherModule"))
        assertEquals(
          importedMember,
          Identifier(Namespace("SomeModule"), "someImportedThing")
        )
        assertEquals(wildcardModule, Namespace("WildcardModule"))
        assertEquals(reference, importedMember)
      case other => fail(s"unexpected parse result: $other")
    }
  }

  test("a qualified path starts at the root even inside a nested module") {
    CpParser.parseModule("module A::B; def main = C::value;") match {
      case Result.Ok(Module(
            _,
            _,
            List(Declaration.Term(
              "main",
              Expression.Variable(NameReference.Qualified(identifier))
            ))
          )) => assertEquals(identifier, Identifier(Namespace("C"), "value"))
      case other => fail(s"unexpected parse result: $other")
    }
  }

  test("top-level definitions accept omitted or explicit semicolon terminators") {
    val omitted =
      """
        |type Base = { value: Int }
        |def identity(value: Int): Int = value
        |impl component from Base = {
        |  value = 42
        |}
        |(Lit value).eval = value
        |def main = identity(42)
        |""".stripMargin
    val explicit =
      """
        |type Base = { value: Int; };
        |def identity(value: Int): Int = value;
        |impl component from Base = {
        |  value = 42;
        |};
        |(Lit value).eval = value;
        |def main = identity(42);
        |""".stripMargin

    val omittedResult = CpParser.parseModule(omitted)
    omittedResult match {
      case Result.Ok(_) => ()
      case other => fail(s"unexpected parse result: $other")
    }
    assertEquals(omittedResult, CpParser.parseModule(explicit))
  }

  test("block bindings accept mixed optional semicolon terminators") {
    val block =
      """
        |{
        |  let first = 40
        |  let second = 2;
        |  first + second
        |}
        |""".stripMargin
    val expected = Expression.Let(
      "first",
      None,
      Expression.Literal(PrimitiveValue.Integer(40)),
      Expression.Let(
        "second",
        None,
        Expression.Literal(PrimitiveValue.Integer(2)),
        Expression.Binary(
          BinaryOperator.Add,
          Expression.variable("first"),
          Expression.variable("second")
        )
      )
    )

    assertEquals(parseMainInitializer(block), Result.Ok(expected))
  }

  test("indented and delimited continuation lines remain in a block initializer") {
    val block =
      """
        |{
        |  let result = function(
        |    first,
        |    second
        |  )
        |  result
        |}
        |""".stripMargin
    val expected = Expression.Let(
      "result",
      None,
      Expression.Application(
        Expression.Application(Expression.variable("function"), Expression.variable("first")),
        Expression.variable("second")
      ),
      Expression.variable("result")
    )

    assertEquals(parseMainInitializer(block), Result.Ok(expected))
  }

  test("adjacent bare definitions are separated by their assignment headers") {
    val source = "first = 40 second = 2 def main = first + second"

    CpParser.parseModule(source) match {
      case Result.Ok(Module(_, _, List(
            Declaration.Term("first", _),
            Declaration.Term("second", _),
            Declaration.Term("main", _)
          ))) => ()
      case other => fail(s"unexpected parse result: $other")
    }
  }

  test("definition lookahead does not split application before equality") {
    val expected = Expression.Binary(
      BinaryOperator.Equal,
      Expression.Application(Expression.variable("predicate"), Expression.variable("value")),
      Expression.variable("expected")
    )

    assertEquals(parseMainInitializer("predicate value == expected"), Result.Ok(expected))
  }

  test("established curried declarations remain aliases of grouped declarations") {
    val established =
      """
        |-- The optional `def` keyword does not select another grammar.
        |maximum (left: Int) (right: Int) =
        |  if left > right then left else right
        |: Int;
        |def main = maximum 1 2;
        |""".stripMargin
    val grouped =
      """
        |// Added grouping and calls normalize at parse time.
        |def maximum(left: Int, right: Int): Int =
        |  if left > right then left else right;
        |def main = maximum(1, 2);
        |""".stripMargin

    assertEquals(CpParser.parseModule(established), CpParser.parseModule(grouped))
  }

  test("grouped parameters and parenthesized calls normalize to core spines") {
    val source =
      """
        |def maximum(left right: Int): Int =
        |  if left > right then left else right;
        |def main = maximum(1, 2);
        |""".stripMargin

    CpParser.parseModule(source) match {
      case Result.Ok(Module(_, _,
            Declaration.Term(
              "maximum",
              Expression.Annotation(
                Expression.Lambda(
                  ValueParameter("left", Type.Integer),
                  Expression.Lambda(
                    ValueParameter("right", Type.Integer),
                    Expression.If(
                      Expression.Binary(
                        BinaryOperator.GreaterThan,
                        Expression.Variable(NameReference.Unqualified("left")),
                        Expression.Variable(NameReference.Unqualified("right"))
                      ),
                      Expression.Variable(NameReference.Unqualified("left")),
                      Expression.Variable(NameReference.Unqualified("right"))
                    )
                  )
                ),
                Type.Arrow(Type.Integer, Type.Arrow(Type.Integer, Type.Integer))
              )
            ) :: Declaration.Term(
              "main",
              Expression.Application(
                Expression.Application(
                  Expression.Variable(NameReference.Unqualified("maximum")),
                  Expression.Literal(PrimitiveValue.Integer(1))
                ),
                Expression.Literal(PrimitiveValue.Integer(2))
              )
            ) :: Nil
          )) => ()
      case other => fail(s"unexpected parse result: $other")
    }
  }

  test("implicit-return blocks normalize directly to nested core let expressions") {
    val source =
      """
        |def area(d: Float): Float = {
        |  let half: Float = d / 2.0;
        |  half * half
        |};
        |def main = area(4.0);
        |""".stripMargin

    CpParser.parseModule(source) match {
      case Result.Ok(Module(_, _,
            Declaration.Term(
              "area",
              Expression.Annotation(
                Expression.Lambda(
                  ValueParameter("d", Type.Decimal),
                  Expression.Let(
                    "half",
                    Some(Type.Decimal),
                    Expression.Binary(
                      BinaryOperator.Divide,
                      Expression.Variable(NameReference.Unqualified("d")),
                      Expression.Literal(PrimitiveValue.Decimal(two))
                    ),
                    Expression.Binary(
                      BinaryOperator.Multiply,
                      Expression.Variable(NameReference.Unqualified("half")),
                      Expression.Variable(NameReference.Unqualified("half"))
                    )
                  )
                ),
                Type.Arrow(Type.Decimal, Type.Decimal)
              )
            ) :: Declaration.Term("main", _) :: Nil
          )) if two == BigDecimal("2.0") => ()
      case other => fail(s"unexpected parse result: $other")
    }
  }

  test("let rec normalizes to an explicitly recursive core binding") {
    assertEquals(
      parseMainInitializer("let rec loop: Int = loop in loop"),
      Result.Ok(Expression.RecursiveLet(
        "loop",
        Type.Integer,
        Expression.variable("loop"),
        Expression.variable("loop")
      ))
    )
  }

  test("the rec keyword does not consume an ordinary binding-name prefix") {
    parseMainInitializer("let record = 42 in record") match {
      case Result.Ok(Expression.Let(
            "record",
            None,
            _,
            Expression.Variable(NameReference.Unqualified("record"))
          )) => ()
      case other => fail(s"unexpected parse result: $other")
    }
  }

  test("let rec requires a declared binding type") {
    CpParser.parseModule("def main = let rec loop = loop in loop;") match {
      case Result.Err(_: ParsingError.Syntax) => ()
      case other => fail(s"expected syntax failure, received: $other")
    }
  }

  test("angle-bracket signature arguments preserve dependency pairs") {
    val source =
      """
        |type ExpSig<Exp> = { Lit: Int -> Exp; };
        |def consume(value: ExpSig<Eval % Print>): Top = top;
        |def main = top;
        |""".stripMargin

    CpParser.parseModule(source) match {
      case Result.Ok(Module(_, _,
            Declaration.TypeSignature("ExpSig", List("Exp"), Type.Top, _) ::
            Declaration.Term(
              "consume",
              Expression.Annotation(
                Expression.Lambda(
                  ValueParameter(
                    "value",
                    Type.SignatureApplication(
                      NameReference.Unqualified("ExpSig"),
                      List(SortArgument.Dependency(
                        Type.Variable("Eval"),
                        Type.Variable("Print")
                      ))
                    )
                  ),
                  Expression.Top
                ),
                _
              )
            ) :: Declaration.Term("main", Expression.Top) :: Nil
          )) => ()
      case other => fail(s"unexpected parse result: $other")
    }
  }

  test("ordinary square and established at-sign applications have the same core node") {
    val square = parseMainInitializer("function[Argument]")
    val established = parseMainInitializer("function @Argument")

    val expected = Expression.TypeApplication(Expression.variable("function"), Type.Variable("Argument"))
    assertEquals(square, Result.Ok(expected))
    assertEquals(established, Result.Ok(expected))
  }

  test("all declaration binder spellings normalize to one disjoint type abstraction") {
    val complete = CpParser.parseModule(
      "identity Element (value: Element) where Element * Int = value; def main = top;"
    )
    val concise = CpParser.parseModule(
      "identity[Element * Int](value: Element) = value; def main = top;"
    )
    val established = CpParser.parseModule(
      "identity (Element * Int) (value: Element) = value; def main = top;"
    )

    assertEquals(complete, concise)
    assertEquals(concise, established)
  }

  test("preferred, Unicode-preferred, and raw abstractions normalize to one core expression") {
    val preferred = parseMainInitializer(
      "[type Element * Int] => (value: Element) => value"
    )
    val unicodePreferred = parseMainInitializer(
      "Λ[type Element * Int] => (value: Element) => value"
    )
    val raw = parseMainInitializer(
      "Λ (Element * Int) . λ(value: Element) . value"
    )

    assertEquals(preferred, unicodePreferred)
    assertEquals(unicodePreferred, raw)
  }

  test("forall and Unicode forall normalize in preferred and raw forms") {
    val preferred = CpParser.parseModule(
      "type Universal = forall [type Element * Int] -> Element -> (Element & Int); def main = top;"
    )
    val unicodePreferred = CpParser.parseModule(
      "type Universal = ∀[type Element * Int] -> Element -> (Element & Int); def main = top;"
    )
    val raw = CpParser.parseModule(
      "type Universal = forall (Element * Int) . Element -> (Element & Int); def main = top;"
    )
    val unicodeRaw = CpParser.parseModule(
      "type Universal = ∀ (Element * Int) . Element -> (Element & Int); def main = top;"
    )

    assertEquals(preferred, unicodePreferred)
    assertEquals(unicodePreferred, raw)
    assertEquals(raw, unicodeRaw)
  }

  test("postfix clauses compose into core application, type application, and projection spines") {
    val expected = Expression.Projection(
      Expression.Application(
        Expression.TypeApplication(
          Expression.TypeApplication(
            Expression.variable("build"),
            Type.Variable("Input")
          ),
          Type.Variable("Output")
        ),
        Expression.Literal(PrimitiveValue.Integer(1))
      ),
      "value"
    )

    assertEquals(parseMainInitializer("build[Input, Output](1).value"), Result.Ok(expected))
  }

  test("new consumes the established whitespace application spine") {
    val established = parseMainInitializer("new Constructor 1")
    val parenthesized = parseMainInitializer("new Constructor(1)")
    val expected = Expression.New(Expression.Application(
      Expression.variable("Constructor"),
      Expression.Literal(PrimitiveValue.Integer(1))
    ))

    assertEquals(established, Result.Ok(expected))
    assertEquals(parenthesized, Result.Ok(expected))
  }

  test("ordinary type applications fold from left to right in both spellings") {
    val square = parseMainInitializer("function[First, Second]")
    val established = parseMainInitializer("function @First @Second")

    assertEquals(square, established)
  }

  test("impl normalization preserves signature angles and lowers inherited square application") {
    val source =
      """
        |type MulSig<Exp> = { test: Exp; };
        |impl expMul[Exp]
        |  from MulSig<Exp>
        |  inherits expAdd[Exp] = {
        |  override test = top;
        |};
        |def main = top;
        |""".stripMargin

    CpParser.parseModule(source) match {
      case Result.Ok(Module(_, _,
            _ :: Declaration.Term(
              "expMul",
              Expression.TypeLambda(
                TypeBinder("Exp", Type.Top),
                Expression.Trait(
                  "self",
                  Type.SignatureApplication(
                    NameReference.Unqualified("MulSig"),
                    List(SortArgument.TypeArgument(Type.Variable("Exp")))
                  ),
                  Type.Top,
                  Expression.TypeApplication(
                    Expression.Variable(NameReference.Unqualified("expAdd")),
                    Type.Variable("Exp")
                  ),
                  Expression.Record(List(Member.Field("test", Expression.Top)))
                )
              )
            ) :: Declaration.Term("main", Expression.Top) :: Nil
          )) => ()
      case other => fail(s"unexpected parse result: $other")
    }
  }

  test("Trait brackets and trait self brackets remain distinct core constructs") {
    val source =
      """
        |def make(base: Trait[Required, Provided]): Trait[Required, Provided] =
        |  trait [self: Required] implements Provided inherits base => {};
        |def main = top;
        |""".stripMargin

    CpParser.parseModule(source) match {
      case Result.Ok(Module(_, _,
            Declaration.Term(
              "make",
              Expression.Annotation(
                Expression.Lambda(
                  ValueParameter("base", Type.Trait(required, provided)),
                  Expression.Trait(
                    "self",
                    selfRequirement,
                    implemented,
                    Expression.Variable(NameReference.Unqualified("base")),
                    _
                  )
                ),
                _
              )
            ) :: Declaration.Term("main", Expression.Top) :: Nil
          )) =>
        assertEquals(required, Type.Variable("Required"))
        assertEquals(provided, Type.Variable("Provided"))
        assertEquals(selfRequirement, required)
        assertEquals(implemented, provided)
      case other => fail(s"unexpected parse result: $other")
    }
  }

  test("method patterns remain CP core members") {
    CpParser.parseModule("(Lit value).eval = value; def main = top;") match {
      case Result.Ok(Module(_, _,
            List(Declaration.Method(Member.MethodPattern(
              "Lit",
              List(PatternParameter("value", None)),
              None,
              "eval",
              Nil,
              Expression.Variable(NameReference.Unqualified("value"))
            )), Declaration.Term("main", Expression.Top))
          )) => ()
      case other => fail(s"unexpected parse result: $other")
    }
  }

  test("blocks reject discarded non-final expressions") {
    CpParser.parseModule("def main = { 1; 2 };") match {
      case Result.Err(_: ParsingError.Syntax) => ()
      case other => fail(s"expected syntax failure, received: $other")
    }
  }

  test("declarations reject two result annotations") {
    CpParser.parseModule("def identity(value: Int): Int = value : Int;") match {
      case Result.Err(_: ParsingError.Syntax) => ()
      case other => fail(s"expected syntax failure, received: $other")
    }
  }

  test("record members accept omitted semicolon terminators") {
    val source = "def main = { left = 40 right = 2 }"

    CpParser.parseModule(source) match {
      case Result.Ok(Module(_, _, List(Declaration.Term(
            "main",
            Expression.Record(List(Member.Field("left", _), Member.Field("right", _)))
          )))) => ()
      case other => fail(s"unexpected parse result: $other")
    }
  }

  test("member lookahead separates adjacent field and method definitions") {
    val source =
      """
        |def main = {
        |  fallback = 0
        |  (Lit value).eval = value
        |  (Add left right).eval = left.eval + right.eval
        |}
        |""".stripMargin

    CpParser.parseModule(source) match {
      case Result.Ok(Module(_, _, List(Declaration.Term(
            "main",
            Expression.Record(List(
              Member.Field("fallback", _),
              _: Member.MethodPattern,
              _: Member.MethodPattern
            ))
          )))) => ()
      case other => fail(s"unexpected parse result: $other")
    }
  }

  test("term angle brackets are not accepted as ordinary type application") {
    CpParser.parseModule("def main = term<Argument>;") match {
      case Result.Err(_: ParsingError.Syntax) => ()
      case other => fail(s"expected syntax failure, received: $other")
    }
  }

  test("a declared signature cannot be applied with expression square brackets") {
    val source =
      """
        |type Signature<Sort> = { field: Sort; };
        |def main = Signature[Argument];
        |""".stripMargin

    assertEquals(
      CpParser.parseModule(source),
      Result.Err(ParsingError.SignatureUsedAsOrdinaryTypeApplication("Signature"))
    )
  }

  test("compilation units reject top-level expressions") {
    CpParser.parseModule("42") match {
      case Result.Err(_: ParsingError.Syntax) => ()
      case other => fail(s"expected syntax failure, received: $other")
    }
  }

  private def parseMainInitializer(source: String): Result[Expression, ParsingError] = {
    CpParser.parseModule(s"def main = $source;").map {
      case Module(_, _, List(Declaration.Term("main", initializer))) => initializer
      case module => throw new IllegalStateException(s"unexpected generated module: $module")
    }
  }
}
