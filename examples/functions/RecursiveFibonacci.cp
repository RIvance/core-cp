// expected: 55

def main: Int =
  let rec fibonacci: Int -> Int = (value: Int) =>
    if value <= 1 then value else fibonacci(value - 1) + fibonacci(value - 2)
  in fibonacci(10);
