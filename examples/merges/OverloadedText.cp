// expected: "haha"

def doubleInteger(value: Int): Int = value * 2;
def doubleText(value: String): String = value ++ value;
def double = doubleInteger ,, doubleText;

def main: String = (double : String -> String)("ha");
