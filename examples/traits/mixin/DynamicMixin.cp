type Value = { value: Int; };
type Doubled = { doubled: Int; };

def valueComponent = trait implements Value => {
  value = 21;
};

def addDoubling[Base * Doubled](base: Trait[Base], doubledValue: Int) =
  trait [self: Base] implements Doubled inherits base => {
    doubled = doubledValue;
  };

def component = addDoubling[Value](valueComponent, 42) ^ { value = 21; };
