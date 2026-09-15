// expected: 42

def integerConstant = Λ Element . 42;
def booleanConstant = Λ[type Element] => true;

def main: Int = ((integerConstant ,, booleanConstant)[Unit] : Int);
