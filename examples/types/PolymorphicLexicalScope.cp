// expected: 1

def captured: Int = 1;

def addCaptured[Ignored](value: Int): Int = captured + value;

def invoke(unit: Unit): Int = {
  let captured = 2;
  addCaptured[Unit](0)
};

def main: Int = invoke(());
