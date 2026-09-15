// expected: 720

def main: Int =
  let rec factorial: Int -> Int = λ(value: Int) .
    if value == 0 then 1 else value * factorial(value - 1)
  in factorial(6);
