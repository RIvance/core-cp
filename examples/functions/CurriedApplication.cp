// expected: 42

def add(left: Int)(right: Int): Int = left + right;

def main: Int = let addTwenty = add(20) in addTwenty(22);
