package cp.fitrie

import cp.primitive.PrimitiveType

/**
 * A canonical prefix-grouped set of static observation paths. Path variables
 * are static binders used only by filters and type-application payloads.
 */
enum ObservationPathInterface {
  case Divergence
  case Finite(
    pathVariables: Set[PathVariableIndex],
    terminationTypes: Set[PrimitiveType],
    routeContinuations: Map[RouteKey, ObservationPathInterface]
  )

  override def toString: String = ObservationPathInterfaceRendering.render(this)

  def prefixGroupedUnion(other: ObservationPathInterface): ObservationPathInterface = {
    (this, other) match {
      case (Divergence, _) | (_, Divergence) => Divergence
      case (left: Finite, right: Finite) =>
        val commonRoutes = left.routeContinuations.keySet.intersect(right.routeContinuations.keySet)
        val mergedCommonRoutes = commonRoutes.map { routeKey =>
          routeKey -> left.routeContinuations(routeKey).prefixGroupedUnion(
            right.routeContinuations(routeKey)
          )
        }.toMap
        Finite(
          left.pathVariables ++ right.pathVariables,
          left.terminationTypes ++ right.terminationTypes,
          left.routeContinuations ++ right.routeContinuations ++ mergedCommonRoutes
        )
    }
  }

  def prepend(routeKey: RouteKey): ObservationPathInterface = {
    ObservationPathInterface.route(routeKey, this)
  }

  def shiftPathVariables(by: Int, cutoff: Int = 0): ObservationPathInterface = {
    require(cutoff >= 0, "the path-variable cutoff cannot be negative")
    this match {
      case Divergence => Divergence
      case Finite(pathVariables, terminationTypes, routeContinuations) =>
        val shiftedVariables = pathVariables.map { variable =>
          if (variable.value < cutoff) {
            variable
          } else {
            val shiftedValue = variable.value + by
            require(
              shiftedValue >= 0,
              s"de Bruijn shift would create a negative path-variable index: ${variable.value} + $by"
            )
            PathVariableIndex(shiftedValue)
          }
        }
        val shiftedRoutes = routeContinuations.map { case (routeKey, continuation) =>
          routeKey -> continuation.shiftPathVariables(
            by,
            cutoff + routeKey.introducedPathVariables
          )
        }
        Finite(shiftedVariables, terminationTypes, shiftedRoutes)
    }
  }

  def substitutePathVariable(
    index: PathVariableIndex,
    replacement: ObservationPathInterface
  ): ObservationPathInterface = this match {
    case Divergence => Divergence
    case Finite(pathVariables, terminationTypes, routeContinuations) =>
      val containsTarget = pathVariables.contains(index)
      val substitutedVariables = pathVariables.collect {
        case variable if variable.value < index.value => variable
        case variable if variable.value > index.value => PathVariableIndex(variable.value - 1)
      }
      val substitutedRoutes = routeContinuations.map { case (routeKey, continuation) =>
        val introducedVariables = routeKey.introducedPathVariables
        routeKey -> continuation.substitutePathVariable(
          PathVariableIndex(index.value + introducedVariables),
          replacement.shiftPathVariables(introducedVariables)
        )
      }
      val remaining = Finite(substitutedVariables, terminationTypes, substitutedRoutes)
      if (containsTarget) remaining.prefixGroupedUnion(replacement) else remaining
  }

  def currentRootKeys: Option[RootKeySet] = this match {
    case Divergence => Some(RootKeySet.Universal)
    case Finite(pathVariables, terminationTypes, routeContinuations) if pathVariables.isEmpty =>
      val terminationKeys = terminationTypes.map(RootKey.Termination(_))
      val routeKeys = routeContinuations.keySet.map(_.rootKey)
      Some(RootKeySet.Finite(terminationKeys ++ routeKeys))
    case Finite(_, _, _) => None
  }

  def shallow: ObservationPathInterface = this match {
    case Divergence => Divergence
    case Finite(pathVariables, terminationTypes, _) =>
      Finite(pathVariables, terminationTypes, Map.empty)
  }

  def isWellScoped(pathVariableDepth: Int): Boolean = {
    require(pathVariableDepth >= 0, "the path-variable scope depth cannot be negative")
    this match {
      case Divergence => true
      case Finite(pathVariables, _, routeContinuations) =>
        pathVariables.forall(_.value < pathVariableDepth) &&
          routeContinuations.forall { case (routeKey, continuation) =>
            continuation.isWellScoped(pathVariableDepth + routeKey.introducedPathVariables)
          }
    }
  }
}

object ObservationPathInterface {
  val exactTop: ObservationPathInterface = Finite(Set.empty, Set.empty, Map.empty)

  def variable(index: PathVariableIndex): ObservationPathInterface = {
    Finite(Set(index), Set.empty, Map.empty)
  }

  def termination(primitiveType: PrimitiveType): ObservationPathInterface = {
    Finite(Set.empty, Set(primitiveType), Map.empty)
  }

  def route(
    routeKey: RouteKey,
    continuation: ObservationPathInterface
  ): ObservationPathInterface = {
    Finite(Set.empty, Set.empty, Map(routeKey -> continuation))
  }
}

/** A static shallow-key expression. Execution consumes only its normalized concrete result. */
enum RootKeyExpression {
  case Concrete(rootKeys: RootKeySet)
  case Front(pathInterface: ObservationPathInterface)
  case Union(left: RootKeyExpression, right: RootKeyExpression)
  case Difference(left: RootKeyExpression, right: RootKeyExpression)

  override def toString: String = this match {
    case Concrete(rootKeys) => rootKeys.toString
    case Front(pathInterface) => s"($pathInterface)•"
    case Union(left, right) => s"($left ∪ $right)"
    case Difference(left, right) => s"($left ∖ $right)"
  }

  def normalize: Option[RootKeySet] = this match {
    case Concrete(rootKeys) => Some(rootKeys)
    case Front(pathInterface) => pathInterface.currentRootKeys
    case Union(left, right) => for {
      leftKeys <- left.normalize
      rightKeys <- right.normalize
    } yield leftKeys.union(rightKeys)
    case Difference(left, right) => for {
      leftKeys <- left.normalize
      rightKeys <- right.normalize
    } yield leftKeys.difference(rightKeys)
  }

  def shiftPathVariables(by: Int, cutoff: Int = 0): RootKeyExpression = this match {
    case Concrete(_) => this
    case Front(pathInterface) => Front(pathInterface.shiftPathVariables(by, cutoff))
    case Union(left, right) => RootKeyExpression.union(
      left.shiftPathVariables(by, cutoff),
      right.shiftPathVariables(by, cutoff)
    )
    case Difference(left, right) => RootKeyExpression.difference(
      left.shiftPathVariables(by, cutoff),
      right.shiftPathVariables(by, cutoff)
    )
  }

  def substitutePathVariable(
    index: PathVariableIndex,
    replacement: ObservationPathInterface
  ): RootKeyExpression = this match {
    case Concrete(_) => this
    case Front(pathInterface) => RootKeyExpression.front(
      pathInterface.substitutePathVariable(index, replacement)
    )
    case Union(left, right) => RootKeyExpression.union(
      left.substitutePathVariable(index, replacement),
      right.substitutePathVariable(index, replacement)
    )
    case Difference(left, right) => RootKeyExpression.difference(
      left.substitutePathVariable(index, replacement),
      right.substitutePathVariable(index, replacement)
    )
  }

  def isWellScoped(pathVariableDepth: Int): Boolean = this match {
    case Concrete(_) => true
    case Front(pathInterface) => pathInterface.isWellScoped(pathVariableDepth)
    case Union(left, right) =>
      left.isWellScoped(pathVariableDepth) && right.isWellScoped(pathVariableDepth)
    case Difference(left, right) =>
      left.isWellScoped(pathVariableDepth) && right.isWellScoped(pathVariableDepth)
  }
}

object RootKeyExpression {
  def concrete(rootKeys: RootKeySet): RootKeyExpression = Concrete(rootKeys)

  def front(pathInterface: ObservationPathInterface): RootKeyExpression = {
    pathInterface.currentRootKeys match {
      case Some(rootKeys) => Concrete(rootKeys)
      case None => Front(pathInterface)
    }
  }

  def union(left: RootKeyExpression, right: RootKeyExpression): RootKeyExpression = {
    (left.normalize, right.normalize) match {
      case (Some(leftKeys), Some(rightKeys)) => Concrete(leftKeys.union(rightKeys))
      case _ if left == right => left
      case _ => Union(left, right)
    }
  }

  def difference(left: RootKeyExpression, right: RootKeyExpression): RootKeyExpression = {
    (left.normalize, right.normalize) match {
      case (Some(leftKeys), Some(rightKeys)) => Concrete(leftKeys.difference(rightKeys))
      case _ if left == right => Concrete(RootKeySet.empty)
      case _ => Difference(left, right)
    }
  }
}

private object ObservationPathInterfaceRendering {
  def render(pathInterface: ObservationPathInterface): String = pathInterface match {
    case ObservationPathInterface.Divergence => "div"
    case ObservationPathInterface.Finite(pathVariables, terminationTypes, routeContinuations) =>
      val variables = pathVariables.toList.sortBy(_.value).map(index => s"α${subscript(index.value)}")
      val terminations = terminationTypes.toList.sortBy(_.ordinal).map(_.toString.toLowerCase)
      val routes = routeContinuations.toList.sortBy { case (routeKey, _) => routeSortKey(routeKey) }
        .map { case (routeKey, continuation) => s"${routeName(routeKey)} · ${render(continuation)}" }
      val members = variables ++ terminations ++ routes
      if (members.isEmpty) "ε" else members.mkString("⟨", ", ", "⟩")
  }

  private def routeName(routeKey: RouteKey): String = routeKey match {
    case RouteKey.Application => "κᵃᵖᵖ"
    case RouteKey.TypeApplication => "κᵗᵃᵖᵖ"
    case RouteKey.Projection(label) => s"κᵖʳᵒʲ_${label.value}"
  }

  private def routeSortKey(routeKey: RouteKey): (Int, String) = routeKey match {
    case RouteKey.Application => (0, "")
    case RouteKey.TypeApplication => (1, "")
    case RouteKey.Projection(label) => (2, label.value)
  }

  private def subscript(value: Int): String = value.toString.map {
    case '0' => '₀'
    case '1' => '₁'
    case '2' => '₂'
    case '3' => '₃'
    case '4' => '₄'
    case '5' => '₅'
    case '6' => '₆'
    case '7' => '₇'
    case '8' => '₈'
    case '9' => '₉'
  }.mkString
}
