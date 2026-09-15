// expected: true

type ZeroRemainder = { isZeroRemainder: Int -> Bool; };
type OneRemainder = { isOneRemainder: Int -> Bool; };
type TwoRemainder = { isTwoRemainder: Int -> Bool; };

def zeroRemainder = trait [self: TwoRemainder] implements ZeroRemainder => {
  isZeroRemainder(value: Int) =
    if value == 0 then true else self.isTwoRemainder(value - 1);
};

def oneRemainder = trait [self: ZeroRemainder] implements OneRemainder => {
  isOneRemainder(value: Int) =
    if value == 0 then false else self.isZeroRemainder(value - 1);
};

def twoRemainder = trait [self: OneRemainder] implements TwoRemainder => {
  isTwoRemainder(value: Int) =
    if value == 0 then false else self.isOneRemainder(value - 1);
};

def main: Bool = (new (zeroRemainder ,, oneRemainder ,, twoRemainder)).isZeroRemainder(42);
