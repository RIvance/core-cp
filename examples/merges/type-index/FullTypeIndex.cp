// expected: 0

type IntegerAndBooleanConsumer = (Int -> (Int & Bool)) -> Int;
type IntegerAndStringConsumer = (Int -> (Int & String)) -> Int;

def consume(function: Int -> Int): Int = function(0);
def overloaded = consume: IntegerAndBooleanConsumer & IntegerAndStringConsumer;
def consumeIntegerAndBoolean = overloaded: IntegerAndBooleanConsumer;

def main: Int = consumeIntegerAndBoolean((value: Int) => (value ,, true));
