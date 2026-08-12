package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

class Phase17SequenceModularBranchTest {

  @ParameterizedTest
  @MethodSource("greedySuccesses")
  void everyGreedyRuleAndClaimShapeIsDeterministic(
      String arguments, ExperimentOutcome outcome) {
    HandlerEvidence first =
        SequenceFunctions.runBoundedGreedySequence(
            ComputationFixtures.spec(
                ComputationMethod.BOUNDED_GREEDY_SEQUENCE, arguments));
    HandlerEvidence replay =
        SequenceFunctions.runBoundedGreedySequence(
            ComputationFixtures.spec(
                ComputationMethod.BOUNDED_GREEDY_SEQUENCE, arguments));

    assertThat(first.outcome()).isEqualTo(outcome);
    assertThat(first.certificate()).isEqualTo(replay.certificate());
    assertThat(first.counterexample()).isEqualTo(replay.counterexample());
  }

  @ParameterizedTest
  @MethodSource("invalidGreedy")
  void greedySearchGuardsInvalidOrUnboundedRequests(String arguments, int maxCases) {
    var spec =
        ComputationFixtures.spec(
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            arguments,
            "{}",
            ComputationPurpose.CHECK_DERIVED_IDENTITY,
            false,
            maxCases);
    assertThatThrownBy(() -> SequenceFunctions.runBoundedGreedySequence(spec))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @MethodSource("periodSuccesses")
  void periodChecksCoverDefaultAndExplicitStarts(
      String arguments, ExperimentOutcome expected) {
    assertThat(
            SequenceFunctions.runCandidatePeriodCheck(
                    ComputationFixtures.spec(
                        ComputationMethod.CANDIDATE_PERIOD_CHECK, arguments))
                .outcome())
        .isEqualTo(expected);
  }

  @ParameterizedTest
  @MethodSource("invalidPeriods")
  void periodChecksRejectEmptyOutOfRangeAndOverBudgetInputs(
      String arguments, int maxCases) {
    var spec =
        ComputationFixtures.spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            arguments,
            "{}",
            ComputationPurpose.CHECK_DERIVED_IDENTITY,
            false,
            maxCases);
    assertThatThrownBy(() -> SequenceFunctions.runCandidatePeriodCheck(spec))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @ParameterizedTest
  @MethodSource("modularSuccesses")
  void modularDomainsRelationsAndCertificationPathsAreCovered(
      String arguments, String domains, ExperimentOutcome expected) {
    HandlerEvidence evidence =
        ModularFunctions.run(
            ComputationFixtures.spec(
                ComputationMethod.MODULAR_EXHAUSTIVE, arguments, domains));

    assertThat(evidence.outcome()).isEqualTo(expected);
    assertThat(evidence.casesChecked()).isPositive();
  }

  @ParameterizedTest
  @MethodSource("invalidModular")
  void modularRequestsFailClosedAcrossVariableAndDomainGuards(
      String arguments, String domains, int maxCases) {
    var spec =
        ComputationFixtures.spec(
            ComputationMethod.MODULAR_EXHAUSTIVE,
            arguments,
            domains,
            ComputationPurpose.CHECK_DERIVED_IDENTITY,
            false,
            maxCases);
    assertThatThrownBy(() -> ModularFunctions.run(spec))
        .isInstanceOfAny(IllegalArgumentException.class, ArithmeticException.class);
  }

  private static Stream<Arguments> greedySuccesses() {
    return Stream.of(
        Arguments.of(
            "{\"initial_values\":[1],\"rule\":\"coprime_to_all\"}",
            ExperimentOutcome.NOT_REFUTED),
        Arguments.of(
            "{\"initial_values\":[0,2],\"length\":4,\"candidate_min\":0,"
                + "\"candidate_max\":9,\"strictly_increasing\":false,"
                + "\"rule\":\"coprime_to_all\",\"claimed_values\":[0,2,1,3]}",
            ExperimentOutcome.NOT_REFUTED),
        Arguments.of(
            "{\"initial_values\":[1,2],\"length\":4,\"candidate_max\":10,"
                + "\"rule\":\"avoid_three_term_arithmetic_progression\"}",
            ExperimentOutcome.NOT_REFUTED),
        Arguments.of(
            "{\"initial_values\":[6],\"length\":3,\"candidate_min\":2,"
                + "\"candidate_max\":12,\"rule\":\"gcd_overlap_all_prior\"}",
            ExperimentOutcome.NOT_REFUTED),
        Arguments.of(
            "{\"initial_values\":[0],\"length\":3,\"candidate_max\":8,"
                + "\"rule\":\"avoid_forbidden_differences\","
                + "\"forbidden_differences\":[-1],\"claimed_values\":[0,2,4]}",
            ExperimentOutcome.NOT_REFUTED),
        Arguments.of(
            "{\"initial_values\":[0],\"length\":3,\"candidate_max\":8,"
                + "\"rule\":\"avoid_forbidden_differences\","
                + "\"forbidden_differences\":[1],\"claimed_values\":[0,2]}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"initial_values\":[0],\"length\":3,\"candidate_max\":8,"
                + "\"rule\":\"avoid_forbidden_differences\","
                + "\"forbidden_differences\":[1],\"claimed_values\":[0,3,4]}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND));
  }

  private static Stream<Arguments> invalidGreedy() {
    return Stream.of(
        Arguments.of("{\"initial_values\":[]}", 10),
        Arguments.of("{\"initial_values\":[1,2],\"length\":1}", 10),
        Arguments.of(
            "{\"initial_values\":[1],\"length\":2,\"candidate_min\":3,"
                + "\"candidate_max\":2}",
            10),
        Arguments.of(
            "{\"initial_values\":[1],\"length\":2,"
                + "\"rule\":\"avoid_forbidden_differences\","
                + "\"forbidden_differences\":[]}",
            10),
        Arguments.of(
            "{\"initial_values\":[1],\"length\":2,\"rule\":\"unknown\"}", 10),
        Arguments.of(
            "{\"initial_values\":[0],\"length\":3,\"candidate_min\":0,"
                + "\"candidate_max\":10,\"rule\":\"avoid_forbidden_differences\","
                + "\"forbidden_differences\":[1]}",
            1),
        Arguments.of(
            "{\"initial_values\":[0],\"length\":2,\"candidate_min\":1,"
                + "\"candidate_max\":1,\"rule\":\"avoid_forbidden_differences\","
                + "\"forbidden_differences\":[1]}",
            10));
  }

  private static Stream<Arguments> periodSuccesses() {
    return Stream.of(
        Arguments.of(
            "{\"values\":[1,2,1,2],\"candidate_period\":2}",
            ExperimentOutcome.NOT_REFUTED),
        Arguments.of(
            "{\"values\":[9,1,2,1,3],\"candidate_period\":2,\"start_index\":1}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND));
  }

  private static Stream<Arguments> invalidPeriods() {
    return Stream.of(
        Arguments.of("{\"values\":[],\"candidate_period\":1}", 10),
        Arguments.of("{\"values\":[1,2],\"candidate_period\":2}", 10),
        Arguments.of(
            "{\"values\":[1,2,1,2,1],\"candidate_period\":1}", 2));
  }

  private static Stream<Arguments> modularSuccesses() {
    return Stream.of(
        Arguments.of(
            "{\"modulus\":3,\"lhs\":\"x\",\"variables\":[]}",
            "{}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"modulus\":3,\"lhs\":\"x\",\"rhs\":\"x\",\"relation\":\"eq\","
                + "\"finite_reduction\":true,"
                + "\"reduction_justification\":\"all residue classes\"}",
            "{}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"modulus\":5,\"lhs\":\"x\",\"rhs\":\"x+1\",\"relation\":\"ne\","
                + "\"variables\":[\"x\"]}",
            "{\"x\":[-1,0,1,1]}",
            ExperimentOutcome.NOT_REFUTED),
        Arguments.of(
            "{\"modulus\":5,\"lhs\":\"x\",\"rhs\":\"x\",\"relation\":\"eq\","
                + "\"variables\":[\"x\"],\"finite_reduction\":true,"
                + "\"reduction_justification\":\" \"}",
            "{\"x\":{\"values\":[0,1]}}",
            ExperimentOutcome.NOT_REFUTED),
        Arguments.of(
            "{\"modulus\":5,\"lhs\":\"x\",\"rhs\":\"x\",\"variables\":[\"x\"]}",
            "{\"x\":{\"min\":-2,\"max\":8}}",
            ExperimentOutcome.NOT_REFUTED),
        Arguments.of(
            "{\"modulus\":5,\"lhs\":\"x\",\"rhs\":\"x\",\"variables\":[\"x\"]}",
            "{\"x\":{\"min\":1,\"max\":2}}",
            ExperimentOutcome.NOT_REFUTED),
        Arguments.of(
            "{\"modulus\":2,\"lhs\":\"x\",\"rhs\":\"x\",\"variables\":[\"x\"]}",
            "{\"x\":null}",
            ExperimentOutcome.NOT_REFUTED));
  }

  private static Stream<Arguments> invalidModular() {
    return Stream.of(
        Arguments.of("{\"modulus\":2,\"lhs\":\"1\"}", "{}", 10),
        Arguments.of(
            "{\"modulus\":2,\"lhs\":\"x\",\"variables\":[\"x\",\"x\"]}",
            "{}",
            10),
        Arguments.of(
            "{\"modulus\":2,\"lhs\":\"x+y\",\"variables\":[\"x\"]}",
            "{}",
            10),
        Arguments.of(
            "{\"modulus\":2,\"lhs\":\"x\",\"relation\":\"lt\",\"variables\":[\"x\"]}",
            "{}",
            10),
        Arguments.of(
            "{\"modulus\":3,\"lhs\":\"x\",\"variables\":[\"x\"]}",
            "{\"x\":1}",
            10),
        Arguments.of(
            "{\"modulus\":3,\"lhs\":\"x\",\"variables\":[\"x\"]}",
            "{\"x\":[]}",
            10),
        Arguments.of(
            "{\"modulus\":3,\"lhs\":\"x\",\"variables\":[\"x\"]}",
            "{\"x\":{\"values\":[]}}",
            10),
        Arguments.of(
            "{\"modulus\":3,\"lhs\":\"x\",\"variables\":[\"x\"]}",
            "{\"x\":{\"min\":2,\"max\":1}}",
            10),
        Arguments.of(
            "{\"modulus\":3,\"lhs\":\"x+y\",\"variables\":[\"x\",\"y\"]}",
            "{}",
            3));
  }
}
