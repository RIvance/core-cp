// expected: true

def stateZero(remaining: Int): Bool =
  if remaining == 0 then true else stateOne(remaining - 1);

def stateOne(remaining: Int): Bool =
  if remaining == 0 then false else stateTwo(remaining - 1);

def stateTwo(remaining: Int): Bool =
  if remaining == 0 then false else stateZero(remaining - 1);

def main: Bool = stateZero(6);
