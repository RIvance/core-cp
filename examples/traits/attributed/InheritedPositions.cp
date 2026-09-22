// expected: 16

type ExpressionSig<Expression> = {
  Literal: Int -> Expression;
  Add: Expression -> Expression -> Expression;
};

type Count = { count: Int; };

def countNodes = trait implements ExpressionSig<Count> => {
  (Literal value).count = 1;
  (Add left right).count = left.count + right.count + 1;
};

type Position = { position: Int; };
type ChildPositions = {
  leftPosition: Position -> Int;
  rightPosition: Position -> Count -> Int;
};
type PositionSum<Context> = { positionSum: Position & Context -> Int; };

def sumPositions[Context * Position] =
  trait [self: ChildPositions] implements ExpressionSig<Count % PositionSum<Context>> => {
    (Literal value).positionSum(context: Position & Context) = context.position;
    (Add left right).positionSum(context: Position & Context) =
      left.positionSum((context : Context) ,, { position = self.leftPosition(context); }) +
      right.positionSum((context : Context) ,, { position = self.rightPosition(context, left); });

    leftPosition(parent: Position): Int = parent.position + 1;
    rightPosition(parent: Position, left: Count): Int = parent.position + left.count + 1;
  };

def family = new (countNodes ,, sumPositions[Top]);

def expression = new family.Add(
  new family.Add(new family.Literal(1), new family.Literal(2)),
  new family.Add(new family.Literal(3), new family.Literal(4))
);

// Preorder numbering starts at zero. The leaves occupy positions 2, 3, 5, and 6.
// Summing the positions keeps this example independent of integer-to-text conversion.
def main: Int = expression.positionSum({ position = 0; });
