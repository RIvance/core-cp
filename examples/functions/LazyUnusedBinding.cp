// expected: 42

def main: Int = let rec divergent: Int = divergent in 42;
