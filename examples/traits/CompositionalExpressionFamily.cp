// expected: "((4 + 8) * 4)"

type Eval = { eval: Int; };
type Print = { print: String; };

type AddSig<Expression> = {
  Lit: Int -> String -> Expression;
  Add: Expression -> Expression -> Expression;
};

def evaluateAdd = trait implements AddSig<Eval> => {
  (Lit value text).eval = value;
  (Add left right).eval = left.eval + right.eval;
};

def printAdd = trait implements AddSig<Print> => {
  (Lit value text).print = text;
  (Add left right).print = "(" ++ left.print ++ " + " ++ right.print ++ ")";
};

type MultiplySig<Expression> extends AddSig<Expression> = {
  Multiply: Expression -> Expression -> Expression;
};

def evaluateMultiply = trait implements MultiplySig<Eval> inherits evaluateAdd => {
  (Multiply left right).eval = left.eval * right.eval;
};

def printMultiply = trait implements MultiplySig<Print> inherits printAdd => {
  (Multiply left right).print = "(" ++ left.print ++ " * " ++ right.print ++ ")";
};

def main: String = {
  let family = new (evaluateMultiply ,, printMultiply);
  let sum = new family.Add(
    new family.Lit(4, "4"),
    new family.Lit(8, "8")
  );
  let product = new family.Multiply(sum, new family.Lit(4, "4"));
  if product.eval == 48 then product.print else "unexpected result"
};
