// expected: 42

def main: Int =
  let nested = { outer = { inner = 42; }; } in nested.outer.inner;
