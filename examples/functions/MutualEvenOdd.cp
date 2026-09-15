// expected: true

def even(value: Int): Bool =
  if value == 0 then true else odd(value - 1);

def odd(value: Int): Bool =
  if value == 0 then false else even(value - 1);

def main: Bool = odd(9);
