// expected: 81

def emptyIntegers(name: String): Int = 0;
def emptyFunctions(name: String): Int -> Int = (value: Int) => value;

def insertInteger(
  name: String,
  value: Int,
  environment: String -> Int
): String -> Int = (requested: String) =>
  if name == requested then value else environment(requested);

def insertFunction(
  name: String,
  function: Int -> Int,
  environment: String -> (Int -> Int)
): String -> (Int -> Int) = (requested: String) =>
  if name == requested then function else environment(requested);

type Context = {
  integers: String -> Int;
  functions: String -> (Int -> Int);
};

type Evaluation = { evaluate: Context -> Int; };

type ArithmeticSig<Expression> = {
  Literal: Int -> Expression;
  Add: Expression -> Expression -> Expression;
};

def evaluateArithmetic = trait implements ArithmeticSig<Evaluation> => {
  (Literal value).evaluate(context: Context) = value;
  (Add left right).evaluate(context: Context) = left.evaluate(context) + right.evaluate(context);
};

type ValueBindingSig<Expression> = {
  LetValue: String -> Expression -> Expression -> Expression;
  Variable: String -> Expression;
};

def evaluateValueBindings = trait implements ValueBindingSig<Evaluation> => {
  (LetValue name initializer body).evaluate(context: Context) = body.evaluate({
    integers = insertInteger(name, initializer.evaluate(context), context.integers);
    functions = context.functions;
  });
  (Variable name).evaluate(context: Context) = context.integers(name);
};

type FunctionBindingSig<Expression> = {
  LetFunction: String -> (Int -> Int) -> Expression -> Expression;
  ApplyFunction: String -> Expression -> Expression;
};

def evaluateFunctionBindings = trait implements FunctionBindingSig<Evaluation> => {
  (LetFunction name function body).evaluate(context: Context) = body.evaluate({
    integers = context.integers;
    functions = insertFunction(name, function, context.functions);
  });
  (ApplyFunction name argument).evaluate(context: Context) =
    context.functions(name)(argument.evaluate(context));
};

def family = new (evaluateArithmetic ,, evaluateValueBindings ,, evaluateFunctionBindings);

def program = new family.LetFunction(
  "square",
  (value: Int) => value * value,
  new family.LetValue(
    "x",
    new family.Literal(9),
    new family.ApplyFunction("square", new family.Variable("x"))
  )
);

def initialContext: Context = {
  integers = emptyIntegers;
  functions = emptyFunctions;
};

def main: Int = program.evaluate(initialContext);
