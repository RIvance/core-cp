// expected: 48

def scaleInteger(value: Int): Int = value * 16;
def isPositive(value: Float): Bool = value > 0.0;

def main: Int = ((scaleInteger ,, isPositive)(3 ,, 1.5) : Int);
