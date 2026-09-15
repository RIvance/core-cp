package cp.naming

/** An absolute namespace path. Relative namespace interpretation is intentionally absent. */
final case class Namespace private (segments: Vector[String]) {
  def isRoot: Boolean = segments.isEmpty

  def identifier(name: String): Identifier = Identifier(this, name)

  def render: String = segments.mkString("::")

  override def toString: String = if (isRoot) "{root}" else render
}

object Namespace {
  val Root: Namespace = Namespace(Vector.empty)

  def apply(firstSegment: String, remainingSegments: String*): Namespace = {
    from(firstSegment +: remainingSegments)
  }

  def from(segments: Iterable[String]): Namespace = {
    val completeSegments = segments.toVector
    require(completeSegments.forall(_.nonEmpty), "namespace segments cannot be empty")
    Namespace(completeSegments)
  }
}

/** A module-level identity whose scope is always an absolute namespace. */
final case class Identifier(scope: Namespace, name: String) {
  require(name.nonEmpty, "an identifier name cannot be empty")

  def render: String = if (scope.isRoot) name else s"${scope.render}::$name"

  override def toString: String = render
}

/** A source reference is either unqualified or explicitly absolute. */
enum NameReference {
  case Unqualified(name: String)
  case Qualified(identifier: Identifier)

  def render: String = this match {
    case Unqualified(name) => name
    case Qualified(identifier) => identifier.render
  }
}

/** An open record-field identifier shared by source and target representations. */
final case class FieldLabel(value: String) {
  override def toString: String = value
}
