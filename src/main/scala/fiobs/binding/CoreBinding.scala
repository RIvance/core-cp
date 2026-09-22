package cp.fiobs.binding

import cp.fiobs.*
import cp.naming.Identifier

object Binding {
  extension (term: Term) {
    /** Replaces globals without capturing the free term or type variables in their replacements. */
    def substituteGlobals(replacements: Map[Identifier, Term]): Term = term match {
      case Term.Global(identifier) => replacements.getOrElse(identifier, term)
      case Term.Variable(_) | Term.Literal(_) | Term.Top => term
      case Term.Lambda(body) =>
        Term.Lambda(body.substituteGlobals(replacements.view.mapValues(_.shiftTermVariables(1)).toMap))
      case Term.Fix(annotatedType, body) =>
        Term.Fix(annotatedType, body.substituteGlobals(replacements.view.mapValues(_.shiftTermVariables(1)).toMap))
      case Term.Fold(recursiveType, body) =>
        Term.Fold(recursiveType, body.substituteGlobals(replacements))
      case Term.Unfold(recursiveType, inner) =>
        Term.Unfold(recursiveType, inner.substituteGlobals(replacements))
      case Term.Application(function, argument) =>
        Term.Application(function.substituteGlobals(replacements), argument.substituteGlobals(replacements))
      case Term.Merge(left, right) =>
        Term.Merge(left.substituteGlobals(replacements), right.substituteGlobals(replacements))
      case Term.Annotation(inner, annotatedType) =>
        Term.Annotation(inner.substituteGlobals(replacements), annotatedType)
      case Term.TypeLambda(bound, body) =>
        Term.TypeLambda(bound, body.substituteGlobals(replacements.view.mapValues(_.shiftTypeVariables(1)).toMap))
      case Term.TypeApplication(function, argumentType) =>
        Term.TypeApplication(function.substituteGlobals(replacements), argumentType)
      case Term.Record(label, field) => Term.Record(label, field.substituteGlobals(replacements))
      case Term.Projection(record, label) => Term.Projection(record.substituteGlobals(replacements), label)
      case Term.Binary(operator, left, right) =>
        Term.Binary(operator, left.substituteGlobals(replacements), right.substituteGlobals(replacements))
      case Term.If(condition, whenTrue, whenFalse) => Term.If(
          condition.substituteGlobals(replacements),
          whenTrue.substituteGlobals(replacements),
          whenFalse.substituteGlobals(replacements)
        )
    }

    def shiftTermVariables(by: Int, cutoff: Int = 0): Term = term match {
      case Term.Variable(index) =>
        if (index < cutoff) term else Term.Variable(shiftedIndex(index, by))
      case Term.Global(_) | Term.Literal(_) | Term.Top => term
      case Term.Lambda(body) => Term.Lambda(body.shiftTermVariables(by, cutoff + 1))
      case Term.Fix(annotatedType, body) =>
        Term.Fix(annotatedType, body.shiftTermVariables(by, cutoff + 1))
      case Term.Fold(recursiveType, body) =>
        Term.Fold(recursiveType, body.shiftTermVariables(by, cutoff))
      case Term.Unfold(recursiveType, inner) =>
        Term.Unfold(recursiveType, inner.shiftTermVariables(by, cutoff))
      case Term.Application(function, argument) =>
        Term.Application(function.shiftTermVariables(by, cutoff), argument.shiftTermVariables(by, cutoff))
      case Term.Merge(left, right) =>
        Term.Merge(left.shiftTermVariables(by, cutoff), right.shiftTermVariables(by, cutoff))
      case Term.Annotation(inner, annotatedType) =>
        Term.Annotation(inner.shiftTermVariables(by, cutoff), annotatedType)
      case Term.TypeLambda(bound, body) => Term.TypeLambda(bound, body.shiftTermVariables(by, cutoff))
      case Term.TypeApplication(function, argumentType) =>
        Term.TypeApplication(function.shiftTermVariables(by, cutoff), argumentType)
      case Term.Record(label, field) => Term.Record(label, field.shiftTermVariables(by, cutoff))
      case Term.Projection(record, label) => Term.Projection(record.shiftTermVariables(by, cutoff), label)
      case Term.Binary(operator, left, right) =>
        Term.Binary(operator, left.shiftTermVariables(by, cutoff), right.shiftTermVariables(by, cutoff))
      case Term.If(condition, whenTrue, whenFalse) =>
        Term.If(
          condition.shiftTermVariables(by, cutoff),
          whenTrue.shiftTermVariables(by, cutoff),
          whenFalse.shiftTermVariables(by, cutoff)
        )
    }

    def substituteTerm(index: Int, replacement: Term): Term = term match {
      case Term.Variable(variable) if variable < index => term
      case Term.Variable(variable) if variable == index => replacement.shiftTermVariables(index)
      case Term.Variable(variable) => Term.Variable(variable - 1)
      case Term.Global(_) | Term.Literal(_) | Term.Top => term
      case Term.Lambda(body) => Term.Lambda(body.substituteTerm(index + 1, replacement))
      case Term.Fix(annotatedType, body) =>
        Term.Fix(annotatedType, body.substituteTerm(index + 1, replacement))
      case Term.Fold(recursiveType, body) =>
        Term.Fold(recursiveType, body.substituteTerm(index, replacement))
      case Term.Unfold(recursiveType, inner) =>
        Term.Unfold(recursiveType, inner.substituteTerm(index, replacement))
      case Term.Application(function, argument) =>
        Term.Application(function.substituteTerm(index, replacement), argument.substituteTerm(index, replacement))
      case Term.Merge(left, right) =>
        Term.Merge(left.substituteTerm(index, replacement), right.substituteTerm(index, replacement))
      case Term.Annotation(inner, annotatedType) =>
        Term.Annotation(inner.substituteTerm(index, replacement), annotatedType)
      case Term.TypeLambda(bound, body) =>
        Term.TypeLambda(bound, body.substituteTerm(index, replacement.shiftTypeVariables(1)))
      case Term.TypeApplication(function, argumentType) =>
        Term.TypeApplication(function.substituteTerm(index, replacement), argumentType)
      case Term.Record(label, field) => Term.Record(label, field.substituteTerm(index, replacement))
      case Term.Projection(record, label) => Term.Projection(record.substituteTerm(index, replacement), label)
      case Term.Binary(operator, left, right) =>
        Term.Binary(operator, left.substituteTerm(index, replacement), right.substituteTerm(index, replacement))
      case Term.If(condition, whenTrue, whenFalse) =>
        Term.If(
          condition.substituteTerm(index, replacement),
          whenTrue.substituteTerm(index, replacement),
          whenFalse.substituteTerm(index, replacement)
        )
    }

    def shiftTypeVariables(by: Int, cutoff: Int): Term = term match {
      case Term.Variable(_) | Term.Global(_) | Term.Literal(_) | Term.Top => term
      case Term.Lambda(body) => Term.Lambda(body.shiftTypeVariables(by, cutoff))
      case Term.Fix(annotatedType, body) =>
        Term.Fix(annotatedType.shiftTypeVariables(by, cutoff), body.shiftTypeVariables(by, cutoff))
      case Term.Fold(recursiveType, body) =>
        Term.Fold(recursiveType.shiftTypeVariables(by, cutoff), body.shiftTypeVariables(by, cutoff))
      case Term.Unfold(recursiveType, inner) =>
        Term.Unfold(recursiveType.shiftTypeVariables(by, cutoff), inner.shiftTypeVariables(by, cutoff))
      case Term.Application(function, argument) =>
        Term.Application(function.shiftTypeVariables(by, cutoff), argument.shiftTypeVariables(by, cutoff))
      case Term.Merge(left, right) =>
        Term.Merge(left.shiftTypeVariables(by, cutoff), right.shiftTypeVariables(by, cutoff))
      case Term.Annotation(inner, annotatedType) =>
        Term.Annotation(inner.shiftTypeVariables(by, cutoff), annotatedType.shiftTypeVariables(by, cutoff))
      case Term.TypeLambda(bound, body) =>
        Term.TypeLambda(bound.shiftTypeVariables(by, cutoff), body.shiftTypeVariables(by, cutoff + 1))
      case Term.TypeApplication(function, argumentType) =>
        Term.TypeApplication(function.shiftTypeVariables(by, cutoff), argumentType.shiftTypeVariables(by, cutoff))
      case Term.Record(label, field) => Term.Record(label, field.shiftTypeVariables(by, cutoff))
      case Term.Projection(record, label) => Term.Projection(record.shiftTypeVariables(by, cutoff), label)
      case Term.Binary(operator, left, right) =>
        Term.Binary(operator, left.shiftTypeVariables(by, cutoff), right.shiftTypeVariables(by, cutoff))
      case Term.If(condition, whenTrue, whenFalse) =>
        Term.If(
          condition.shiftTypeVariables(by, cutoff),
          whenTrue.shiftTypeVariables(by, cutoff),
          whenFalse.shiftTypeVariables(by, cutoff)
        )
    }

    def shiftTypeVariables(by: Int): Term = shiftTypeVariables(by, 0)

    def substituteType(index: Int, replacement: Type): Term = term match {
      case Term.Variable(_) | Term.Global(_) | Term.Literal(_) | Term.Top => term
      case Term.Lambda(body) => Term.Lambda(body.substituteType(index, replacement))
      case Term.Fix(annotatedType, body) =>
        Term.Fix(annotatedType.substituteType(index, replacement), body.substituteType(index, replacement))
      case Term.Fold(recursiveType, body) =>
        Term.Fold(recursiveType.substituteType(index, replacement), body.substituteType(index, replacement))
      case Term.Unfold(recursiveType, inner) =>
        Term.Unfold(recursiveType.substituteType(index, replacement), inner.substituteType(index, replacement))
      case Term.Application(function, argument) =>
        Term.Application(function.substituteType(index, replacement), argument.substituteType(index, replacement))
      case Term.Merge(left, right) =>
        Term.Merge(left.substituteType(index, replacement), right.substituteType(index, replacement))
      case Term.Annotation(inner, annotatedType) =>
        Term.Annotation(inner.substituteType(index, replacement), annotatedType.substituteType(index, replacement))
      case Term.TypeLambda(bound, body) =>
        Term.TypeLambda(
          bound.substituteType(index, replacement),
          body.substituteType(index + 1, replacement.shiftTypeVariables(1))
        )
      case Term.TypeApplication(function, argumentType) =>
        Term.TypeApplication(
          function.substituteType(index, replacement),
          argumentType.substituteType(index, replacement)
        )
      case Term.Record(label, field) => Term.Record(label, field.substituteType(index, replacement))
      case Term.Projection(record, label) => Term.Projection(record.substituteType(index, replacement), label)
      case Term.Binary(operator, left, right) =>
        Term.Binary(operator, left.substituteType(index, replacement), right.substituteType(index, replacement))
      case Term.If(condition, whenTrue, whenFalse) =>
        Term.If(
          condition.substituteType(index, replacement),
          whenTrue.substituteType(index, replacement),
          whenFalse.substituteType(index, replacement)
        )
    }
  }

  private def shiftedIndex(index: Int, by: Int): Int = {
    val shifted = index + by
    require(shifted >= 0, s"de Bruijn shift would create negative index: $index + $by")
    shifted
  }
}
