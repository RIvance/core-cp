package cp.fiobs.typing

import cp.fiobs.Type

/** Lexical identity of a conversion introduced by S-Rec, scoped by its derivation node. */
final case class RecursiveConversionId(depth: Int)

enum ConversionDirection {
  case Forward, Reverse
}

/** Subtyping evidence shared by the checker and compilers; recursive references are finite backedges. */
final case class SubtypeDerivation(sourceType: Type, targetType: Type, rule: SubtypeRule[SubtypeDerivation]) {
  def shiftTypeVariables(by: Int, cutoff: Int = 0): SubtypeDerivation = {
    val shiftedRule = rule match {
      case SubtypeRule.Universal(bound, body) => SubtypeRule.Universal(
        bound.shiftTypeVariables(by, cutoff),
        body.shiftTypeVariables(by, cutoff + 1)
      )
      case other => other.mapChildren(_.shiftTypeVariables(by, cutoff))
    }
    SubtypeDerivation(
      sourceType.shiftTypeVariables(by, cutoff),
      targetType.shiftTypeVariables(by, cutoff),
      shiftedRule
    )
  }
}

/** The child parameter also permits scoped nominal evidence before its types are read back. */
enum SubtypeRule[+Child] {
  case Identity
  case Top
  case Bottom
  case Arrow(parameter: Child, result: Child)
  case Universal(bound: Child, body: Child)
  case Record(field: Child)
  case SelectLeft(selected: Child)
  case SelectRight(selected: Child)
  case Split(first: Child, second: Child)
  case Recursive(identity: RecursiveConversionId, forwardBody: Child, reverseBody: Option[Child])
  case RecursiveReference(identity: RecursiveConversionId, direction: ConversionDirection)

  def children: List[Child] = this match {
    case Identity | Top | Bottom | RecursiveReference(_, _) => Nil
    case Arrow(parameter, result) => List(parameter, result)
    case Universal(bound, body) => List(bound, body)
    case Record(field) => List(field)
    case SelectLeft(selected) => List(selected)
    case SelectRight(selected) => List(selected)
    case Split(first, second) => List(first, second)
    case Recursive(_, forwardBody, reverseBody) => forwardBody :: reverseBody.toList
  }

  def mapChildren[Mapped](transform: Child => Mapped): SubtypeRule[Mapped] = this match {
    case Identity => Identity
    case Top => Top
    case Bottom => Bottom
    case Arrow(parameter, result) => Arrow(transform(parameter), transform(result))
    case Universal(bound, body) => Universal(transform(bound), transform(body))
    case Record(field) => Record(transform(field))
    case SelectLeft(selected) => SelectLeft(transform(selected))
    case SelectRight(selected) => SelectRight(transform(selected))
    case Split(first, second) => Split(transform(first), transform(second))
    case Recursive(identity, forwardBody, reverseBody) =>
      Recursive(identity, transform(forwardBody), reverseBody.map(transform))
    case RecursiveReference(identity, direction) => RecursiveReference(identity, direction)
  }
}
