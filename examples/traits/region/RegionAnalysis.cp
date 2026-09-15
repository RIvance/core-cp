// expected: true

def negate(value: Bool): Bool = if value then false else true;

def power(base: Float, exponent: Int): Float =
  if exponent == 0 then 1.0
  else if exponent > 0 then power(base, exponent - 1) * base
  else power(base, exponent + 1) / base;

type Vector = { x: Float; y: Float; };

type HudakSig<Region> = {
  Circle: Float -> Region;
  Outside: Region -> Region;
  Union: Region -> Region -> Region;
  Intersect: Region -> Region -> Region;
  Translate: Vector -> Region -> Region;
};

type HoferSig<Region> = {
  Universal: Region;
  Empty: Region;
  Scale: Vector -> Region -> Region;
};

type RegionSig<Region> = HudakSig<Region> & HoferSig<Region>;

type RegionText = { text: String; };

def printHudak = trait implements HudakSig<RegionText> => {
  (Circle radius).text = "circle";
  (Outside region).text = "outside";
  (Union left right).text = "union";
  (Intersect left right).text = "intersect";
  (Translate offset region).text = "translate";
};

def printHofer = trait implements HoferSig<RegionText> => {
  (Universal).text = "U";
  (Empty).text = "empty";
  (Scale factor region).text = "scale";
};

type Contains = { contains: Vector -> Bool; };

def containRegions = trait implements RegionSig<Contains> => {
  (Circle radius).contains(point: Vector) =
    power(point.x, 2) + power(point.y, 2) <= power(radius, 2);
  (Outside region).contains(point: Vector) = negate(region.contains(point));
  (Union left right).contains(point: Vector) = left.contains(point) || right.contains(point);
  (Intersect left right).contains(point: Vector) = left.contains(point) && right.contains(point);
  (Translate offset region).contains(point: Vector) =
    region.contains({ x = point.x - offset.x; y = point.y - offset.y; });
  (Universal).contains(point: Vector) = true;
  (Empty).contains(point: Vector) = false;
  (Scale factor region).contains(point: Vector) =
    region.contains({ x = point.x / factor.x; y = point.y / factor.y; });
};

type IsUniversal = { isUniversal: Bool; };
type IsEmpty = { isEmpty: Bool; };

def checkUniversal = trait implements RegionSig<IsEmpty % IsUniversal> => {
  (Universal).isUniversal = true;
  (Outside region).isUniversal = region.isEmpty;
  (Union left right).isUniversal = left.isUniversal || right.isUniversal;
  (Intersect left right).isUniversal = left.isUniversal && right.isUniversal;
  (Translate offset region).isUniversal = region.isUniversal;
  (Scale factor region).isUniversal = region.isUniversal;
  (Circle radius).isUniversal = false;
  (Empty).isUniversal = false;
};

def checkEmpty = trait implements RegionSig<IsUniversal % IsEmpty> => {
  (Empty).isEmpty = true;
  (Outside region).isEmpty = region.isUniversal;
  (Union left right).isEmpty = left.isEmpty && right.isEmpty;
  (Intersect left right).isEmpty = left.isEmpty || right.isEmpty;
  (Translate offset region).isEmpty = region.isEmpty;
  (Scale factor region).isEmpty = region.isEmpty;
  (Circle radius).isEmpty = false;
  (Universal).isEmpty = false;
};

type Simplified<Region> = { simplify: Region; };

def simplifyRegions[Region](family: RegionSig<Region>) =
  trait implements RegionSig<IsUniversal & IsEmpty & Region % Simplified<Region>> => {
    (Circle radius [self: IsUniversal & IsEmpty & Region]).simplify =
      if self.isUniversal then new family.Universal
      else if self.isEmpty then new family.Empty
      else self;
    (Outside region [self: IsUniversal & IsEmpty & Region]).simplify =
      if self.isUniversal then new family.Universal
      else if self.isEmpty then new family.Empty
      else self;
    (Union left right [self: IsUniversal & IsEmpty & Region]).simplify =
      if self.isUniversal then new family.Universal
      else if self.isEmpty then new family.Empty
      else self;
    (Intersect left right [self: IsUniversal & IsEmpty & Region]).simplify =
      if self.isUniversal then new family.Universal
      else if self.isEmpty then new family.Empty
      else self;
    (Translate offset region [self: IsUniversal & IsEmpty & Region]).simplify =
      if self.isUniversal then new family.Universal
      else if self.isEmpty then new family.Empty
      else self;
    (Universal [self: IsUniversal & IsEmpty & Region]).simplify = new family.Universal;
    (Empty [self: IsUniversal & IsEmpty & Region]).simplify = new family.Empty;
    (Scale factor region [self: IsUniversal & IsEmpty & Region]).simplify =
      if self.isUniversal then new family.Universal
      else if self.isEmpty then new family.Empty
      else self;
  };

type OutsideEliminated<Region> = {
  eliminateOutside: Region;
  deleteOutside: Region;
};

def eliminateOutside[Region](family: RegionSig<Region>) =
  trait implements RegionSig<Region % OutsideEliminated<Region>> => {
    (Circle radius [self: Region]).eliminateOutside = self;
    (Outside region).eliminateOutside = region.deleteOutside;
    (Union left right).eliminateOutside =
      new family.Union(left.eliminateOutside, right.eliminateOutside);
    (Intersect left right).eliminateOutside =
      new family.Intersect(left.eliminateOutside, right.eliminateOutside);
    (Translate offset region).eliminateOutside =
      new family.Translate(offset, region.eliminateOutside);
    (Universal [self: Region]).eliminateOutside = self;
    (Empty [self: Region]).eliminateOutside = self;
    (Scale factor region).eliminateOutside =
      new family.Scale(factor, region.eliminateOutside);

    (Circle radius [self: Region & OutsideEliminated<Region>]).deleteOutside =
      new family.Outside(self.eliminateOutside);
    (Outside region).deleteOutside = region.eliminateOutside;
    (Union left right [self: Region & OutsideEliminated<Region>]).deleteOutside =
      new family.Outside(self.eliminateOutside);
    (Intersect left right [self: Region & OutsideEliminated<Region>]).deleteOutside =
      new family.Outside(self.eliminateOutside);
    (Translate offset region [self: Region & OutsideEliminated<Region>]).deleteOutside =
      new family.Outside(self.eliminateOutside);
    (Universal [self: Region & OutsideEliminated<Region>]).deleteOutside =
      new family.Outside(self.eliminateOutside);
    (Empty [self: Region & OutsideEliminated<Region>]).deleteOutside =
      new family.Outside(self.eliminateOutside);
    (Scale factor region [self: Region & OutsideEliminated<Region>]).deleteOutside =
      new family.Outside(self.eliminateOutside);
  };

def regionRepository[Region] = trait [self: RegionSig<Region>] => {
  annulus = new Intersect(new Outside(new Circle(4.0)), new Circle(8.0));
  ellipse = new Scale({ x = 4.0; y = 8.0; }, new Circle(1.0));
  universal = new Union(new Outside(new Empty), new Circle(1.0));
  circles = {
    let rec build: Int -> Float -> Region = (remaining: Int) => (offset: Float) =>
      if remaining == 0 then new Circle(1.0)
      else {
        let shared = build(remaining - 1)(offset / 2.0);
        new Union(
          new Translate({ x = 0.0 - offset; y = 0.0; }, shared),
          new Translate({ x = offset; y = 0.0; }, shared)
        )
      };
    build(20)(power(2.0, 18))
  };
};

def analysisFamily = new (checkUniversal ,, checkEmpty);

def universalRegion = new analysisFamily.Union(
  new analysisFamily.Outside(new analysisFamily.Empty),
  new analysisFamily.Circle(1.0)
);

def main: Bool = universalRegion.isUniversal;
