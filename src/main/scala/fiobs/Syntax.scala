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

  def substituteFreeTermVariables(replacements: Map[String, Expr]): Expr = this match {
    case Variable(name) => replacements.getOrElse(name, this)
    case Global(_) | Literal(_) | Top => this
    case Lambda(parameter, body) =>
      Lambda(parameter, body.substituteFreeTermVariables(replacements - parameter))
    case Fix(name, annotatedType, body) =>
      Fix(name, annotatedType, body.substituteFreeTermVariables(replacements - name))
    case Application(function, argument) =>
      Application(
        function.substituteFreeTermVariables(replacements),
        argument.substituteFreeTermVariables(replacements)
      )
    case Merge(left, right) =>
      Merge(
        left.substituteFreeTermVariables(replacements),
        right.substituteFreeTermVariables(replacements)
      )
    case Annotation(expression, annotatedType) =>
      Annotation(expression.substituteFreeTermVariables(replacements), annotatedType)
    case TypeLambda(typeParameter, disjointBound, body) =>
      TypeLambda(typeParameter, disjointBound, body.substituteFreeTermVariables(replacements))
    case TypeApplication(function, argumentType) =>
      TypeApplication(function.substituteFreeTermVariables(replacements), argumentType)
    case Record(label, field) => Record(label, field.substituteFreeTermVariables(replacements))
    case Projection(record, label) => Projection(record.substituteFreeTermVariables(replacements), label)
    case Binary(operator, left, right) =>
      Binary(
        operator,
        left.substituteFreeTermVariables(replacements),
        right.substituteFreeTermVariables(replacements)
      )
    case If(condition, whenTrue, whenFalse) =>
      If(
        condition.substituteFreeTermVariables(replacements),
        whenTrue.substituteFreeTermVariables(replacements),
        whenFalse.substituteFreeTermVariables(replacements)
      )
  }
}

enum Term {
  case Variable(index: Int)
  case Global(identifier: Identifier)
  case Literal(value: PrimitiveValue)
  case Top
  case Lambda(body: Term)
  case Fix(annotatedType: Type, body: Term)
  case Application(function: Term, argument: Term)
  case Merge(left: Term, right: Term)
  case Annotation(term: Term, annotatedType: Type)
  case TypeLambda(disjointBound: Type, body: Term)
  case TypeApplication(function: Term, argumentType: Type)
  case Record(label: String, field: Term)
  case Projection(record: Term, label: String)
  case Binary(operator: BinaryOperator, left: Term, right: Term)
  case If(condition: Term, whenTrue: Term, whenFalse: Term)

  def render(maximumLineWidth: Int = 88): String = {
    FiobsRendering.render(this, maximumLineWidth)
  }
}
