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

class Phase17NumberTheoryBranchTest {

  @ParameterizedTest
  @MethodSource("outcomes")
  void finiteNumberTheoryBoundaryCasesAreReplayed(
      String arguments, ExperimentOutcome expected) {
    HandlerEvidence first =
        NumberTheoryFunctions.run(
            ComputationFixtures.spec(
                ComputationMethod.NUMBER_THEORY_CHECK, arguments));
    HandlerEvidence replay =
        NumberTheoryFunctions.run(
            ComputationFixtures.spec(
                ComputationMethod.NUMBER_THEORY_CHECK, arguments));

    assertThat(first.outcome()).isEqualTo(expected);
    assertThat(first.outcome()).isEqualTo(replay.outcome());
    assertThat(first.certificate()).isEqualTo(replay.certificate());
    assertThat(first.counterexample()).isEqualTo(replay.counterexample());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "{\"operation\":\"unknown\"}",
        "{\"operation\":\"multiplicative_order\",\"a\":1,\"n\":1}",
        "{\"operation\":\"multiplicative_order\",\"a\":2,\"n\":4}",
        "{\"operation\":\"crt\",\"residues\":[],\"moduli\":[2]}",
        "{\"operation\":\"crt\",\"residues\":[1],\"moduli\":[]}",
        "{\"operation\":\"crt\",\"residues\":[1],\"moduli\":[2,3]}",
        "{\"operation\":\"crt\",\"residues\":[1],\"moduli\":[-2]}",
        "{\"operation\":\"p_adic_valuation\",\"p\":1,\"expression\":\"1\"}",
        "{\"operation\":\"p_adic_valuation\",\"p\":9,\"expression\":\"1\"}",
        "{\"operation\":\"p_adic_valuation\",\"p\":2,\"expression\":\"x\"}",
        "{\"operation\":\"p_adic_valuation\",\"p\":2,\"expression\":\"x\","
            + "\"assignment\":{\"y\":1}}",
        "{\"operation\":\"primitive_root\",\"n\":1}",
        "{\"operation\":\"is_prime\",\"n\":7,\"claimed\":1}",
        "{\"operation\":\"factorization\",\"n\":1}",
        "{\"operation\":\"factorization\",\"n\":12,\"claimed\":[]}",
        "{\"operation\":\"factorization\",\"n\":12,\"claimed\":{\"x\":1}}",
        "{\"operation\":\"factorization\",\"n\":12,\"claimed\":{\"2\":1.5}}"
      })
  void malformedAndOutOfDomainRequestsFailClosed(String arguments) {
    assertThatThrownBy(
            () ->
                NumberTheoryFunctions.run(
                    ComputationFixtures.spec(
                        ComputationMethod.NUMBER_THEORY_CHECK, arguments)))
        .isInstanceOfAny(IllegalArgumentException.class, ArithmeticException.class);
  }

  private static Stream<Arguments> outcomes() {
    return Stream.of(
        Arguments.of(
            "{\"operation\":\"multiplicative_order\",\"a\":3,\"n\":7}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"multiplicative_order\",\"a\":-1,\"n\":7,\"claimed\":2}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"crt\",\"residues\":[1,3],\"moduli\":[2,4]}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"crt\",\"residues\":[0,1],\"moduli\":[2,4]}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"crt\",\"residues\":[2,3],\"moduli\":[3,5],\"claimed\":8}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"p_adic_valuation\",\"p\":3,\"expression\":\"2\"}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"p_adic_valuation\",\"p\":3,\"expression\":\"-27\","
                + "\"claimed\":3}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"p_adic_valuation\",\"p\":3,\"expression\":\"x+1\","
                + "\"assignment\":{\"x\":2}}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":2}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":4,\"claimed\":3}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":6}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":8,\"claimed\":false}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":9,\"claimed\":2}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"primitive_root\",\"n\":10,\"claimed\":2}",
            ExperimentOutcome.COUNTEREXAMPLE_FOUND),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":-1}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":0}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":3}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":5}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":7}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":11}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":13}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":17}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":19}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":23}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":29}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":31}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":37}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":41,\"claimed\":true}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":49,\"claimed\":false}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"is_prime\",\"n\":2047}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"factorization\",\"n\":2}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"factorization\",\"n\":4}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"factorization\",\"n\":6}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"factorization\",\"n\":49}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"factorization\",\"n\":72,"
                + "\"claimed\":{\"2\":3,\"3\":2}}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"factorization\",\"n\":9999999967}",
            ExperimentOutcome.CERTIFIED),
        Arguments.of(
            "{\"operation\":\"factorization\",\"n\":1000000000000}",
            ExperimentOutcome.INCONCLUSIVE));
  }
}
