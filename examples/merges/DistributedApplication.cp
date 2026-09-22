// expected: true

// The declared Bool result hides the extra Int in this function's body.
def asBoolean(value: Int): Bool = true ,, value;
def asInteger(value: Bool): Int = 1;

def main: Bool = {
  let result = (asBoolean ,, asInteger)(2 ,, false);
  (result : Bool) && (result : Int) == 1
};
