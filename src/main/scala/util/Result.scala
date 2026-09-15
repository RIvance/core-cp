package cp.util

/** A small, right-biased result type modelled after Rust's `Result<T, E>`. */
enum Result[+T, +E] {
  case Ok(value: T)
  case Err(error: E)

  def map[U](function: T => U): Result[U, E] = this match {
    case Result.Ok(value) => Result.Ok(function(value))
    case Result.Err(error) => Result.Err(error)
  }

  def flatMap[U, F >: E](function: T => Result[U, F]): Result[U, F] = this match {
    case Result.Ok(value) => function(value)
    case Result.Err(error) => Result.Err(error)
  }

  def mapError[F](function: E => F): Result[T, F] = this match {
    case Result.Ok(value) => Result.Ok(value)
    case Result.Err(error) => Result.Err(function(error))
  }

  def toOption: Option[T] = this match {
    case Result.Ok(value) => Some(value)
    case Result.Err(_) => None
  }
}

object Result {
  def traverse[A, T, E](values: List[A])(function: A => Result[T, E]): Result[List[T], E] = {
    values.foldRight(Result.Ok(List.empty[T]): Result[List[T], E]) { (value, accumulated) =>
      function(value).flatMap { transformed =>
        accumulated.map(transformed :: _)
      }
    }
  }
}
