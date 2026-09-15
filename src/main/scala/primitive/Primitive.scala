package cp.primitive

import cp.util.Result

enum PrimitiveType {
  case Integer, Decimal, Boolean, Text, Unit
}

enum PrimitiveValue {
  case Integer(value: BigInt)
  case Decimal(value: BigDecimal)
  case Boolean(value: scala.Boolean)
  case Text(value: String)
  case UnitValue

  def primitiveType: PrimitiveType = this match {
    case Integer(_) => PrimitiveType.Integer
    case Decimal(_) => PrimitiveType.Decimal
    case Boolean(_) => PrimitiveType.Boolean
    case Text(_) => PrimitiveType.Text
    case UnitValue => PrimitiveType.Unit
  }
}

final case class PrimitiveSignature(
  leftArgument: PrimitiveType,
  rightArgument: PrimitiveType,
  result: PrimitiveType
)

object PrimitiveSignature {
  def homogeneous(argument: PrimitiveType, result: PrimitiveType): PrimitiveSignature = {
    PrimitiveSignature(argument, argument, result)
  }
}

enum PrimitiveOperationError {
  case DivisionByZero(operator: BinaryOperator)
  case InvalidOperands(operator: BinaryOperator, left: PrimitiveValue, right: PrimitiveValue)
}

/** An operator owns its accepted interfaces and its pure dynamic behavior. */
enum BinaryOperator(val symbol: String) {
  case Add extends BinaryOperator("+")
  case Subtract extends BinaryOperator("-")
  case Multiply extends BinaryOperator("*")
  case Divide extends BinaryOperator("/")
  case Remainder extends BinaryOperator("%")
  case LessThan extends BinaryOperator("<")
  case LessThanOrEqual extends BinaryOperator("<=")
  case GreaterThan extends BinaryOperator(">")
  case GreaterThanOrEqual extends BinaryOperator(">=")
  case Equal extends BinaryOperator("==")
  case NotEqual extends BinaryOperator("!=")
  case And extends BinaryOperator("&&")
  case Or extends BinaryOperator("||")
  case Concatenate extends BinaryOperator("++")

  def signatures: List[PrimitiveSignature] = this match {
    case Add | Subtract | Multiply | Divide | Remainder =>
      List(
        PrimitiveSignature.homogeneous(PrimitiveType.Integer, PrimitiveType.Integer),
        PrimitiveSignature.homogeneous(PrimitiveType.Decimal, PrimitiveType.Decimal)
      )
    case LessThan | LessThanOrEqual | GreaterThan | GreaterThanOrEqual =>
      List(
        PrimitiveSignature.homogeneous(PrimitiveType.Integer, PrimitiveType.Boolean),
        PrimitiveSignature.homogeneous(PrimitiveType.Decimal, PrimitiveType.Boolean)
      )
    case Equal | NotEqual =>
      PrimitiveType.values.toList.map(PrimitiveSignature.homogeneous(_, PrimitiveType.Boolean))
    case And | Or =>
      List(PrimitiveSignature.homogeneous(PrimitiveType.Boolean, PrimitiveType.Boolean))
    case Concatenate =>
      List(PrimitiveSignature.homogeneous(PrimitiveType.Text, PrimitiveType.Text))
  }

  def apply(
    left: PrimitiveValue,
    right: PrimitiveValue
  ): Result[PrimitiveValue, PrimitiveOperationError] = {
    import PrimitiveValue.*
    import java.math.MathContext
    (this, left, right) match {
      case (Add, Integer(a), Integer(b)) => Result.Ok(Integer(a + b))
      case (Subtract, Integer(a), Integer(b)) => Result.Ok(Integer(a - b))
      case (Multiply, Integer(a), Integer(b)) => Result.Ok(Integer(a * b))
      case (Divide, Integer(_), Integer(0)) =>
        Result.Err(PrimitiveOperationError.DivisionByZero(this))
      case (Divide, Integer(a), Integer(b)) => Result.Ok(Integer(a / b))
      case (Remainder, Integer(_), Integer(0)) =>
        Result.Err(PrimitiveOperationError.DivisionByZero(this))
      case (Remainder, Integer(a), Integer(b)) => Result.Ok(Integer(a % b))
      case (Add, Decimal(a), Decimal(b)) => Result.Ok(Decimal(a + b))
      case (Subtract, Decimal(a), Decimal(b)) => Result.Ok(Decimal(a - b))
      case (Multiply, Decimal(a), Decimal(b)) => Result.Ok(Decimal(a * b))
      case (Divide, Decimal(_), Decimal(b)) if b == BigDecimal(0) =>
        Result.Err(PrimitiveOperationError.DivisionByZero(this))
      case (Divide, Decimal(a), Decimal(b)) =>
        Result.Ok(Decimal(BigDecimal(a.bigDecimal.divide(b.bigDecimal, MathContext.DECIMAL128))))
      case (Remainder, Decimal(_), Decimal(b)) if b == BigDecimal(0) =>
        Result.Err(PrimitiveOperationError.DivisionByZero(this))
      case (Remainder, Decimal(a), Decimal(b)) =>
        Result.Ok(Decimal(BigDecimal(a.bigDecimal.remainder(b.bigDecimal, MathContext.DECIMAL128))))
      case (LessThan, Integer(a), Integer(b)) => Result.Ok(Boolean(a < b))
      case (LessThanOrEqual, Integer(a), Integer(b)) => Result.Ok(Boolean(a <= b))
      case (GreaterThan, Integer(a), Integer(b)) => Result.Ok(Boolean(a > b))
      case (GreaterThanOrEqual, Integer(a), Integer(b)) => Result.Ok(Boolean(a >= b))
      case (LessThan, Decimal(a), Decimal(b)) => Result.Ok(Boolean(a < b))
      case (LessThanOrEqual, Decimal(a), Decimal(b)) => Result.Ok(Boolean(a <= b))
      case (GreaterThan, Decimal(a), Decimal(b)) => Result.Ok(Boolean(a > b))
      case (GreaterThanOrEqual, Decimal(a), Decimal(b)) => Result.Ok(Boolean(a >= b))
      case (Equal, a, b) if a.primitiveType == b.primitiveType => Result.Ok(Boolean(a == b))
      case (NotEqual, a, b) if a.primitiveType == b.primitiveType => Result.Ok(Boolean(a != b))
      case (And, Boolean(a), Boolean(b)) => Result.Ok(Boolean(a && b))
      case (Or, Boolean(a), Boolean(b)) => Result.Ok(Boolean(a || b))
      case (Concatenate, Text(a), Text(b)) => Result.Ok(Text(a + b))
      case _ => Result.Err(PrimitiveOperationError.InvalidOperands(this, left, right))
    }
  }
}
