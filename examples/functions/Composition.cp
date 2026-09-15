// expected: 42

def compose(function: Int -> Int, next: Int -> Int)(value: Int): Int = next(function(value));

def increment(value: Int): Int = value + 1;

def double(value: Int): Int = value * 2;

def main: Int = compose(increment, double)(20);
