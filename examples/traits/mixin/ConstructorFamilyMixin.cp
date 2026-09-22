// expected: "48 + -(2)"

type Eval = { eval: Int; };
type Print = { print: String; };

type AddSig<Expression> = {
  Literal: Int -> String -> Expression;
  Add: Expression -> Expression -> Expression;
};

def evaluateAdd = trait implements AddSig<Eval> => {
  (Literal value text).eval = value;
  (Add left right).eval = left.eval + right.eval;
};

def printAdd = trait implements AddSig<Print> => {
  (Literal value text).print = text;
  (Add left right).print = left.print ++ " + " ++ right.print;
};

type NegationSig<Expression> = { Negate: Expression -> Expression; };

def addNegation[Base * NegationSig<Eval & Print>](base: Trait[Base]) =
  trait [self: Base] implements NegationSig<Eval & Print> inherits base => {
    (Negate expression).eval = 0 - expression.eval;
    (Negate expression).print = "-(" ++ expression.print ++ ")";
  };

def family = new addNegation[AddSig<Eval & Print>](evaluateAdd ,, printAdd);

def expression = new family.Add(
  new family.Literal(48, "48"),
  new family.Negate(new family.Literal(2, "2"))
);

def main: String = if expression.eval == 46 then expression.print else "unexpected result";
