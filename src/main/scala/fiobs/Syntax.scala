package cp.fiobs

import cp.naming.Identifier
import cp.primitive.{BinaryOperator, PrimitiveValue}
import cp.util.Result

enum Expr {
  case Variable(name: String)
  case Global(identifier: Identifier)
  case Literal(value: PrimitiveValue)
  case Top
  case Lambda(parameter: String, body: Expr)
  case Fix(name: String, annotatedType: SurfaceType, body: Expr)
  case Fold(recursiveType: SurfaceType, body: Expr)
  case Unfold(recursiveType: SurfaceType, term: Expr)
  case Application(function: Expr, argument: Expr)
  case Merge(left: Expr, right: Expr)
  case Annotation(expression: Expr, annotatedType: SurfaceType)
  case TypeLambda(typeParameter: String, disjointBound: SurfaceType, body: Expr)
  case TypeApplication(function: Expr, argumentType: SurfaceType)
  case Record(label: String, field: Expr)
  case Projection(record: Expr, label: String)
  case Binary(operator: BinaryOperator, left: Expr, right: Expr)
  case If(condition: Expr, whenTrue: Expr, whenFalse: Expr)

  def toTerm: Result[Term, elaboration.ElaborationError] = elaboration.Elaborator.elaborate(this)
}

enum Term {
  case Variable(index: Int)
  case Global(identifier: Identifier)
  case Literal(value: PrimitiveValue)
  case Top
  case Lambda(body: Term)
  case Fix(annotatedType: Type, body: Term)
  case Fold(recursiveType: Type, body: Term)
  case Unfold(recursiveType: Type, term: Term)
  case Application(function: Term, argument: Term)
  case Merge(left: Term, right: Term)
  case Annotation(term: Term, annotatedType: Type)
  case TypeLambda(disjointBound: Type, body: Term)
  case TypeApplication(function: Term, argumentType: Type)
  case Record(label: String, field: Term)
  case Projection(record: Term, label: String)
  case Binary(operator: BinaryOperator, left: Term, right: Term)
  case If(condition: Term, whenTrue: Term, whenFalse: Term)

  /** Module dependencies after all lexical bindings and implicit opens have been resolved. */
  def referencedGlobals: Set[Identifier] = this match {
    case Global(identifier) => Set(identifier)
    case Variable(_) | Literal(_) | Top => Set.empty
    case Lambda(body) => body.referencedGlobals
    case Fix(_, body) => body.referencedGlobals
    case Fold(_, body) => body.referencedGlobals
    case Unfold(_, term) => term.referencedGlobals
    case Application(function, argument) => function.referencedGlobals ++ argument.referencedGlobals
    case Merge(left, right) => left.referencedGlobals ++ right.referencedGlobals
    case Annotation(term, _) => term.referencedGlobals
    case TypeLambda(_, body) => body.referencedGlobals
    case TypeApplication(function, _) => function.referencedGlobals
    case Record(_, field) => field.referencedGlobals
    case Projection(record, _) => record.referencedGlobals
    case Binary(_, left, right) => left.referencedGlobals ++ right.referencedGlobals
    case If(condition, whenTrue, whenFalse) =>
      condition.referencedGlobals ++ whenTrue.referencedGlobals ++ whenFalse.referencedGlobals
  }

  def render(maximumLineWidth: Int = 88): String = {
    FiobsRendering.render(this, maximumLineWidth)
  }
}
