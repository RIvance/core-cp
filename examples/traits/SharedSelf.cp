// expected: 42

type Base = { base: Int; };
type Derived = { doubled: Int; };

def baseTrait = trait implements Base => {
  base = 21;
};

def derivedTrait = trait [self: Base] implements Derived => {
  doubled = self.base * 2;
};

def main: Int = (new (baseTrait ,, derivedTrait)).doubled;
