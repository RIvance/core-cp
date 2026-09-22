// expected: 512

type Eval = { eval: Int; };
type Doubled<Expression> = { doubled: Expression; };

type ExpressionSig<Expression> = {
  Literal: Int -> Expression;
  Add: Expression -> Expression -> Expression;
};

def evaluateExpressions = trait implements ExpressionSig<Eval> => {
  (Literal value).eval = value;
  (Add left right).eval = left.eval + right.eval;
};

def doubleExpressions[Expression] =
  trait [self: ExpressionSig<Expression>] implements ExpressionSig<Doubled<Expression>> => {
    (Literal value).doubled = new self.Literal(value * 2);
    (Add left right).doubled = new self.Add(left.doubled, right.doubled);
  };

def expressionTree[Expression](depth: Int) = trait [self: ExpressionSig<Expression>] => {
  tree = {
    let rec build: Int -> Expression = (depth: Int) =>
      if depth == 0 then new self.Literal(1)
      else {
        let shared = build(depth - 1);
        new self.Add(shared, shared)
      };
    build(depth)
  };
};

def evaluatedFamily = new evaluateExpressions;
def family = evaluatedFamily ,, (doubleExpressions[Eval] ^ evaluatedFamily);
// Eight levels give 256 leaves; doubling each literal gives 512.
def expressions = expressionTree[Eval & Doubled<Eval>](8) ^ family;
def main: Int = expressions.tree.doubled.eval;
