// expected: 0

type IntegerBooleanConsumer = (Int -> Int & Bool) -> Int;
type IntegerTextConsumer = (Int -> Int & String) -> Int;

def chooseInteger(function: Int -> Int): Int = function(0);

def overloadedConsumer: IntegerBooleanConsumer & IntegerTextConsumer = chooseInteger;
def integerBooleanConsumer: IntegerBooleanConsumer = overloadedConsumer;

def main: Int = integerBooleanConsumer((value: Int) => value ,, true);
