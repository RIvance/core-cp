// expected: 1

def outerValue = 1;
def addOuter[Ignored](value: Int) = outerValue + value;
def evaluate(ignored: Unit) = {
  let outerValue = 2;
  addOuter[Unit](0)
};

def main: Int = evaluate(());
