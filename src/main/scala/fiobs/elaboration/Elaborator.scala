package cp.fiobs.elaboration

import cp.fiobs.*
import cp.util.Result

enum ElaborationError {
  case UnboundTermVariable(name: String, scope: List[String])
  case UnboundTypeVariable(name: String, scope: List[String])
}

object Elaborator {
  def elaborate(expression: Expr): Result[Term, ElaborationError] =
    elaborateExpression(expression, Nil, Nil)

  def elaborateType(inputType: SurfaceType): Result[Type, ElaborationError] =
    lowerType(inputType, Nil)

  private def indexOf[A](value: A, scope: List[A]): Option[Int] = {
    scope.indexOf(value) match {
      case -1 => None
      case index => Some(index)
    }
  }

  private def lowerType(
    inputType: SurfaceType,
    typeScope: List[String]
  ): Result[Type, ElaborationError] = {
    inputType match {
      case SurfaceType.Primitive(kind) => Result.Ok(Type.Primitive(kind))
      case SurfaceType.Top => Result.Ok(Type.Top)
      case SurfaceType.Bottom => Result.Ok(Type.Bottom)
      case SurfaceType.Variable(name) =>
        indexOf(name, typeScope)
          .map(index => Result.Ok(Type.Variable(index)))
          .getOrElse(Result.Err(ElaborationError.UnboundTypeVariable(name, typeScope)))
      case SurfaceType.Arrow(from, to) =>
        for {
          loweredFrom <- lowerType(from, typeScope)
          loweredTo <- lowerType(to, typeScope)
        } yield Type.Arrow(loweredFrom, loweredTo)
      case SurfaceType.Intersection(left, right) =>
        for {
          loweredLeft <- lowerType(left, typeScope)
          loweredRight <- lowerType(right, typeScope)
        } yield Type.Intersection(loweredLeft, loweredRight)
      case SurfaceType.ForAll(parameter, bound, body) =>
        for {
          loweredBound <- lowerType(bound, typeScope)
          loweredBody <- lowerType(body, parameter :: typeScope)
        } yield Type.ForAll(loweredBound, loweredBody)
      case SurfaceType.Record(label, fieldType) =>
        lowerType(fieldType, typeScope).map(Type.Record(label, _))
    }
  }

  private def elaborateExpression(
    expression: Expr,
    termScope: List[String],
    typeScope: List[String]
  ): Result[Term, ElaborationError] = {
    expression match {
      case Expr.Variable(name) =>
        indexOf(name, termScope)
          .map(index => Result.Ok(Term.Variable(index)))
          .getOrElse(Result.Err(ElaborationError.UnboundTermVariable(name, termScope)))
      case Expr.Global(identifier) => Result.Ok(Term.Global(identifier))
      case Expr.Literal(value) => Result.Ok(Term.Literal(value))
      case Expr.Top => Result.Ok(Term.Top)
      case Expr.Lambda(parameter, body) =>
        elaborateExpression(body, parameter :: termScope, typeScope).map(Term.Lambda(_))
      case Expr.Fix(name, annotatedType, body) =>
        for {
          loweredType <- lowerType(annotatedType, typeScope)
          loweredBody <- elaborateExpression(body, name :: termScope, typeScope)
        } yield Term.Fix(loweredType, loweredBody)
      case Expr.Application(function, argument) =>
        for {
          loweredFunction <- elaborateExpression(function, termScope, typeScope)
          loweredArgument <- elaborateExpression(argument, termScope, typeScope)
        } yield Term.Application(loweredFunction, loweredArgument)
      case Expr.Merge(left, right) =>
        for {
          loweredLeft <- elaborateExpression(left, termScope, typeScope)
          loweredRight <- elaborateExpression(right, termScope, typeScope)
        } yield Term.Merge(loweredLeft, loweredRight)
      case Expr.Annotation(term, annotatedType) =>
        for {
          loweredTerm <- elaborateExpression(term, termScope, typeScope)
          loweredType <- lowerType(annotatedType, typeScope)
        } yield Term.Annotation(loweredTerm, loweredType)
      case Expr.TypeLambda(parameter, bound, body) =>
        for {
          loweredBound <- lowerType(bound, typeScope)
          loweredBody <- elaborateExpression(body, termScope, parameter :: typeScope)
        } yield Term.TypeLambda(loweredBound, loweredBody)
      case Expr.TypeApplication(function, argumentType) =>
        for {
          loweredFunction <- elaborateExpression(function, termScope, typeScope)
          loweredType <- lowerType(argumentType, typeScope)
        } yield Term.TypeApplication(loweredFunction, loweredType)
      case Expr.Record(label, field) =>
        elaborateExpression(field, termScope, typeScope).map(Term.Record(label, _))
      case Expr.Projection(record, label) =>
        elaborateExpression(record, termScope, typeScope).map(Term.Projection(_, label))
      case Expr.Binary(operator, left, right) =>
        for {
          loweredLeft <- elaborateExpression(left, termScope, typeScope)
          loweredRight <- elaborateExpression(right, termScope, typeScope)
        } yield Term.Binary(operator, loweredLeft, loweredRight)
      case Expr.If(condition, whenTrue, whenFalse) =>
        for {
          loweredCondition <- elaborateExpression(condition, termScope, typeScope)
          loweredTrue <- elaborateExpression(whenTrue, termScope, typeScope)
          loweredFalse <- elaborateExpression(whenFalse, termScope, typeScope)
        } yield Term.If(loweredCondition, loweredTrue, loweredFalse)
    }
  }
}
