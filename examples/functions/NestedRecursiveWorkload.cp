// expected: 125

def size: Int = 5;

def linear(value: Int): Int =
  if value == 0 then 0 else linear(value - 1) + 1;

def quadratic(value: Int): Int =
  if value == 0 then 0 else linear(size) + quadratic(value - 1);

def cubic(value: Int): Int =
  if value == 0 then 0 else quadratic(size) + cubic(value - 1);

def main: Int = cubic(size);
