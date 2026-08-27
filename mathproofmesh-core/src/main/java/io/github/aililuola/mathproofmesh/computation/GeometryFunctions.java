package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Exact coordinate-geometry assertions over arbitrary-precision rationals. */
public final class GeometryFunctions {
  private GeometryFunctions() {}

  public static HandlerEvidence run(ExperimentSpec spec) {
    ObjectNode arguments = spec.arguments();
    ObjectNode rawPoints =
        ComputationJson.requiredObject(arguments.get("points"), "arguments.points");
    Map<String, Point> points = new TreeMap<>();
    rawPoints.properties().forEach(entry -> points.put(entry.getKey(), point(entry.getValue())));
    ObjectNode assertion =
        ComputationJson.requiredObject(arguments.get("assertion"), "arguments.assertion");
    String kind = assertion.path("kind").asText("");
    List<String> names =
        assertion.has("points")
            ? ComputationJson.textList(assertion.get("points"), "assertion.points")
            : List.of();
    if (!points.keySet().containsAll(names)) {
      throw new IllegalArgumentException(
          "geometry assertion references an undeclared point");
    }

    Evaluation evaluation =
        switch (kind) {
          case "collinear" -> collinear(points, assertion, names);
          case "orientation" -> orientation(points, assertion, names);
          case "equal_distance" -> equalDistance(points, names);
          case "point_on_segment" -> pointOnSegment(points, names);
          case "concyclic" -> concyclic(points, assertion, names);
          case "parallel" -> parallelOrPerpendicular(points, assertion, names, true);
          case "perpendicular" ->
              parallelOrPerpendicular(points, assertion, names, false);
          case "equal_angle" -> equalAngle(points, assertion, names);
          default ->
              throw new IllegalArgumentException(
                  "unsupported exact geometry assertion: " + kind);
        };
    ObjectNode payload = ComputationJson.object();
    payload.set("points", rawPoints);
    payload.set("assertion", assertion);
    payload.setAll(evaluation.details);
    ObjectNode scope = ComputationJson.object().put("coordinate_type", "ExactRational");
    if (!evaluation.holds) {
      return new HandlerEvidence(
          ExperimentOutcome.COUNTEREXAMPLE_FOUND,
          EvidenceStrength.COUNTEREXAMPLE,
          scope,
          payload,
          null,
          true,
          1,
          true,
          List.of(
              "The assertion was replayed with exact rational determinants, dot products, and squared distances."),
          null);
    }
    return new HandlerEvidence(
        ExperimentOutcome.CERTIFIED,
        EvidenceStrength.FORMAL_CERTIFICATE,
        scope,
        null,
        payload,
        true,
        1,
        true,
        List.of(
            "The declared coordinate assertion was checked exactly; this certifies only that assertion."),
        null);
  }

  private static Evaluation collinear(
      Map<String, Point> points, ObjectNode assertion, List<String> names) {
    requireCount("collinear", names, 3);
    ExactRational determinant =
        determinant(points.get(names.get(0)), points.get(names.get(1)), points.get(names.get(2)));
    boolean expected = expected(assertion, "collinear");
    return new Evaluation(
        (determinant.signum() == 0) == expected,
        ComputationJson.object().put("determinant", determinant.toString()));
  }

  private static Evaluation orientation(
      Map<String, Point> points, ObjectNode assertion, List<String> names) {
    requireCount("orientation", names, 3);
    JsonNode expectedNode = assertion.get("expected_sign");
    if (expectedNode == null || !expectedNode.isIntegralNumber() || expectedNode.isBoolean()) {
      throw new IllegalArgumentException(
          "orientation expected_sign must be -1, 0, or 1");
    }
    int expectedSign = expectedNode.intValue();
    if (expectedSign < -1 || expectedSign > 1) {
      throw new IllegalArgumentException(
          "orientation expected_sign must be -1, 0, or 1");
    }
    ExactRational determinant =
        determinant(points.get(names.get(0)), points.get(names.get(1)), points.get(names.get(2)));
    return new Evaluation(
        determinant.signum() == expectedSign,
        ComputationJson.object().put("determinant", determinant.toString()));
  }

  private static Evaluation equalDistance(Map<String, Point> points, List<String> names) {
    requireCount("equal_distance", names, 4);
    ExactRational first = distanceSquared(points.get(names.get(0)), points.get(names.get(1)));
    ExactRational second = distanceSquared(points.get(names.get(2)), points.get(names.get(3)));
    return new Evaluation(
        first.equals(second),
        ComputationJson.object()
            .put("first_squared", first.toString())
            .put("second_squared", second.toString()));
  }

  private static Evaluation pointOnSegment(Map<String, Point> points, List<String> names) {
    requireCount("point_on_segment", names, 3);
    Point p = points.get(names.get(0));
    Point a = points.get(names.get(1));
    Point b = points.get(names.get(2));
    ExactRational determinant = determinant(a, b, p);
    boolean within =
        between(p.x, a.x, b.x) && between(p.y, a.y, b.y);
    return new Evaluation(
        determinant.signum() == 0 && within,
        ComputationJson.object()
            .put("determinant", determinant.toString())
            .put("within_bounding_box", within));
  }

  private static Evaluation concyclic(
      Map<String, Point> points, ObjectNode assertion, List<String> names) {
    requireCount("concyclic", names, 4);
    Point a = points.get(names.get(0));
    Point b = points.get(names.get(1));
    Point c = points.get(names.get(2));
    Point d = points.get(names.get(3));
    ExactRational determinant = concyclicDeterminant(a, b, c, d);
    boolean distinct = new HashSet<>(List.of(a, b, c, d)).size() == 4;
    boolean allCollinear =
        determinant(a, b, c).signum() == 0 && determinant(a, b, d).signum() == 0;
    boolean genuinelyConcyclic = determinant.signum() == 0 && distinct && !allCollinear;
    return new Evaluation(
        genuinelyConcyclic == expected(assertion, "concyclic"),
        ComputationJson.object()
            .put("determinant", determinant.toString())
            .put("pairwise_distinct", distinct)
            .put("all_collinear", allCollinear));
  }

  private static Evaluation parallelOrPerpendicular(
      Map<String, Point> points,
      ObjectNode assertion,
      List<String> names,
      boolean parallel) {
    String kind = parallel ? "parallel" : "perpendicular";
    requireCount(kind, names, 4);
    Point first = vector(points.get(names.get(0)), points.get(names.get(1)));
    Point second = vector(points.get(names.get(2)), points.get(names.get(3)));
    if (first.isZero() || second.isZero()) {
      throw new IllegalArgumentException(kind + " requires two nondegenerate segments");
    }
    ExactRational value = parallel ? cross(first, second) : dot(first, second);
    ObjectNode details =
        ComputationJson.object()
            .put(parallel ? "cross_product" : "dot_product", value.toString());
    return new Evaluation(
        (value.signum() == 0) == expected(assertion, kind), details);
  }

  private static Evaluation equalAngle(
      Map<String, Point> points, ObjectNode assertion, List<String> names) {
    requireCount("equal_angle", names, 6);
    Point firstLeft = vector(points.get(names.get(1)), points.get(names.get(0)));
    Point firstRight = vector(points.get(names.get(1)), points.get(names.get(2)));
    Point secondLeft = vector(points.get(names.get(4)), points.get(names.get(3)));
    Point secondRight = vector(points.get(names.get(4)), points.get(names.get(5)));
    if (List.of(firstLeft, firstRight, secondLeft, secondRight).stream()
        .anyMatch(Point::isZero)) {
      throw new IllegalArgumentException("equal_angle requires nondegenerate rays");
    }
    ExactRational firstCross = cross(firstLeft, firstRight).abs();
    ExactRational firstDot = dot(firstLeft, firstRight);
    ExactRational secondCross = cross(secondLeft, secondRight).abs();
    ExactRational secondDot = dot(secondLeft, secondRight);
    boolean anglesEqual =
        firstDot.signum() == secondDot.signum()
            && firstCross.multiply(secondDot).equals(secondCross.multiply(firstDot));
    return new Evaluation(
        anglesEqual == expected(assertion, "equal_angle"),
        ComputationJson.object()
            .put("first_abs_cross", firstCross.toString())
            .put("first_dot", firstDot.toString())
            .put("second_abs_cross", secondCross.toString())
            .put("second_dot", secondDot.toString()));
  }

  private static Point point(JsonNode raw) {
    if (raw == null || !raw.isArray() || raw.size() != 2) {
      throw new IllegalArgumentException(
          "each point must contain exactly two coordinates");
    }
    return new Point(
        ExactRational.parse(raw.get(0), "point x-coordinate"),
        ExactRational.parse(raw.get(1), "point y-coordinate"));
  }

  private static ExactRational determinant(Point a, Point b, Point c) {
    return b.x
        .subtract(a.x)
        .multiply(c.y.subtract(a.y))
        .subtract(b.y.subtract(a.y).multiply(c.x.subtract(a.x)));
  }

  private static ExactRational distanceSquared(Point a, Point b) {
    return a.x.subtract(b.x).pow(2).add(a.y.subtract(b.y).pow(2));
  }

  private static ExactRational concyclicDeterminant(Point a, Point b, Point c, Point d) {
    List<Row> rows = new ArrayList<>(3);
    for (Point point : List.of(b, c, d)) {
      ExactRational dx = point.x.subtract(a.x);
      ExactRational dy = point.y.subtract(a.y);
      rows.add(new Row(dx, dy, dx.multiply(dx).add(dy.multiply(dy))));
    }
    Row r0 = rows.get(0);
    Row r1 = rows.get(1);
    Row r2 = rows.get(2);
    return r0.x
        .multiply(r1.y.multiply(r2.norm).subtract(r1.norm.multiply(r2.y)))
        .subtract(
            r0.y.multiply(r1.x.multiply(r2.norm).subtract(r1.norm.multiply(r2.x))))
        .add(r0.norm.multiply(r1.x.multiply(r2.y).subtract(r1.y.multiply(r2.x))));
  }

  private static Point vector(Point tail, Point head) {
    return new Point(head.x.subtract(tail.x), head.y.subtract(tail.y));
  }

  private static ExactRational cross(Point first, Point second) {
    return first.x.multiply(second.y).subtract(first.y.multiply(second.x));
  }

  private static ExactRational dot(Point first, Point second) {
    return first.x.multiply(second.x).add(first.y.multiply(second.y));
  }

  private static boolean expected(ObjectNode assertion, String kind) {
    JsonNode node = assertion.get("expected");
    if (node == null) {
      return true;
    }
    if (!node.isBoolean()) {
      throw new IllegalArgumentException(kind + " expected must be a boolean");
    }
    return node.booleanValue();
  }

  private static boolean between(
      ExactRational value, ExactRational first, ExactRational second) {
    ExactRational lower = first.compareTo(second) <= 0 ? first : second;
    ExactRational upper = first.compareTo(second) <= 0 ? second : first;
    return value.compareTo(lower) >= 0 && value.compareTo(upper) <= 0;
  }

  private static void requireCount(String kind, List<String> names, int count) {
    if (names.size() != count) {
      throw new IllegalArgumentException(kind + " requires " + count + " points");
    }
  }

  private record Point(ExactRational x, ExactRational y) {
    private boolean isZero() {
      return x.signum() == 0 && y.signum() == 0;
    }
  }

  private record Row(ExactRational x, ExactRational y, ExactRational norm) {}

  private record Evaluation(boolean holds, ObjectNode details) {}
}
