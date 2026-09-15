// expected: 42

type Component = { value: Int; };

def component: Trait[Component] = trait implements Component => {
  value = 42;
};

def main: Int = (component ^ top).value;
