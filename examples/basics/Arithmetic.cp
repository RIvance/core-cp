// expected: 42

def maximum(left right: Int): Int =
  if left > right then left else right;

def main: Int = maximum(20, 42);
