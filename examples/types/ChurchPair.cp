// expected: 42

def pair[Left, Right](left: Left, right: Right):
  forall [type Result] -> ((Left -> Right -> Result) -> Result) =
  [type Result] =>
    (consumer: Left -> Right -> Result) => consumer(left, right);

def first[Left, Right](
  pairValue: forall [type Result] -> ((Left -> Right -> Result) -> Result)
): Left = pairValue[Left]((left: Left, right: Right) => left);

def main: Int = first[Int, String](pair[Int, String](42, "unused"));
