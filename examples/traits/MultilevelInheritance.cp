// expected: "component-42: ready"

type Named = { name: String; };
type Identified = { identifier: String; };
type Status = { status: String; };

def named: Trait[Named] = trait implements Named => {
  name = "component";
};

def identified: Trait[Named, Named & Identified] =
  trait [self: Named] implements Named & Identified inherits named => {
    identifier = super.name ++ "-42";
  };

def status: Trait[Named & Identified, Named & Identified & Status] =
  trait [self: Named & Identified] implements Named & Identified & Status inherits identified => {
    status = super.identifier ++ ": ready";
  };

def main: String = (new status).status;
