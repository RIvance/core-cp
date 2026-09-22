// expected: true

interface Exp {
  eval: Int;
  double: Exp;
  eq: Exp -> Bool;
};

def eval(expression: Exp): Int = (unfold[Exp] expression).eval;
def double(expression: Exp): Exp = (unfold[Exp] expression).double;
def equal(left: Exp, right: Exp): Bool = (unfold[Exp] left).eq(right);

def literal(value: Int): Exp = fold[Exp] {
  eval = value;
  double = literal(value * 2);
  eq(other: Exp): Bool = eval(other) == value;
};

def add(left: Exp, right: Exp): Exp = fold[Exp] {
  eval = eval(left) + eval(right);
  double = add(double(left), double(right));
  eq(other: Exp): Bool = eval(other) == eval(left) + eval(right);
};

def seven = literal(7);
def sevenByAddition = add(literal(3), literal(4));

// The double field stays lazy: an expression does not build all its future doubles.
def main: Bool = equal(double(seven), double(sevenByAddition)) && eval(double(sevenByAddition)) == 14;
