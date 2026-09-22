package cp.fiobs.runtime

import cp.fiobs.*
import cp.fiobs.binding.Binding.*

object RuntimeBinding {
  extension (term: RuntimeTerm) {
    def shiftTermVariables(by: Int, cutoff: Int = 0): RuntimeTerm = {
      if (by == 0 || term.requiredTermDepth <= cutoff) term
      else shiftFreeTermVariables(by, cutoff)
    }

    private def shiftFreeTermVariables(by: Int, cutoff: Int): RuntimeTerm = term match {
      case RuntimeTerm.Variable(index) =>
        if (index < cutoff) term else RuntimeTerm.Variable(shiftedIndex(index, by))
      case RuntimeTerm.Global(_) | RuntimeTerm.Literal(_) | RuntimeTerm.Top => term
      case RuntimeTerm.Lambda(parameterType, body, resultType) =>
        RuntimeTerm.Lambda(parameterType, body.shiftTermVariables(by, cutoff + 1), resultType)
      case RuntimeTerm.Fix(annotatedType, body) =>
        RuntimeTerm.Fix(annotatedType, body.shiftTermVariables(by, cutoff + 1))
      case RuntimeTerm.Fold(recursiveType, body) =>
        RuntimeTerm.Fold(recursiveType, body.shiftTermVariables(by, cutoff))
      case RuntimeTerm.Unfold(inner) => RuntimeTerm.Unfold(inner.shiftTermVariables(by, cutoff))
      case RuntimeTerm.Application(function, argument) =>
        RuntimeTerm.Application(function.shiftTermVariables(by, cutoff), argument.shiftTermVariables(by, cutoff))
      case RuntimeTerm.Merge(left, right) =>
        RuntimeTerm.Merge(left.shiftTermVariables(by, cutoff), right.shiftTermVariables(by, cutoff))
      case RuntimeTerm.Cast(inner, targetType) =>
        RuntimeTerm.Cast(inner.shiftTermVariables(by, cutoff), targetType)
      case RuntimeTerm.TypeLambda(disjointBound, body, resultType) =>
        RuntimeTerm.TypeLambda(disjointBound, body.shiftTermVariables(by, cutoff), resultType)
      case RuntimeTerm.TypeApplication(function, argumentType) =>
        RuntimeTerm.TypeApplication(function.shiftTermVariables(by, cutoff), argumentType)
      case RuntimeTerm.Record(label, field, fieldType) =>
        RuntimeTerm.Record(label, field.shiftTermVariables(by, cutoff), fieldType)
      case RuntimeTerm.Projection(record, label) =>
        RuntimeTerm.Projection(record.shiftTermVariables(by, cutoff), label)
      case RuntimeTerm.Binary(operator, left, right) =>
        RuntimeTerm.Binary(operator, left.shiftTermVariables(by, cutoff), right.shiftTermVariables(by, cutoff))
      case RuntimeTerm.If(condition, whenTrue, whenFalse) =>
        RuntimeTerm.If(
          condition.shiftTermVariables(by, cutoff),
          whenTrue.shiftTermVariables(by, cutoff),
          whenFalse.shiftTermVariables(by, cutoff)
        )
    }

    def substituteTerm(index: Int, replacement: RuntimeTerm): RuntimeTerm = {
      if (term.requiredTermDepth <= index) term
      else substituteFreeTerm(index, replacement)
    }

    private def substituteFreeTerm(index: Int, replacement: RuntimeTerm): RuntimeTerm = term match {
      case RuntimeTerm.Variable(variable) if variable < index => term
      case RuntimeTerm.Variable(variable) if variable == index => replacement.shiftTermVariables(index)
      case RuntimeTerm.Variable(variable) => RuntimeTerm.Variable(variable - 1)
      case RuntimeTerm.Global(_) | RuntimeTerm.Literal(_) | RuntimeTerm.Top => term
      case RuntimeTerm.Lambda(parameterType, body, resultType) =>
        RuntimeTerm.Lambda(parameterType, body.substituteTerm(index + 1, replacement), resultType)
      case RuntimeTerm.Fix(annotatedType, body) =>
        RuntimeTerm.Fix(annotatedType, body.substituteTerm(index + 1, replacement))
      case RuntimeTerm.Fold(recursiveType, body) =>
        RuntimeTerm.Fold(recursiveType, body.substituteTerm(index, replacement))
      case RuntimeTerm.Unfold(inner) => RuntimeTerm.Unfold(inner.substituteTerm(index, replacement))
      case RuntimeTerm.Application(function, argument) =>
        RuntimeTerm.Application(
          function.substituteTerm(index, replacement),
          argument.substituteTerm(index, replacement)
        )
      case RuntimeTerm.Merge(left, right) =>
        RuntimeTerm.Merge(left.substituteTerm(index, replacement), right.substituteTerm(index, replacement))
      case RuntimeTerm.Cast(inner, targetType) =>
        RuntimeTerm.Cast(inner.substituteTerm(index, replacement), targetType)
      case RuntimeTerm.TypeLambda(disjointBound, body, resultType) =>
        RuntimeTerm.TypeLambda(
          disjointBound,
          body.substituteTerm(index, replacement.shiftTypeVariables(1)),
          resultType
        )
      case RuntimeTerm.TypeApplication(function, argumentType) =>
        RuntimeTerm.TypeApplication(function.substituteTerm(index, replacement), argumentType)
      case RuntimeTerm.Record(label, field, fieldType) =>
        RuntimeTerm.Record(label, field.substituteTerm(index, replacement), fieldType)
      case RuntimeTerm.Projection(record, label) =>
        RuntimeTerm.Projection(record.substituteTerm(index, replacement), label)
      case RuntimeTerm.Binary(operator, left, right) =>
        RuntimeTerm.Binary(operator, left.substituteTerm(index, replacement), right.substituteTerm(index, replacement))
      case RuntimeTerm.If(condition, whenTrue, whenFalse) =>
        RuntimeTerm.If(
          condition.substituteTerm(index, replacement),
          whenTrue.substituteTerm(index, replacement),
          whenFalse.substituteTerm(index, replacement)
        )
    }

    def shiftTypeVariables(by: Int, cutoff: Int = 0): RuntimeTerm = {
      if (by == 0 || term.requiredTypeDepth <= cutoff) term
      else shiftFreeTypeVariables(by, cutoff)
    }

    private def shiftFreeTypeVariables(by: Int, cutoff: Int): RuntimeTerm = term match {
      case RuntimeTerm.Variable(_) | RuntimeTerm.Global(_) | RuntimeTerm.Literal(_) | RuntimeTerm.Top => term
      case RuntimeTerm.Lambda(parameterType, body, resultType) =>
        RuntimeTerm.Lambda(
          parameterType.shiftTypeVariables(by, cutoff),
          body.shiftTypeVariables(by, cutoff),
          resultType.shiftTypeVariables(by, cutoff)
        )
      case RuntimeTerm.Fix(annotatedType, body) =>
        RuntimeTerm.Fix(annotatedType.shiftTypeVariables(by, cutoff), body.shiftTypeVariables(by, cutoff))
      case RuntimeTerm.Fold(recursiveType, body) =>
        RuntimeTerm.Fold(recursiveType.shiftTypeVariables(by, cutoff), body.shiftTypeVariables(by, cutoff))
      case RuntimeTerm.Unfold(inner) => RuntimeTerm.Unfold(inner.shiftTypeVariables(by, cutoff))
      case RuntimeTerm.Application(function, argument) =>
        RuntimeTerm.Application(function.shiftTypeVariables(by, cutoff), argument.shiftTypeVariables(by, cutoff))
      case RuntimeTerm.Merge(left, right) =>
        RuntimeTerm.Merge(left.shiftTypeVariables(by, cutoff), right.shiftTypeVariables(by, cutoff))
      case RuntimeTerm.Cast(inner, targetType) =>
        RuntimeTerm.Cast(inner.shiftTypeVariables(by, cutoff), targetType.shiftTypeVariables(by, cutoff))
      case RuntimeTerm.TypeLambda(disjointBound, body, resultType) =>
        RuntimeTerm.TypeLambda(
          disjointBound.shiftTypeVariables(by, cutoff),
          body.shiftTypeVariables(by, cutoff + 1),
          resultType.shiftTypeVariables(by, cutoff + 1)
        )
      case RuntimeTerm.TypeApplication(function, argumentType) =>
        RuntimeTerm.TypeApplication(
          function.shiftTypeVariables(by, cutoff),
          argumentType.shiftTypeVariables(by, cutoff)
        )
      case RuntimeTerm.Record(label, field, fieldType) =>
        RuntimeTerm.Record(
          label,
          field.shiftTypeVariables(by, cutoff),
          fieldType.shiftTypeVariables(by, cutoff)
        )
      case RuntimeTerm.Projection(record, label) =>
        RuntimeTerm.Projection(record.shiftTypeVariables(by, cutoff), label)
      case RuntimeTerm.Binary(operator, left, right) =>
        RuntimeTerm.Binary(operator, left.shiftTypeVariables(by, cutoff), right.shiftTypeVariables(by, cutoff))
      case RuntimeTerm.If(condition, whenTrue, whenFalse) =>
        RuntimeTerm.If(
          condition.shiftTypeVariables(by, cutoff),
          whenTrue.shiftTypeVariables(by, cutoff),
          whenFalse.shiftTypeVariables(by, cutoff)
        )
    }

    def substituteType(index: Int, replacement: Type): RuntimeTerm = {
      if (term.requiredTypeDepth <= index) term
      else substituteFreeType(index, replacement)
    }

    private def substituteFreeType(index: Int, replacement: Type): RuntimeTerm = term match {
      case RuntimeTerm.Variable(_) | RuntimeTerm.Global(_) | RuntimeTerm.Literal(_) | RuntimeTerm.Top => term
      case RuntimeTerm.Lambda(parameterType, body, resultType) =>
        RuntimeTerm.Lambda(
          parameterType.substituteType(index, replacement),
          body.substituteType(index, replacement),
          resultType.substituteType(index, replacement)
        )
      case RuntimeTerm.Fix(annotatedType, body) =>
        RuntimeTerm.Fix(
          annotatedType.substituteType(index, replacement),
          body.substituteType(index, replacement)
        )
      case RuntimeTerm.Fold(recursiveType, body) =>
        RuntimeTerm.Fold(recursiveType.substituteType(index, replacement), body.substituteType(index, replacement))
      case RuntimeTerm.Unfold(inner) => RuntimeTerm.Unfold(inner.substituteType(index, replacement))
      case RuntimeTerm.Application(function, argument) =>
        RuntimeTerm.Application(
          function.substituteType(index, replacement),
          argument.substituteType(index, replacement)
        )
      case RuntimeTerm.Merge(left, right) =>
        RuntimeTerm.Merge(left.substituteType(index, replacement), right.substituteType(index, replacement))
      case RuntimeTerm.Cast(inner, targetType) =>
        RuntimeTerm.Cast(inner.substituteType(index, replacement), targetType.substituteType(index, replacement))
      case RuntimeTerm.TypeLambda(disjointBound, body, resultType) =>
        RuntimeTerm.TypeLambda(
          disjointBound.substituteType(index, replacement),
          body.substituteType(index + 1, replacement.shiftTypeVariables(1)),
          resultType.substituteType(index + 1, replacement.shiftTypeVariables(1))
        )
      case RuntimeTerm.TypeApplication(function, argumentType) =>
        RuntimeTerm.TypeApplication(
          function.substituteType(index, replacement),
          argumentType.substituteType(index, replacement)
        )
      case RuntimeTerm.Record(label, field, fieldType) =>
        RuntimeTerm.Record(
          label,
          field.substituteType(index, replacement),
          fieldType.substituteType(index, replacement)
        )
      case RuntimeTerm.Projection(record, label) =>
        RuntimeTerm.Projection(record.substituteType(index, replacement), label)
      case RuntimeTerm.Binary(operator, left, right) =>
        RuntimeTerm.Binary(operator, left.substituteType(index, replacement), right.substituteType(index, replacement))
      case RuntimeTerm.If(condition, whenTrue, whenFalse) =>
        RuntimeTerm.If(
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
