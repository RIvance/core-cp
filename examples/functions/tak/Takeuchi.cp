// expected: 3

def takeuchi(x: Int, y: Int, z: Int): Int =
  if x <= y then y
  else takeuchi(
    takeuchi(x - 1, y, z),
    takeuchi(y - 1, z, x),
    takeuchi(z - 1, x, y)
  );

def main: Int = takeuchi(3, 2, 1);
