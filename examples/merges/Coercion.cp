// expected: 42

def replaceBoolean(value: Int) = false ,, value;

def main: Int = ((replaceBoolean : Bool & Int -> Bool & Int)(true ,, 42) : Int);
