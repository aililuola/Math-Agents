package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class Phase17IntegerSearchBranchTest {

  @ParameterizedTest
  @MethodSource("relations")
  void everyExactRelationOperatorCoversSatisfiedAndViolatingAssignments(
      String relation, String rhs, ExperimentOutcome expected) {
    String arguments =
        "{\"target\":{\"lhs\":\"x\",\"rhs\":\""
            + rhs
            + "\",\"relation\":\""
            + relation
            + "\"}}";
    HandlerEvidence evidence =
        IntegerSearchFunctions.run(
            ComputationFixtures.spec(
                ComputationMethod.BOUNDED_INTEGER_SEARCH,
                arguments,
                "{\"x\":{\"min\":0,\"max\":1}}"));

    assertThat(evidence.outcome()).isEqualTo(expected);
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{}",
        "{\"target\":{\"lhs\":\"x\",\"relation\":\"bad\"}}",
        "{\"target\":{\"lhs\":\"y\"}}",
        "{\"target\":{\"lhs\":\"x\"},\"constraints\":{}}",
        "{\"target\":{\"lhs\":\"x\"},\"constraints\":[{\"lhs\":\"y\"}]}"
      })
  void malformedTargetsConstraintsAndVariablesFailClosed(String arguments) {
    assertThatThrownBy(
            () ->
                IntegerSearchFunctions.run(
                    ComputationFixtures.spec(
                        ComputationMethod.BOUNDED_INTEGER_SEARCH,
                        arguments,
                        "{\"x\":{\"min\":0,\"max\":1}}")))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @MethodSource("invalidDomains")
  void invalidOrOversizedDomainsFailClosed(String domains) {
    assertThatThrownBy(
            () ->
                IntegerSearchFunctions.run(
                    ComputationFixtures.spec(
                        ComputationMethod.BOUNDED_INTEGER_SEARCH,
                        "{\"target\":{\"lhs\":\"x\"}}",
                        domains)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @MethodSource("constraintCases")
  void constraintsCoverSkipReplayAndNoCounterexamplePaths(
      String constraints, ExperimentOutcome expected) {
    HandlerEvidence evidence =
        IntegerSearchFunctions.run(
            ComputationFixtures.spec(
                ComputationMethod.BOUNDED_INTEGER_SEARCH,
                "{\"target\":{\"lhs\":\"x\",\"rhs\":\"0\",\"relation\":\"eq\"},"
                    + "\"constraints\":"
                    + constraints
                    + "}",
                "{\"x\":{\"min\":0,\"max\":1}}"));
    assertThat(evidence.outcome()).isEqualTo(expected);
  }

  private static Stream<Arguments> relations() {
    return Stream.of(
        Arguments.of("eq", "x", ExperimentOutcome.NOT_REFUTED),
        Arguments.of("ne", "x", ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of("le", "1", ExperimentOutcome.NOT_REFUTED),
        Arguments.of("lt", "2", ExperimentOutcome.NOT_REFUTED),
        Arguments.of("ge", "0", ExperimentOutcome.NOT_REFUTED),
        Arguments.of("gt", "-1", ExperimentOutcome.NOT_REFUTED));
  }

  private static Stream<String> invalidDomains() {
    return Stream.of(
        "{}",
        "{\"x\":1}",
        "{\"x\":{}}",
        "{\"x\":{\"min\":0}}",
        "{\"x\":{\"min\":2,\"max\":1}}",
        "{\"x\":{\"min\":0,\"max\":2000}}");
  }

  private static Stream<Arguments> constraintCases() {
    return Stream.of(
        Arguments.of("[{\"lhs\":\"x\",\"rhs\":\"0\",\"relation\":\"eq\"}]",
            ExperimentOutcome.NOT_REFUTED),
        Arguments.of("[{\"lhs\":\"x\",\"rhs\":\"2\",\"relation\":\"lt\"}]",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND));
  }
}
