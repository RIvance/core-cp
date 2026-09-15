// expected: 42

type Base = { value: Int; };
type Child = { answer: Int; };

def baseTrait: Trait[Base] = trait implements Base => {
  value = 40;
};

def childTrait: Trait[Base, Base & Child] =
  trait [self: Base] implements Base & Child inherits baseTrait => {
    answer = super.value + 2;
  };

def main: Int = (new childTrait).answer;
