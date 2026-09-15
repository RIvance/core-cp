//package cp.fitrie
//
//import cp.fiobs.Type
//import cp.fitrie.elaboration.{ObservationPathRelation, TypeLabels}
//import cp.primitive.PrimitiveType
//
//class ObservationPathRelationSuite extends munit.FunSuite {
//  private val integerPath = ObservationPath.Termination(PrimitiveType.Integer)
//  private val booleanPath = ObservationPath.Termination(PrimitiveType.Boolean)
//
//  test("exact top derives the empty observation path") {
//    assertEquals(
//      ObservationPathRelation.acceptedBy(Type.Top),
//      Set(ObservationPath.Empty)
//    )
//  }
//
//  test("intersection absorbs the empty observation path when a nonempty path exists") {
//    val expectedPaths = Set(integerPath, booleanPath)
//    val domains = List(
//      Type.Intersection(Type.Top, Type.Intersection(Type.Integer, Type.Boolean)),
//      Type.Intersection(Type.Intersection(Type.Integer, Type.Top), Type.Boolean),
//      Type.Intersection(Type.Intersection(Type.Top, Type.Integer), Type.Boolean),
//      Type.Intersection(Type.Intersection(Type.Integer, Type.Boolean), Type.Top)
//    )
//
//    domains.foreach { domain =>
//      assertEquals(ObservationPathRelation.acceptedBy(domain), expectedPaths)
//    }
//  }
//
//  test("intersection retains the empty observation path when every component is top") {
//    val topIntersection = Type.Intersection(
//      Type.Top,
//      Type.Intersection(Type.Top, Type.Top)
//    )
//
//    assertEquals(
//      ObservationPathRelation.acceptedBy(topIntersection),
//      Set(ObservationPath.Empty)
//    )
//  }
//
//  test("arrow root labels use the canonical domain path family") {
//    val domain = Type.Intersection(Type.Integer, Type.Top)
//    val arrowType = Type.Arrow(domain, Type.Boolean)
//
//    assertEquals(
//      TypeLabels.compile(arrowType),
//      RootKeySet.one(RouteKey.Application(integerPath).rootKey)
//    )
//  }
//}
