// expected: 42

def main: Int =
  let record = { left = 20; right = 22; } in record.left + record.right;
