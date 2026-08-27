package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/** Independent exact-coordinate replay for geometry result artifacts. */
final class IndependentGeometryCertificateVerifier {
  private IndependentGeometryCertificateVerifier() {}

  static boolean verify(ExperimentSpec spec, ComputationResultArtifact result) {
    try {
      ObjectNode arguments = spec.arguments();
      ObjectNode rawPoints =
          ComputationJson.requiredObject(arguments.get("points"), "points");
      Map<String, Point> points = new TreeMap<>();
      rawPoints
          .properties()
          .forEach(entry -> points.put(entry.getKey(), point(entry.getValue())));
      ObjectNode assertion =
          ComputationJson.requiredObject(arguments.get("assertion"), "assertion");
      List<String> names =
          assertion.has("points")
              ? ComputationJson.textList(assertion.get("points"), "assertion.points")
              : List.of();
      if (!points.keySet().containsAll(names)) {
        return false;
      }
      Evaluation evaluation =
          switch (assertion.path("kind").asText("")) {
            case "collinear" -> collinear(points, assertion, names);
            case "orientation" -> orientation(points, assertion, names);
            case "equal_distance" -> equalDistance(points, names);
            case "point_on_segment" -> pointOnSegment(points, names);
            case "concyclic" -> concyclic(points, assertion, names);
            case "parallel" -> parallelOrPerpendicular(points, assertion, names, true);
            case "perpendicular" -> parallelOrPerpendicular(points, assertion, names, false);
            case "equal_angle" -> equalAngle(points, assertion, names);
            default -> throw new IllegalArgumentException("unsupported geometry assertion");
          };
      ObjectNode expected = ComputationJson.object();
      expected.set("points", rawPoints);
      expected.set("assertion", assertion);
      expected.setAll(evaluation.details());
      JsonNode actual =
          result.outcome() == ExperimentOutcome.CERTIFIED
              ? result.certificate()
              : result.outcome() == ExperimentOutcome.COUNTEREXAMPLE_FOUND
                  ? result.counterexample()
                  : null;
      boolean outcomeAgrees =
          evaluation.holds()
              ? result.outcome() == ExperimentOutcome.CERTIFIED
              : result.outcome() == ExperimentOutcome.COUNTEREXAMPLE_FOUND;
      return outcomeAgrees
          && actual != null
          && CanonicalJson.stableHash(expected).equals(CanonicalJson.stableHash(actual));
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private static Evaluation collinear(
      Map<String, Point> points, ObjectNode assertion, List<String> names) {
    requireCount(names, 3);
    ExactRational determinant =
        determinant(points.get(names.get(0)), points.get(names.get(1)), points.get(names.get(2)));
    return new Evaluation(
        (determinant.signum() == 0) == expected(assertion),
        ComputationJson.object().put("determinant", determinant.toString()));
  }

  private static Evaluation orientation(
      Map<String, Point> points, ObjectNode assertion, List<String> names) {
    requireCount(names, 3);
    int expectedSign = assertion.path("expected_sign").asInt(Integer.MIN_VALUE);
    if (expectedSign < -1 || expectedSign > 1) {
      throw new IllegalArgumentException("invalid orientation sign");
    }
    ExactRational determinant =
        determinant(points.get(names.get(0)), points.get(names.get(1)), points.get(names.get(2)));
    return new Evaluation(
        determinant.signum() == expectedSign,
        ComputationJson.object().put("determinant", determinant.toString()));
  }

  private static Evaluation equalDistance(
      Map<String, Point> points, List<String> names) {
    requireCount(names, 4);
    ExactRational first = distanceSquared(points.get(names.get(0)), points.get(names.get(1)));
    ExactRational second = distanceSquared(points.get(names.get(2)), points.get(names.get(3)));
    return new Evaluation(
        first.equals(second),
        ComputationJson.object()
            .put("first_squared", first.toString())
            .put("second_squared", second.toString()));
  }

  private static Evaluation pointOnSegment(
      Map<String, Point> points, List<String> names) {
    requireCount(names, 3);
    Point p = points.get(names.get(0));
    Point a = points.get(names.get(1));
    Point b = points.get(names.get(2));
    ExactRational determinant = determinant(a, b, p);
    boolean within = between(p.x(), a.x(), b.x()) && between(p.y(), a.y(), b.y());
    return new Evaluation(
        determinant.signum() == 0 && within,
        ComputationJson.object()
            .put("determinant", determinant.toString())
            .put("within_bounding_box", within));
  }

  private static Evaluation concyclic(
      Map<String, Point> points, ObjectNode assertion, List<String> names) {
    requireCount(names, 4);
    Point a = points.get(names.get(0));
    Point b = points.get(names.get(1));
    Point c = points.get(names.get(2));
    Point d = points.get(names.get(3));
    ExactRational value = concyclicDeterminant(a, b, c, d);
    boolean distinct = new HashSet<>(List.of(a, b, c, d)).size() == 4;
    boolean allCollinear =
        determinant(a, b, c).signum() == 0 && determinant(a, b, d).signum() == 0;
    boolean holds = value.signum() == 0 && distinct && !allCollinear;
    return new Evaluation(
        holds == expected(assertion),
        ComputationJson.object()
            .put("determinant", value.toString())
            .put("pairwise_distinct", distinct)
            .put("all_collinear", allCollinear));
  }

  private static Evaluation parallelOrPerpendicular(
      Map<String, Point> points,
      ObjectNode assertion,
      List<String> names,
      boolean parallel) {
    requireCount(names, 4);
    Point first = vector(points.get(names.get(0)), points.get(names.get(1)));
    Point second = vector(points.get(names.get(2)), points.get(names.get(3)));
    if (first.zero() || second.zero()) {
      throw new IllegalArgumentException("degenerate segment");
    }
    ExactRational value = parallel ? cross(first, second) : dot(first, second);
    return new Evaluation(
        (value.signum() == 0) == expected(assertion),
        ComputationJson.object()
            .put(parallel ? "cross_product" : "dot_product", value.toString()));
  }

  private static Evaluation equalAngle(
      Map<String, Point> points, ObjectNode assertion, List<String> names) {
    requireCount(names, 6);
    Point firstLeft = vector(points.get(names.get(1)), points.get(names.get(0)));
    Point firstRight = vector(points.get(names.get(1)), points.get(names.get(2)));
    Point secondLeft = vector(points.get(names.get(4)), points.get(names.get(3)));
    Point secondRight = vector(points.get(names.get(4)), points.get(names.get(5)));
    if (List.of(firstLeft, firstRight, secondLeft, secondRight).stream()
        .anyMatch(Point::zero)) {
      throw new IllegalArgumentException("degenerate ray");
    }
    ExactRational firstCross = cross(firstLeft, firstRight).abs();
    ExactRational firstDot = dot(firstLeft, firstRight);
    ExactRational secondCross = cross(secondLeft, secondRight).abs();
    ExactRational secondDot = dot(secondLeft, secondRight);
    boolean equal =
        firstDot.signum() == secondDot.signum()
            && firstCross.multiply(secondDot).equals(secondCross.multiply(firstDot));
    return new Evaluation(
        equal == expected(assertion),
        ComputationJson.object()
            .put("first_abs_cross", firstCross.toString())
            .put("first_dot", firstDot.toString())
            .put("second_abs_cross", secondCross.toString())
            .put("second_dot", secondDot.toString()));
  }

  private static Point point(JsonNode raw) {
    if (raw == null || !raw.isArray() || raw.size() != 2) {
      throw new IllegalArgumentException("point must have two coordinates");
    }
    return new Point(
        ExactRational.parse(raw.get(0), "point x"),
        ExactRational.parse(raw.get(1), "point y"));
  }

  private static ExactRational determinant(Point a, Point b, Point c) {
    return b.x()
        .subtract(a.x())
        .multiply(c.y().subtract(a.y()))
        .subtract(b.y().subtract(a.y()).multiply(c.x().subtract(a.x())));
  }

  private static ExactRational distanceSquared(Point a, Point b) {
    return a.x().subtract(b.x()).pow(2).add(a.y().subtract(b.y()).pow(2));
  }

  private static ExactRational concyclicDeterminant(
      Point a, Point b, Point c, Point d) {
    List<Row> rows = new ArrayList<>(3);
    for (Point point : List.of(b, c, d)) {
      ExactRational x = point.x().subtract(a.x());
      ExactRational y = point.y().subtract(a.y());
      rows.add(new Row(x, y, x.multiply(x).add(y.multiply(y))));
    }
    Row first = rows.get(0);
    Row second = rows.get(1);
    Row third = rows.get(2);
    return first
        .x()
        .multiply(
            second.y().multiply(third.norm()).subtract(second.norm().multiply(third.y())))
        .subtract(
            first
                .y()
                .multiply(
                    second.x().multiply(third.norm()).subtract(second.norm().multiply(third.x()))))
        .add(
            first
                .norm()
                .multiply(
                    second.x().multiply(third.y()).subtract(second.y().multiply(third.x()))));
  }

  private static Point vector(Point tail, Point head) {
    return new Point(head.x().subtract(tail.x()), head.y().subtract(tail.y()));
  }

  private static ExactRational cross(Point first, Point second) {
    return first.x().multiply(second.y()).subtract(first.y().multiply(second.x()));
  }

  private static ExactRational dot(Point first, Point second) {
    return first.x().multiply(second.x()).add(first.y().multiply(second.y()));
  }

  private static boolean expected(ObjectNode assertion) {
    JsonNode value = assertion.get("expected");
    if (value == null) {
      return true;
    }
    if (!value.isBoolean()) {
      throw new IllegalArgumentException("expected must be boolean");
    }
    return value.booleanValue();
  }

  private static boolean between(
      ExactRational value, ExactRational first, ExactRational second) {
    ExactRational lower = first.compareTo(second) <= 0 ? first : second;
    ExactRational upper = first.compareTo(second) <= 0 ? second : first;
    return value.compareTo(lower) >= 0 && value.compareTo(upper) <= 0;
  }

  private static void requireCount(List<String> names, int count) {
    if (names.size() != count) {
      throw new IllegalArgumentException("wrong point count");
    }
  }

  private record Point(ExactRational x, ExactRational y) {
    private boolean zero() {
      return x.signum() == 0 && y.signum() == 0;
    }
  }

  private record Row(ExactRational x, ExactRational y, ExactRational norm) {}

  private record Evaluation(boolean holds, ObjectNode details) {}
}
