package cp.fitrie

import cp.primitive.PrimitiveType

/**
 * Static observation paths with finite route prefixes and lexical recursion.
 * Recursive(body) denotes μ β. unfold · body, so every recursive binder is
 * guarded without traversing or expanding its potentially infinite paths.
 * Polymorphic variables and recursive references occupy separate scopes.
 */
enum ObservationPathInterface {
  case Divergence
  case Finite(
    pathVariables: Set[PathVariableIndex],
    terminationTypes: Set[PrimitiveType],
    routeContinuations: Map[RouteKey, ObservationPathInterface]
  )
  case Recursive(body: ObservationPathInterface)
  case RecursiveVariable(index: RecursivePathVariableIndex)
  case Union(components: Set[ObservationPathInterface])

  override def toString: String = ObservationPathInterfaceRendering.render(this)

  /**
   * Groups finite prefixes and retains unions of distinct recursive binders.
   * μ β. A ∪ε μ γ. B cannot be replaced by μ δ. (A[β ↦ δ] ∪ε B[γ ↦ δ]):
   * that replacement permits paths that switch between the two recursions.
   */
  def prefixGroupedUnion(other: ObservationPathInterface): ObservationPathInterface = {
    ObservationPathInterface.normalizedUnion(Set(this, other))
  }

  def prepend(routeKey: RouteKey): ObservationPathInterface = {
    ObservationPathInterface.route(routeKey, this)
  }

  def shiftPathVariables(by: Int, cutoff: Int = 0): ObservationPathInterface = {
    require(cutoff >= 0, "the path-variable cutoff cannot be negative")
    this match {
      case Divergence | RecursiveVariable(_) => this
      case Finite(pathVariables, terminationTypes, routeContinuations) =>
        val shiftedVariables = pathVariables.map { variable =>
          if (variable.value < cutoff) variable else PathVariableIndex(variable.value + by)
        }
        val shiftedRoutes = routeContinuations.map { case (routeKey, continuation) =>
          routeKey -> continuation.shiftPathVariables(by, cutoff + routeKey.introducedPathVariables)
        }
        Finite(shiftedVariables, terminationTypes, shiftedRoutes)
      case Recursive(body) => Recursive(body.shiftPathVariables(by, cutoff))
      case Union(components) => ObservationPathInterface.normalizedUnion(
        components.map(_.shiftPathVariables(by, cutoff))
      )
    }
  }

  def shiftRecursiveVariables(by: Int, cutoff: Int = 0): ObservationPathInterface = {
    require(cutoff >= 0, "the recursive path-variable cutoff cannot be negative")
    this match {
      case Divergence => Divergence
      case RecursiveVariable(index) if index.value >= cutoff =>
        RecursiveVariable(RecursivePathVariableIndex(index.value + by))
      case RecursiveVariable(_) => this
      case Finite(pathVariables, terminationTypes, routeContinuations) => Finite(
        pathVariables,
        terminationTypes,
        routeContinuations.map { case (routeKey, continuation) =>
          routeKey -> continuation.shiftRecursiveVariables(by, cutoff)
        }
      )
      case Recursive(body) => Recursive(body.shiftRecursiveVariables(by, cutoff + 1))
      case Union(components) => ObservationPathInterface.normalizedUnion(
        components.map(_.shiftRecursiveVariables(by, cutoff))
      )
    }
  }

  def substitutePathVariable(
    index: PathVariableIndex,
    replacement: ObservationPathInterface
  ): ObservationPathInterface = this match {
    case Divergence | RecursiveVariable(_) => this
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
    // (μ β. unfold · 𝒜)[α ↦ ℬ] = μ β. unfold · (𝒜[α ↦ ℬ↑β])
    case Recursive(body) => Recursive(body.substitutePathVariable(index, replacement.shiftRecursiveVariables(1)))
    case Union(components) => ObservationPathInterface.normalizedUnion(
      components.map(_.substitutePathVariable(index, replacement))
    )
  }

  def substituteRecursiveVariable(
    index: RecursivePathVariableIndex,
    replacement: ObservationPathInterface
  ): ObservationPathInterface = this match {
    case Divergence => Divergence
    case RecursiveVariable(variable) if variable == index => replacement
    case RecursiveVariable(variable) if variable.value > index.value =>
      RecursiveVariable(RecursivePathVariableIndex(variable.value - 1))
    case RecursiveVariable(_) => this
    case Finite(pathVariables, terminationTypes, routeContinuations) => Finite(
      pathVariables,
      terminationTypes,
      routeContinuations.map { case (routeKey, continuation) =>
        routeKey -> continuation.substituteRecursiveVariable(
          index,
          replacement.shiftPathVariables(routeKey.introducedPathVariables)
        )
      }
    )
    // (μ γ. unfold · 𝒜)[β ↦ ℬ] = μ γ. unfold · (𝒜[β + 1 ↦ ℬ↑γ])
    case Recursive(body) => Recursive(body.substituteRecursiveVariable(
      RecursivePathVariableIndex(index.value + 1),
      replacement.shiftRecursiveVariables(1)
    ))
    case Union(components) => ObservationPathInterface.normalizedUnion(
      components.map(_.substituteRecursiveVariable(index, replacement))
    )
  }

  /** Unfolds one recursive binder, retaining finite references to subsequent unfoldings. */
  def unfolded: Option[ObservationPathInterface] = this match {
    // R = μ β. unfold · 𝒜
    // ────────────────────────── Paths-Unfold
    // R / unfold = 𝒜[β ↦ R]
    case recursive @ Recursive(body) => Some(body.substituteRecursiveVariable(RecursivePathVariableIndex(0), recursive))
    case _ => None
  }

  def currentRootKeys: Option[RootKeySet] = this match {
    case Divergence => Some(RootKeySet.Universal)
    case Finite(pathVariables, terminationTypes, routeContinuations) if pathVariables.isEmpty =>
      val terminationKeys = terminationTypes.map(RootKey.Termination(_))
      val routeKeys = routeContinuations.keySet.map(_.rootKey)
      Some(RootKeySet.Finite(terminationKeys ++ routeKeys))
    case Finite(_, _, _) | RecursiveVariable(_) => None
    // ─────────────────────────────────── Front-Recursive
    // (μ β. unfold · 𝒜)• = ⟨unfold⟩
    case Recursive(_) => Some(RootKeySet.one(RouteKey.Unfold.rootKey))
    case Union(components) => components.foldLeft(Option(RootKeySet.empty)) { (accumulated, component) =>
      for {
        knownKeys <- accumulated
        componentKeys <- component.currentRootKeys
      } yield knownKeys.union(componentKeys)
    }
  }

  /** Retains variable and terminal alternatives at the root and discards every route. */
  def shallow: ObservationPathInterface = this match {
    case Divergence | RecursiveVariable(_) => this
    case Finite(pathVariables, terminationTypes, _) => Finite(pathVariables, terminationTypes, Map.empty)
    case Recursive(_) => ObservationPathInterface.exactTop
    case Union(components) => ObservationPathInterface.normalizedUnion(components.map(_.shallow))
  }

  def isWellScoped(pathVariableDepth: Int, recursiveVariableDepth: Int = 0): Boolean = {
    require(pathVariableDepth >= 0, "the path-variable scope depth cannot be negative")
    require(recursiveVariableDepth >= 0, "the recursive path-variable scope depth cannot be negative")
    this match {
      case Divergence => true
      case Finite(pathVariables, _, routeContinuations) =>
        pathVariables.forall(_.value < pathVariableDepth) &&
          routeContinuations.forall { case (routeKey, continuation) =>
            continuation.isWellScoped(pathVariableDepth + routeKey.introducedPathVariables, recursiveVariableDepth)
          }
      case Recursive(body) => body.isWellScoped(pathVariableDepth, recursiveVariableDepth + 1)
      case RecursiveVariable(index) => index.value < recursiveVariableDepth
      case Union(components) => components.forall(_.isWellScoped(pathVariableDepth, recursiveVariableDepth))
    }
  }
}

object ObservationPathInterface {
  val exactTop: ObservationPathInterface = Finite(Set.empty, Set.empty, Map.empty)

  def variable(index: PathVariableIndex): ObservationPathInterface = Finite(Set(index), Set.empty, Map.empty)

  def recursiveVariable(index: RecursivePathVariableIndex): ObservationPathInterface = RecursiveVariable(index)

  def termination(primitiveType: PrimitiveType): ObservationPathInterface = {
    Finite(Set.empty, Set(primitiveType), Map.empty)
  }

  def route(routeKey: RouteKey, continuation: ObservationPathInterface): ObservationPathInterface = {
    Finite(Set.empty, Set.empty, Map(routeKey -> continuation))
  }

  private def normalizedUnion(components: Set[ObservationPathInterface]): ObservationPathInterface = {
    def flattened(component: ObservationPathInterface): Set[ObservationPathInterface] = component match {
      case Union(nested) => nested.flatMap(flattened)
      case _ => Set(component)
    }
    val flattenedComponents = components.flatMap(flattened)
    if (flattenedComponents.contains(Divergence)) Divergence
    else {
      val finiteComponents = flattenedComponents.collect { case finite: Finite => finite }
      // (κ · 𝒜) ∪ε (κ · ℬ) = κ · (𝒜 ∪ε ℬ)
      val finiteUnion = finiteComponents.foldLeft(new Finite(Set.empty, Set.empty, Map.empty)) { (left, right) =>
        val routes = right.routeContinuations.foldLeft(left.routeContinuations) {
          case (accumulated, (routeKey, continuation)) => accumulated.updated(
            routeKey,
            accumulated.get(routeKey).fold(continuation)(_.prefixGroupedUnion(continuation))
          )
        }
        new Finite(left.pathVariables ++ right.pathVariables, left.terminationTypes ++ right.terminationTypes, routes)
      }
      val recursiveComponents = flattenedComponents.filter {
        case _: Finite => false
        case _ => true
      }
      val normalized = if (finiteUnion == exactTop) recursiveComponents else recursiveComponents + finiteUnion
      if (normalized.isEmpty) exactTop
      else if (normalized.size == 1) normalized.head
      else Union(normalized)
    }
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
    case ObservationPathInterface.Recursive(body) => s"μβ. κᵘⁿᶠᵒˡᵈ · ${render(body)}"
    case ObservationPathInterface.RecursiveVariable(index) => s"β${subscript(index.value)}"
    case ObservationPathInterface.Union(components) => components.toList.map(render).sorted.mkString("(", " ∪ε ", ")")
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
    case RouteKey.Unfold => "κᵘⁿᶠᵒˡᵈ"
    case RouteKey.Projection(label) => s"κᵖʳᵒʲ_${label.value}"
  }

  private def routeSortKey(routeKey: RouteKey): (Int, String) = routeKey match {
    case RouteKey.Application => (0, "")
    case RouteKey.TypeApplication => (1, "")
    case RouteKey.Unfold => (3, "")
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
