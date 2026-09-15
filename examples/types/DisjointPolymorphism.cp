// expected: 42

def appendInteger[Element * Int](value: Element) = value ,, 42;

def main: Int = (appendInteger[String & Bool]("∀" ,, true) : Int);
