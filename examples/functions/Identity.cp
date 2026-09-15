// expected: 42

def identity[Value](value: Value): Value = value;

def main: Int = identity[Int](42);
