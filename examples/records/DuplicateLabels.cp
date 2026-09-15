// expected: true

def main: Bool =
  let record = { x = "x"; x = 3; } in
  (record.x : Int) == 3;
