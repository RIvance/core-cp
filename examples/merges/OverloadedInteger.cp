// expected: 42

def doubleInteger(value: Int): Int = value * 2;
def doubleText(value: String): String = value ++ value;
def double = doubleInteger ,, doubleText;

def main: Int = (double : Int -> Int)(21);
