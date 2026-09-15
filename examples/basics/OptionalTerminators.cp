// expected: 42

type Pair = { left: Int right: Int }

def pair(left: Int, right: Int): Pair = {
  left = left
  right = right
}

def main: Int = {
  let result = pair(40, 2)
  result.left + result.right
}
