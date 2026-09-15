// expected: 42

type Endomorphism<Value> = Value -> Value;

def applyEndomorphism[Value](function: Endomorphism<Value>, value: Value): Value =
  function(value);
def increment(value: Int): Int = value + 1;

def main: Int = applyEndomorphism[Int](increment, 41);
