// expected: 12

type Eval = { eval: Int; };

type ExpSig<Exp> = {
  Lit: Int -> Exp;
  Add: Exp -> Exp -> Exp;
};

def evaluateExpressions = trait implements ExpSig<Eval> => {
  (Lit value).eval = value;
  (Add left right).eval = left.eval + right.eval;
  answer = 12;
};

def main: Int = (new evaluateExpressions).answer;
