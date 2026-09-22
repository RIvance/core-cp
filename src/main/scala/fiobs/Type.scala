package cp.fiobs

import cp.primitive.PrimitiveType
import cp.fiobs.typing.{ApplicativeDistribution, RouteAnalysis, Subtyping}

enum SurfaceType {
  case Primitive(kind: PrimitiveType)
  case Top
  case Bottom
  case Variable(name: String)
  case Arrow(from: SurfaceType, to: SurfaceType)
  case Intersection(left: SurfaceType, right: SurfaceType)
  case ForAll(typeParameter: String, disjointBound: SurfaceType, body: SurfaceType)
  case Recursive(typeParameter: String, body: SurfaceType)
  case Record(label: String, fieldType: SurfaceType)
}

object SurfaceType {
  val Integer: SurfaceType = Primitive(PrimitiveType.Integer)
  val Decimal: SurfaceType = Primitive(PrimitiveType.Decimal)
  val Boolean: SurfaceType = Primitive(PrimitiveType.Boolean)
  val Text: SurfaceType = Primitive(PrimitiveType.Text)
  val Unit: SurfaceType = Primitive(PrimitiveType.Unit)
}

final case class TypeSplit(first: Type, second: Type)

enum ApplicableForm {
  case Arrow, Universal
}

private enum TypeBinding {
  case Disjoint(bound: Type)
  case Recursive

  def shifted: TypeBinding = this match {
    case Disjoint(bound) => Disjoint(bound.shiftTypeVariables(1))
    case Recursive => Recursive
  }
}

final case class TypeContext private (private val bindings: List[TypeBinding]) {
  /** Returns only declared disjointness bounds; a recursive variable has no such assumption. */
  def lookup(index: Int): Option[Type] = bindings.lift(index).collect {
    case TypeBinding.Disjoint(bound) => bound
  }

  def contains(index: Int): Boolean = bindings.isDefinedAt(index)

  def extend(bound: Type): TypeContext =
    TypeContext(TypeBinding.Disjoint(bound.shiftTypeVariables(1)) :: bindings.map(_.shifted))

  def extendRecursive: TypeContext = TypeContext(TypeBinding.Recursive :: bindings.map(_.shifted))

  def accepts(inputType: Type): Boolean = inputType match {
    // ───────────── WF-Primitive
    // Δ ⊢ p ✔
    //
    // ─────── WF-Top
    // Δ ⊢ ⊤ ✔
    //
    // ─────── WF-Bot
    // Δ ⊢ ⊥ ✔
    case Type.Primitive(_) | Type.Top | Type.Bottom => true

    // α ∈ dom(Δ)
    // ───────── WF-Var
    // Δ ⊢ α ✔
    case Type.Variable(index) => contains(index)

    // Δ ⊢ A ✔    Δ ⊢ B ✔
    // ─────────────────── WF-Arr
    // Δ ⊢ A ⇾ B ✔
    case Type.Arrow(parameterType, resultType) => accepts(parameterType) && accepts(resultType)

    // Δ ⊢ A ✔    Δ ⊢ B ✔
    // ─────────────────── WF-And
    // Δ ⊢ A & B ✔
    case Type.Intersection(leftType, rightType) => accepts(leftType) && accepts(rightType)

    // Δ ⊢ A ✔    Δ, α ∗ A ⊢ B ✔
    // ──────────────────────────── WF-All
    // Δ ⊢ ∀(α ∗ A). B ✔
    case Type.ForAll(disjointBound, bodyType) =>
      accepts(disjointBound) && extend(disjointBound).accepts(bodyType)

    // Δ, α ⊢ A ✔
    // ───────────── WF-Rec
    // Δ ⊢ μα. A ✔
    case Type.Recursive(bodyType) => extendRecursive.accepts(bodyType)

    // Δ ⊢ A ✔
    // ─────────────── WF-Rcd
    // Δ ⊢ {ℓ : A} ✔
    case Type.Record(_, fieldType) => accepts(fieldType)
  }
}

object TypeContext {
  // ───────── WF-TCtx-Empty
  // ⊢ ∅ᵀ ✔
  val empty: TypeContext = TypeContext(Nil)
}

enum Type {
  case Primitive(kind: PrimitiveType)
  case Top
  case Bottom
  case Variable(index: Int)
  case Arrow(from: Type, to: Type)
  case Intersection(left: Type, right: Type)
  case ForAll(disjointBound: Type, body: Type)
  case Recursive(body: Type)
  case Record(label: String, fieldType: Type)

  def render(maximumLineWidth: Int = 88): String = {
    FiobsRendering.render(this, maximumLineWidth)
  }

  /**
   * One plus the greatest free type-variable index, or zero for a closed type.
   * Recursive casts repeatedly revisit shared closed interfaces; retaining this intrinsic
   * scope measure avoids rebuilding them during shifts and substitutions.
   */
  private[fiobs] lazy val requiredTypeDepth: Long = this match {
    case Primitive(_) | Top | Bottom => 0L
    case Variable(index) => index.toLong + 1L
    case Arrow(from, to) => from.requiredTypeDepth.max(to.requiredTypeDepth)
    case Intersection(left, right) => left.requiredTypeDepth.max(right.requiredTypeDepth)
    case ForAll(bound, body) => bound.requiredTypeDepth.max((body.requiredTypeDepth - 1L).max(0L))
    case Recursive(body) => (body.requiredTypeDepth - 1L).max(0L)
    case Record(_, fieldType) => fieldType.requiredTypeDepth
  }

  def shiftTypeVariables(by: Int, cutoff: Int = 0): Type = {
    if (by == 0 || requiredTypeDepth <= cutoff) this
    else this match {
      case Primitive(_) | Top | Bottom => this
      case Variable(index) =>
        if (index < cutoff) {
          this
        } else {
          val shiftedIndex = index + by
          require(shiftedIndex >= 0, s"de Bruijn shift would create negative index: $index + $by")
          Variable(shiftedIndex)
        }
      case Arrow(from, to) =>
        Arrow(from.shiftTypeVariables(by, cutoff), to.shiftTypeVariables(by, cutoff))
      case Intersection(left, right) =>
        Intersection(left.shiftTypeVariables(by, cutoff), right.shiftTypeVariables(by, cutoff))
      case ForAll(bound, body) =>
        ForAll(bound.shiftTypeVariables(by, cutoff), body.shiftTypeVariables(by, cutoff + 1))
      case Recursive(body) => Recursive(body.shiftTypeVariables(by, cutoff + 1))
      case Record(label, fieldType) => Record(label, fieldType.shiftTypeVariables(by, cutoff))
    }
  }

  def substituteType(index: Int, replacement: Type): Type = {
    if (requiredTypeDepth <= index) this
    else this match {
      case Primitive(_) | Top | Bottom => this
      case Variable(variable) if variable < index => this
      case Variable(variable) if variable == index => replacement
      case Variable(variable) => Variable(variable - 1)
      case Arrow(from, to) =>
        Arrow(from.substituteType(index, replacement), to.substituteType(index, replacement))
      case Intersection(left, right) =>
        Intersection(left.substituteType(index, replacement), right.substituteType(index, replacement))
      case ForAll(bound, body) =>
        ForAll(
          bound.substituteType(index, replacement),
          body.substituteType(index + 1, replacement.shiftTypeVariables(1))
        )
      case Recursive(body) => Recursive(body.substituteType(index + 1, replacement.shiftTypeVariables(1)))
      case Record(label, fieldType) => Record(label, fieldType.substituteType(index, replacement))
    }
  }

  /** One capture-avoiding unfolding; this does not identify a recursive type with its body. */
  def unfolded: Option[Type] = this match {
    case Recursive(body) => Some(body.substituteType(0, this))
    case _ => None
  }

  def split: Option[TypeSplit] = this match {
    // ───────────── Spl-And
    // A & B ⤇ A ‖ B
    case Intersection(leftType, rightType) => Some(TypeSplit(leftType, rightType))

    // B ⤇ C ‖ D
    // ───────────────────────────── Spl-Arr
    // A ⇾ B ⤇ (A ⇾ C) ‖ (A ⇾ D)
    case Arrow(parameterType, resultType) =>
      resultType.split.map(resultSplit => TypeSplit(
        Arrow(parameterType, resultSplit.first),
        Arrow(parameterType, resultSplit.second)
      ))

    // B ⤇ C ‖ D
    // ───────────────────────────────────────────────── Spl-All
    // ∀(α ∗ A). B ⤇ ∀(α ∗ A). C ‖ ∀(α ∗ A). D
    case ForAll(disjointBound, bodyType) =>
      bodyType.split.map(bodySplit => TypeSplit(
        ForAll(disjointBound, bodySplit.first),
        ForAll(disjointBound, bodySplit.second)
      ))

    // A ⤇ B ‖ C
    // ───────────────────────── Spl-Rcd
    // {ℓ : A} ⤇ {ℓ : B} ‖ {ℓ : C}
    case Record(label, fieldType) =>
      fieldType.split.map(fieldSplit => TypeSplit(
        Record(label, fieldSplit.first),
        Record(label, fieldSplit.second)
      ))
    case _ => None
  }

  def isRigid: Boolean = this match {
    // ─────────────── Rigid-Primitive
    // Rigid(p)
    //
    // ───────── Rigid-Top
    // Rigid(⊤)
    //
    // ───────── Rigid-Bot
    // Rigid(⊥)
    //
    // ───────── Rigid-Var
    // Rigid(α)
    case Primitive(_) | Top | Bottom | Variable(_) => true

    // ─────────────── Rigid-Rec
    // Rigid(μα. A)
    case Recursive(_) => true

    // Rigid(B)
    // ─────────────── Rigid-Arr
    // Rigid(A ⇾ B)
    case Arrow(_, resultType) => resultType.isRigid

    // Rigid(B)
    // ───────────────────── Rigid-All
    // Rigid(∀(α ∗ A). B)
    case ForAll(_, bodyType) => bodyType.isRigid

    // Rigid(A)
    // ─────────────── Rigid-Rcd
    // Rigid({ℓ : A})
    case Record(_, fieldType) => fieldType.isRigid

    // There is no rigidity rule for an exposed intersection.
    case Intersection(_, _) => false
  }

  def isWellFormed(context: TypeContext = TypeContext.empty): Boolean = context.accepts(this)

  def isSubtypeOf(targetType: Type, context: TypeContext = TypeContext.empty): Boolean =
    Subtyping(context).relates(this, targetType)

  def isSilent(context: TypeContext = TypeContext.empty): Boolean =
    RouteAnalysis(context).isSilent(this)

  def collisionShape: Type = RouteAnalysis(TypeContext.empty).collisionShapeOf(this)

  def isRouteSubtypeOf(targetType: Type, context: TypeContext = TypeContext.empty): Boolean =
    RouteAnalysis(context).isRouteSubtype(this, targetType)

  def applicativeView(applicableForm: ApplicableForm): Option[Type] =
    ApplicativeDistribution(applicableForm).viewOf(this)
}

object Type {
  val Integer: Type = Primitive(PrimitiveType.Integer)
  val Decimal: Type = Primitive(PrimitiveType.Decimal)
  val Boolean: Type = Primitive(PrimitiveType.Boolean)
  val Text: Type = Primitive(PrimitiveType.Text)
  val Unit: Type = Primitive(PrimitiveType.Unit)
}
