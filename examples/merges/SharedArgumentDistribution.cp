// expected: 42

def increment(value: Int): Int = value + 1;
def isPositive(value: Int): Bool = value > 0;

def main: Int = ((increment ,, isPositive)(41) : Int);
