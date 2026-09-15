// expected: 42

def applyTwice(function: Int -> Int)(value: Int): Int =
  function(function(value));
def increment(value: Int): Int = value + 1;

def main: Int = applyTwice(increment)(40);
