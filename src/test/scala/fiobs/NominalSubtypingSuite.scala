package cp.fiobs

import cp.fiobs.typing.{ConversionDirection, RecursiveConversionId, SubtypeDerivation, SubtypeRule, Subtyping}
import cp.primitive.PrimitiveType

/** Differential checks against nominal substitution as written in S-Rec, without conversion generation. */
class NominalSubtypingSuite extends munit.FunSuite {
  private val self = Type.Variable(0)

  test("finite evidence accepts exactly the nominal judgment over bounded recursive type families") {
    val atoms = List(Type.Integer, Type.Text, Type.Top, Type.Bottom, self)
    val bodies = (
      atoms ++
      atoms.flatMap(value => List(Type.Record("a", value), Type.Record("b", value))) ++
      (for {
        first <- atoms
        second <- atoms
        body <- List(
          Type.Arrow(first, second),
          Type.Intersection(first, second),
          Type.ForAll(first, Type.Arrow(Type.Variable(0), second.shiftTypeVariables(1))),
          Type.Recursive(Type.Arrow(Type.Variable(0), Type.Arrow(
            first.shiftTypeVariables(1), second.shiftTypeVariables(1)
          )))
        )
      } yield body)
    ).distinct
    val types = bodies.map(Type.Recursive(_))
    val subtyping = Subtyping(TypeContext.empty)

    for {
      source <- types
      target <- types
    } {
      val expected = PaperType.relates(PaperType.from(source), PaperType.from(target))
      val actual = subtyping.derivation(source, target)
      assertEquals(actual.nonEmpty, expected, s"nominal judgment differs for $source <: $target")
      actual.foreach(checkEvidence(_, TypeContext.empty, Map.empty))
    }
  }

  test("reverse conversions under nested universal and recursive binders retain lexical scope") {
    val firstFields = Type.Intersection(Type.Record("a", Type.Variable(3)), Type.Record("b", Type.Variable(2)))
    val secondFields = Type.Intersection(Type.Record("b", Type.Variable(2)), Type.Record("a", Type.Variable(3)))
    val source = Type.ForAll(Type.Top, Type.Recursive(Type.ForAll(Type.Top, Type.Recursive(
      Type.Arrow(self, Type.Arrow(Type.Variable(2), firstFields))
    ))))
    val target = Type.ForAll(Type.Top, Type.Recursive(Type.ForAll(Type.Top, Type.Recursive(
      Type.Arrow(self, Type.Arrow(Type.Variable(2), secondFields))
    ))))
    assert(PaperType.relates(PaperType.from(source), PaperType.from(target)))
    val proof = Subtyping(TypeContext.empty).derivation(source, target).getOrElse(fail("missing nominal derivation"))
    checkEvidence(proof, TypeContext.empty, Map.empty)
    checkEvidence(proof.shiftTypeVariables(2), TypeContext.empty.extendRecursive.extendRecursive, Map.empty)
  }

  private def checkEvidence(
    proof: SubtypeDerivation,
    context: TypeContext,
    conversions: Map[RecursiveConversionId, (Type, Type)]
  ): Unit = {
    assert(proof.sourceType.isWellFormed(context), s"ill-scoped source ${proof.sourceType}")
    assert(proof.targetType.isWellFormed(context), s"ill-scoped target ${proof.targetType}")
    proof.rule match {
      case SubtypeRule.Universal(bound, body) =>
        checkEvidence(bound, context, conversions)
        val shifted = conversions.view.mapValues { case (source, target) =>
          (source.shiftTypeVariables(1), target.shiftTypeVariables(1))
        }.toMap
        checkEvidence(body, context.extend(bound.sourceType), shifted)
      case SubtypeRule.Recursive(identity, forward, reverse) =>
        val extended = conversions.updated(identity, proof.sourceType -> proof.targetType)
        assertEquals(forward.sourceType, proof.sourceType.unfolded.get)
        assertEquals(forward.targetType, proof.targetType.unfolded.get)
        checkEvidence(forward, context, extended)
        reverse.foreach { backward =>
          assertEquals(backward.sourceType, proof.targetType.unfolded.get)
          assertEquals(backward.targetType, proof.sourceType.unfolded.get)
          checkEvidence(backward, context, extended)
        }
      case SubtypeRule.RecursiveReference(identity, direction) =>
        val (forwardSource, forwardTarget) = conversions.getOrElse(identity, fail(s"unbound conversion $identity"))
        val expected = direction match {
          case ConversionDirection.Forward => forwardSource -> forwardTarget
          case ConversionDirection.Reverse => forwardTarget -> forwardSource
        }
        assertEquals(proof.sourceType -> proof.targetType, expected)
      case _ => proof.rule.children.foreach(checkEvidence(_, context, conversions))
    }
  }
}

/** Named substitution makes this specification independent of the production checker's closure machinery. */
private enum PaperVariable {
  case Binder(depth: Int)
  case Fresh(depth: Int)
  case External(index: Int)
}

private enum PaperType {
  case Primitive(kind: PrimitiveType)
  case Top
  case Bottom
  case Variable(identity: PaperVariable)
  case Arrow(parameter: PaperType, result: PaperType)
  case Intersection(first: PaperType, second: PaperType)
  case Universal(variable: PaperVariable, bound: PaperType, body: PaperType)
  case Recursive(variable: PaperVariable, body: PaperType)
  case Record(label: String, field: PaperType)
  case Label(identity: Int, body: PaperType)

  def substitute(variable: PaperVariable, replacement: PaperType): PaperType = this match {
    case Primitive(_) | Top | Bottom => this
    case Variable(found) => if (found == variable) replacement else this
    case Arrow(parameter, result) =>
      Arrow(parameter.substitute(variable, replacement), result.substitute(variable, replacement))
    case Intersection(first, second) =>
      Intersection(first.substitute(variable, replacement), second.substitute(variable, replacement))
    case Universal(boundVariable, bound, body) => Universal(
      boundVariable,
      bound.substitute(variable, replacement),
      if (boundVariable == variable) body else body.substitute(variable, replacement)
    )
    case Recursive(boundVariable, body) =>
      Recursive(boundVariable, if (boundVariable == variable) body else body.substitute(variable, replacement))
    case Record(label, field) => Record(label, field.substitute(variable, replacement))
    case Label(identity, body) => Label(identity, body.substitute(variable, replacement))
  }

  def split: Option[(PaperType, PaperType)] = this match {
    case Intersection(first, second) => Some(first -> second)
    case Arrow(parameter, result) => result.split.map { case (first, second) =>
      Arrow(parameter, first) -> Arrow(parameter, second)
    }
    case Universal(variable, bound, body) => body.split.map { case (first, second) =>
      Universal(variable, bound, first) -> Universal(variable, bound, second)
    }
    case Record(label, field) => field.split.map { case (first, second) =>
      Record(label, first) -> Record(label, second)
    }
    case _ => None
  }
}

private object PaperType {
  def from(inputType: Type): PaperType = {
    def convert(current: Type, variables: List[PaperVariable]): PaperType = current match {
      case Type.Primitive(kind) => Primitive(kind)
      case Type.Top => Top
      case Type.Bottom => Bottom
      case Type.Variable(index) =>
        Variable(variables.lift(index).getOrElse(PaperVariable.External(index - variables.size)))
      case Type.Arrow(parameter, result) => Arrow(convert(parameter, variables), convert(result, variables))
      case Type.Intersection(first, second) => Intersection(convert(first, variables), convert(second, variables))
      case Type.ForAll(bound, body) =>
        val variable = PaperVariable.Binder(variables.size)
        Universal(variable, convert(bound, variables), convert(body, variable :: variables))
      case Type.Recursive(body) =>
        val variable = PaperVariable.Binder(variables.size)
        Recursive(variable, convert(body, variable :: variables))
      case Type.Record(label, field) => Record(label, convert(field, variables))
    }
    convert(inputType, Nil)
  }

  def relates(source: PaperType, target: PaperType, depth: Int = 0): Boolean = {
    if (source == target || target == Top || source == Bottom) true
    else if (target.split.exists { case (first, second) =>
      relates(source, first, depth) && relates(source, second, depth)
    }) true
    else (source, target) match {
      case (Arrow(firstParameter, firstResult), Arrow(secondParameter, secondResult)) =>
        relates(secondParameter, firstParameter, depth) && relates(firstResult, secondResult, depth)
      case (Intersection(first, second), _) => relates(first, target, depth) || relates(second, target, depth)
      case (Universal(firstVariable, firstBound, firstBody), Universal(secondVariable, secondBound, secondBody)) =>
        val variable = Variable(PaperVariable.Fresh(depth))
        relates(secondBound, firstBound, depth) && relates(
          firstBody.substitute(firstVariable, variable),
          secondBody.substitute(secondVariable, variable),
          depth + 1
        )
      // Δ, α ⊢ A[α ↦ Aᵅ] <: B[α ↦ Bᵅ]
      // ───────────────────────────────── S-Rec
      // Δ ⊢ μα. A <: μα. B
      case (Recursive(firstVariable, firstBody), Recursive(secondVariable, secondBody)) =>
        val variable = PaperVariable.Fresh(depth)
        val openedFirst = firstBody.substitute(firstVariable, Variable(variable))
        val openedSecond = secondBody.substitute(secondVariable, Variable(variable))
        relates(
          openedFirst.substitute(variable, Label(depth, openedFirst)),
          openedSecond.substitute(variable, Label(depth, openedSecond)),
          depth + 1
        )
      // Δ ⊢ A <: B
      // ───────────────── S-Label
      // Δ ⊢ Aᵅ <: Bᵅ
      case (Label(firstIdentity, firstBody), Label(secondIdentity, secondBody)) =>
        firstIdentity == secondIdentity && relates(firstBody, secondBody, depth)
      case (Record(firstLabel, firstField), Record(secondLabel, secondField)) =>
        firstLabel == secondLabel && relates(firstField, secondField, depth)
      case _ => false
    }
  }
}
