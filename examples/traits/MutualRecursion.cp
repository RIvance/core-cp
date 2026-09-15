// expected: true

type Even = { isEven: Int -> Bool; };
type Odd = { isOdd: Int -> Bool; };

def even = trait [self: Odd] implements Even => {
  isEven(value: Int) = if value == 0 then true else self.isOdd(value - 1);
};

def odd = trait [self: Even] implements Odd => {
  isOdd(value: Int) = if value == 0 then false else self.isEven(value - 1);
};

def main: Bool = (new (even ,, odd)).isEven(10);
