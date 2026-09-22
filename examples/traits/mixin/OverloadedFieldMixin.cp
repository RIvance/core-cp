// expected: "FOOBAR"

type TextFields = { m: String; n: String; };

def base = trait [self: TextFields] => {
  m = "FOOBAR";
  n = self.m;
};

// A String-valued m in Base is disjoint from the mixin's Int-valued m.
def addInteger[Base * { m: Int }](parent: Trait[Base, Base]) =
  trait [self: Base] inherits parent => { m = 48; };

def main: String = {
  let object = new addInteger[TextFields](base);
  if (object.m : Int) == 48 then object.n else "unexpected integer field"
};
