// expected: 42

type Box<Value> = { value: Value; };

def box[Value](value: Value): Box<Value> = { value = value; };
def unbox[Value](boxed: Box<Value>): Value = boxed.value;

def main: Int = unbox[Int](box[Int](42));
