// expected: 20

def values(number: Int) = {
  x = number;
  y = number + 1;
  z = number * 2;
};
def refined = (values : Int -> { y: Int; z: Int; });

def main: Int = refined(10).z;
