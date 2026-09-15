// expected: "(4 + 8)"

type Eval = { eval: Int; };
type Print = { print: String; };
type PrintAuxiliary = { printAuxiliary: String; };

type ExpressionSig<Expression> = {
  Literal: Int -> String -> Expression;
  Add: Expression -> Expression -> Expression;
};

def evaluateExpressions = trait implements ExpressionSig<Eval> => {
  (Literal value text).eval = value;
  (Add left right).eval = left.eval + right.eval;
};

def printWithChildEvaluation = trait implements ExpressionSig<Eval % Print> => {
  (Literal value text).print = text;
  (Add left right).print =
    if right.eval == 0 then left.print
    else "(" ++ left.print ++ " + " ++ right.print ++ ")";
};

def inheritedPrint = trait implements ExpressionSig<Eval & Print> inherits evaluateExpressions => {
  (Literal value text).print = text;
  (Add left right).print =
    if right.eval == 0 then left.print
    else "(" ++ left.print ++ " + " ++ right.print ++ ")";
};

def printWithSelfEvaluation = trait implements ExpressionSig<Eval % Print> => {
  (Literal value text).print = text;
  (Add left right [self: Eval]).print =
    if self.eval == 0 then "0"
    else "(" ++ left.print ++ " + " ++ right.print ++ ")";
};

def mutuallyDefinedPrint = trait implements ExpressionSig<PrintAuxiliary % Print> => {
  (Literal value text).print = text;
  (Add left right).print = left.printAuxiliary ++ " + " ++ right.printAuxiliary;
};

def auxiliaryPrint = trait implements ExpressionSig<Print % PrintAuxiliary> => {
  (Literal value text [self: Print]).printAuxiliary = self.print;
  (Add left right [self: Print]).printAuxiliary = "(" ++ self.print ++ ")";
};

def family = new (evaluateExpressions ,, mutuallyDefinedPrint ,, auxiliaryPrint);

def expression = new family.Add(
  new family.Literal(4, "4"),
  new family.Literal(8, "8")
);

def main: String = if expression.eval == 12 then expression.printAuxiliary else "unexpected result";
