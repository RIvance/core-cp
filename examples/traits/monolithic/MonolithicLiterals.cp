// expected: 46

type Monolithic = {
  eval: Int;
  plus: Int -> Int;
  print: String;
};

type LiteralSig<Expression> = {
  IntegerLiteral: Int -> String -> Expression;
  StringLiteral: String -> Expression;
};

def interpretLiterals = trait implements LiteralSig<Monolithic> => {
  (IntegerLiteral value text).eval = value;
  (IntegerLiteral value text).print = text;
  (IntegerLiteral value text [self: Monolithic]).plus(increment: Int) = self.eval + increment;

  (StringLiteral text).eval = 0;
  (StringLiteral text).print = text;
  (StringLiteral text [self: Monolithic]).plus(increment: Int) = self.eval + increment;
};

def literalRepository = trait [self: LiteralSig<Monolithic>] => {
  integer = new self.IntegerLiteral(48, "48");
  string = new self.StringLiteral("Core CP");
};

def family = new interpretLiterals;
def literals = literalRepository ^ family;
def integer = new family.IntegerLiteral(48, "48");
def main: Int = integer.plus(0 - 2);
