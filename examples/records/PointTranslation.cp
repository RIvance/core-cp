// expected: 7

type Point = { x: Int; y: Int; };

def origin: Point = { x = 0; y = 0; };
def move(point: Point, horizontal: Int, vertical: Int): Point = {
  x = point.x + horizontal;
  y = point.y + vertical;
};

def main: Int =
  let moved = move(origin, 3, 4) in moved.x + moved.y;
