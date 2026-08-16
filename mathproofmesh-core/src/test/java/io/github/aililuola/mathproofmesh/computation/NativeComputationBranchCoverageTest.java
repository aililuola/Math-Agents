package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.BooleanNode;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NativeComputationBranchCoverageTest {
  @Test
  void exactLinearAlgebraCoversEveryOperationAndMathematicalOutcome() {
    assertVerified(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"determinant\",\"matrix\":[[\"7\"]]}");
    assertVerified(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"determinant\",\"matrix\":[[\"0\",\"2\"],[\"3\",\"4\"]]}");
    assertVerified(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"determinant\",\"matrix\":[[\"1\",\"2\"],[\"2\",\"4\"]]}");
    assertVerified(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"rank\",\"matrix\":[[\"0\",\"1\",\"0\"],[\"0\",\"0\",\"0\"]]}");
    assertVerified(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"solve\",\"matrix\":[[\"1\",\"0\"],[\"0\",\"1\"]],"
            + "\"rhs\":[\"2\",\"3\"]}");
    assertVerified(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"solve\",\"matrix\":[[\"1\"],[\"1\"]],"
            + "\"rhs\":[\"1\",\"2\"]}");
    assertVerified(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"solve\",\"matrix\":[[\"1\",\"2\"]],\"rhs\":[\"3\"]}");
    assertVerified(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"nullspace\",\"matrix\":[[\"1\",\"2\"],[\"2\",\"4\"]]}");
    assertVerified(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"nullspace\",\"matrix\":[[\"1\",\"0\"],[\"0\",\"1\"]]}");
    assertVerified(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"span_membership\",\"matrix\":[[\"1\"],[\"2\"]],"
            + "\"vector\":[\"3\",\"6\"]}");
    assertVerified(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"span_membership\",\"matrix\":[[\"1\"],[\"0\"]],"
            + "\"vector\":[\"0\",\"1\"]}");
  }

  @Test
  void finiteMapsCoverPositiveNegativeAndDerivedOperations() {
    String bijection =
        "\"domain\":[\"a\",\"b\"],\"codomain\":[\"x\",\"y\"],"
            + "\"mapping\":{\"a\":\"x\",\"b\":\"y\"}";
    String collision =
        "\"domain\":[\"a\",\"b\"],\"codomain\":[\"x\",\"y\"],"
            + "\"mapping\":{\"a\":\"x\",\"b\":\"x\"}";
    assertVerified(ComputationMethod.FINITE_SET_MAP_CHECK, map("injective", bijection, ""));
    assertVerified(ComputationMethod.FINITE_SET_MAP_CHECK, map("surjective", collision, ""));
    assertVerified(ComputationMethod.FINITE_SET_MAP_CHECK, map("bijective", collision, ""));
    assertVerified(ComputationMethod.FINITE_SET_MAP_CHECK, map("image", collision, ""));
    assertVerified(
        ComputationMethod.FINITE_SET_MAP_CHECK,
        map("preimage", collision, ",\"target\":\"x\""));
    assertVerified(
        ComputationMethod.FINITE_SET_MAP_CHECK,
        map("preimage", collision, ",\"target\":\"y\""));
    assertVerified(
        ComputationMethod.FINITE_SET_MAP_CHECK,
        map("cardinality_equality", bijection, ""));
    assertVerified(
        ComputationMethod.FINITE_SET_MAP_CHECK,
        "{\"operation\":\"cardinality_equality\",\"domain\":[\"a\"],"
            + "\"codomain\":[\"x\",\"y\"],\"mapping\":{\"a\":\"x\"}}");
  }

  @Test
  void hypergraphsCoverHittingMinimalityAndExhaustiveEnumeration() {
    String base =
        "\"vertices\":[\"a\",\"b\",\"c\"],"
            + "\"edges\":[[\"a\",\"b\"],[\"b\",\"c\"]]";
    assertVerified(
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        hypergraph("is_hitting_set", base, "[\"b\"]"));
    assertVerified(
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        hypergraph("is_hitting_set", base, "[\"a\"]"));
    assertVerified(
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        hypergraph("is_minimal_hitting_set", base, "[\"b\"]"));
    assertVerified(
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        hypergraph("is_minimal_hitting_set", base, "[\"a\",\"b\",\"c\"]"));
    assertVerified(
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        "{\"operation\":\"enumerate_minimal_transversals\"," + base + "}");
  }

  @Test
  void graphCertificatesCoverEveryPropertyAndFailClosedBoundaries() {
    assertGraphVerified(
        "proper_coloring",
        false,
        "[\"a\",\"b\"]",
        "[[\"a\",\"b\"]]",
        "{\"colors\":{\"a\":\"red\",\"b\":\"blue\"}}",
        true);
    assertGraphVerified(
        "proper_coloring",
        false,
        "[\"a\",\"b\"]",
        "[[\"a\",\"b\"]]",
        "{\"colors\":{\"a\":\"red\",\"b\":\"red\"}}",
        false);
    assertGraphVerified(
        "path",
        false,
        "[\"a\",\"b\",\"c\"]",
        "[[\"a\",\"b\"],[\"b\",\"c\"]]",
        "{\"vertices\":[\"a\",\"b\",\"c\"]}",
        true);
    assertGraphVerified(
        "path",
        false,
        "[\"a\",\"b\",\"c\"]",
        "[[\"a\",\"b\"]]",
        "{\"vertices\":[\"a\",\"c\"]}",
        false);
    assertGraphVerified(
        "cycle",
        false,
        "[\"a\",\"b\",\"c\"]",
        "[[\"a\",\"b\"],[\"b\",\"c\"],[\"c\",\"a\"]]",
        "{\"vertices\":[\"a\",\"b\",\"c\"]}",
        true);
    assertGraphVerified(
        "cycle",
        true,
        "[\"a\",\"b\"]",
        "[[\"a\",\"b\"],[\"b\",\"a\"]]",
        "{\"vertices\":[\"a\",\"b\"]}",
        true);
    assertGraphVerified(
        "matching",
        false,
        "[\"a\",\"b\",\"c\",\"d\"]",
        "[[\"a\",\"b\"],[\"c\",\"d\"]]",
        "{\"edges\":[[\"b\",\"a\"],[\"c\",\"d\"]]}",
        true);
    assertGraphVerified(
        "matching",
        false,
        "[\"a\",\"b\",\"c\"]",
        "[[\"a\",\"b\"],[\"b\",\"c\"]]",
        "{\"edges\":[[\"a\",\"b\"],[\"b\",\"c\"]]}",
        false);
    assertGraphVerified(
        "connected",
        false,
        "[\"a\",\"b\",\"c\"]",
        "[[\"a\",\"b\"],[\"b\",\"c\"]]",
        "{}",
        true);
    assertGraphVerified(
        "connected",
        false,
        "[\"a\",\"b\",\"c\"]",
        "[[\"a\",\"b\"]]",
        "{}",
        true);
    assertGraphVerified(
        "connected",
        true,
        "[\"a\",\"b\"]",
        "[[\"a\",\"b\"],[\"b\",\"a\"]]",
        "{}",
        true);
    assertGraphVerified(
        "connected",
        true,
        "[\"a\",\"b\"]",
        "[[\"a\",\"b\"]]",
        "{}",
        true);
  }

  @Test
  void nativeParsersRejectMalformedOrUnsupportedRequests() {
    assertNativeFailure(ComputationMethod.EXACT_LINEAR_ALGEBRA, "{\"matrix\":[[1]]}");
    assertNativeFailure(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"rank\",\"matrix\":[]}");
    assertNativeFailure(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"rank\",\"matrix\":[1]}");
    assertNativeFailure(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"rank\",\"matrix\":[[]]}");
    assertNativeFailure(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"rank\",\"matrix\":[[1],[1,2]]}");
    assertNativeFailure(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"determinant\",\"matrix\":[[1,2]]}");
    assertNativeFailure(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"solve\",\"matrix\":[[1]],\"rhs\":[1,2]}");
    assertNativeFailure(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"span_membership\",\"matrix\":[[1]],\"vector\":[1,2]}");
    assertNativeFailure(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"operation\":\"mystery\",\"matrix\":[[1]]}");

    String validMap =
        "\"domain\":[\"a\"],\"codomain\":[\"x\"],\"mapping\":{\"a\":\"x\"}";
    assertNativeFailure(
        ComputationMethod.FINITE_SET_MAP_CHECK,
        map("injective", "\"domain\":[\"a\",\"a\"],\"codomain\":[\"x\"],"
            + "\"mapping\":{\"a\":\"x\"}", ""));
    assertNativeFailure(
        ComputationMethod.FINITE_SET_MAP_CHECK,
        "{\"operation\":\"injective\",\"domain\":[{}],\"codomain\":[\"x\"],"
            + "\"mapping\":{\"a\":\"x\"}}");
    assertNativeFailure(
        ComputationMethod.FINITE_SET_MAP_CHECK,
        "{\"operation\":\"injective\",\"domain\":[\"a\"],\"codomain\":[\"x\"],"
            + "\"mapping\":[]}");
    assertNativeFailure(
        ComputationMethod.FINITE_SET_MAP_CHECK,
        "{\"operation\":\"injective\",\"domain\":[\"a\"],\"codomain\":[\"x\"],"
            + "\"mapping\":{}}");
    assertNativeFailure(
        ComputationMethod.FINITE_SET_MAP_CHECK,
        "{\"operation\":\"injective\",\"domain\":[\"a\"],\"codomain\":[\"x\"],"
            + "\"mapping\":{\"a\":\"z\"}}");
    assertNativeFailure(
        ComputationMethod.FINITE_SET_MAP_CHECK,
        map("preimage", validMap, ",\"target\":\"z\""));
    assertNativeFailure(ComputationMethod.FINITE_SET_MAP_CHECK, map("mystery", validMap, ""));

    String hypergraph = "\"vertices\":[\"a\"],\"edges\":[[\"a\"]]";
    assertNativeFailure(
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        "{\"operation\":\"is_hitting_set\",\"vertices\":[\"a\",\"a\"],"
            + "\"edges\":[[\"a\"]],\"candidate\":[\"a\"]}");
    assertNativeFailure(
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        "{\"operation\":\"is_hitting_set\",\"vertices\":[\"a\"],"
            + "\"edges\":[[]],\"candidate\":[\"a\"]}");
    assertNativeFailure(
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        "{\"operation\":\"is_hitting_set\",\"vertices\":[\"a\"],"
            + "\"edges\":[[\"z\"]],\"candidate\":[\"a\"]}");
    assertNativeFailure(
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        hypergraph("is_hitting_set", hypergraph, "[\"z\"]"));
    assertNativeFailure(
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        "{\"operation\":\"mystery\"," + hypergraph + "}");

    var vertices = java.util.stream.IntStream.range(0, 31).mapToObj(i -> "v" + i).toList();
    assertThatThrownBy(
            () -> HypergraphTransversalFunctions.enumerate(vertices, List.of(), Integer.MAX_VALUE))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> HypergraphTransversalFunctions.enumerate(List.of("a", "b"), List.of(), 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () -> new HypergraphTransversalFunctions.Enumeration(null, -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void exactRationalsNormalizeEverySupportedRepresentationAndRejectInvalidValues() {
    assertThat(new ExactRational(BigInteger.valueOf(2), BigInteger.valueOf(-4)).toString())
        .isEqualTo("-1/2");
    assertThat(ExactRational.parse("1.25", "decimal").toString()).isEqualTo("5/4");
    assertThat(ExactRational.parse("1E+2", "decimal").toString()).isEqualTo("100");
    assertThat(ExactRational.parse(" 6 / 8 ", "fraction").toString()).isEqualTo("3/4");
    assertThat(ExactRational.parse("-2", "integer").abs().toString()).isEqualTo("2");
    assertThat(ExactRational.parse("2", "integer").abs().toString()).isEqualTo("2");
    assertThat(ExactRational.parse("2", "integer").pow(-2).toString()).isEqualTo("1/4");
    assertThat(ExactRational.parse("2", "integer").pow(3).toString()).isEqualTo("8");
    assertThat(ExactRational.parse("2", "integer").compareTo(ExactRational.ONE)).isPositive();
    assertThat(ExactRational.parse("2", "integer").toBigIntegerExact("integer"))
        .isEqualTo(BigInteger.TWO);
    assertThatThrownBy(() -> new ExactRational(BigInteger.ONE, BigInteger.ZERO))
        .isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(() -> ExactRational.parse((String) null, "value"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExactRational.parse(" ", "value"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExactRational.parse("1/2/3", "value"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExactRational.parse("x", "value"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExactRational.parse(BooleanNode.TRUE, "value"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExactRational.parse("1/2", "value").toBigIntegerExact("value"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExactRational.ONE.divide(ExactRational.ZERO))
        .isInstanceOf(ArithmeticException.class);
    assertThatThrownBy(() -> ExactRational.ZERO.pow(-1))
        .isInstanceOf(ArithmeticException.class);
  }

  @Test
  void matrixAndHypergraphHelpersDefensivelyValidateDimensionsAndResults() {
    var one = ExactRational.ONE;
    assertThatThrownBy(
            () ->
                ExactLinearAlgebraFunctions.multiply(
                    new ExactRational[][] {{one, one}}, new ExactRational[] {one}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(
            () ->
                ExactLinearAlgebraFunctions.determinant(
                    new ExactRational[][] {{one, one}}))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(HypergraphTransversalFunctions.hits(Set.of(), List.of())).isTrue();
    assertThat(HypergraphTransversalFunctions.minimal(Set.of(), List.of())).isTrue();
    assertThat(HypergraphTransversalFunctions.minimal(Set.of("a"), List.of())).isFalse();
    var enumeration = new HypergraphTransversalFunctions.Enumeration(null, 0);
    assertThat(enumeration.minimal()).isEmpty();
  }

  private static void assertVerified(ComputationMethod method, String arguments) {
    var outcome =
        ComputationIssue010TestSupport.run(
            ComputationFixtures.broker("branch-" + method.value() + '-' + arguments.hashCode()),
            ComputationFixtures.spec(method, arguments));
    assertThat(outcome.result().outcome()).isNotEqualTo(ExperimentOutcome.ERROR);
    assertThat(outcome.verificationReceipt().valid()).isTrue();
  }

  private static void assertGraphVerified(
      String property,
      boolean directed,
      String nodes,
      String edges,
      String certificate,
      boolean verificationExpected) {
    String arguments =
        "{\"property\":\""
            + property
            + "\",\"graph\":{\"directed\":"
            + directed
            + ",\"nodes\":"
            + nodes
            + ",\"edges\":"
            + edges
            + "},\"certificate\":"
            + certificate
            + "}";
    var outcome =
        ComputationIssue010TestSupport.run(
            ComputationFixtures.broker("graph-" + arguments.hashCode()),
            ComputationFixtures.spec(ComputationMethod.GRAPH_CERTIFICATE, arguments));
    assertThat(outcome.verificationReceipt().valid()).isEqualTo(verificationExpected);
  }

  private static void assertNativeFailure(ComputationMethod method, String arguments) {
    var spec = ComputationFixtures.spec(method, arguments);
    assertThatThrownBy(
            () -> {
              switch (method) {
                case EXACT_LINEAR_ALGEBRA -> ExactLinearAlgebraFunctions.run(spec);
                case FINITE_SET_MAP_CHECK -> FiniteSetMapFunctions.run(spec);
                case HYPERGRAPH_TRANSVERSAL -> HypergraphTransversalFunctions.run(spec);
                default -> throw new AssertionError("unexpected method " + method);
              }
            })
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static String map(String operation, String body, String suffix) {
    return "{\"operation\":\"" + operation + "\"," + body + suffix + "}";
  }

  private static String hypergraph(String operation, String body, String candidate) {
    return "{\"operation\":\""
        + operation
        + "\","
        + body
        + ",\"candidate\":"
        + candidate
        + "}";
  }
}
