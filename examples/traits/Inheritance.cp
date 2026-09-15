// expected: 42

type Base = { base: Int; };
type Child = { child: Int; };

def baseTrait: Trait[Base] = trait implements Base => {
  base = 20;
};

def childTrait: Trait[Base, Base & Child] =
  trait [self: Base] implements Base & Child inherits baseTrait => {
    child = 22;
  };

def main: Int =
  let instance = new childTrait in instance.base + instance.child;
