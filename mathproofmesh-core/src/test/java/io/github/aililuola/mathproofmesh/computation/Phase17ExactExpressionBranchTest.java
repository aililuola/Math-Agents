package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigInteger;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

class Phase17ExactExpressionBranchTest {

  @ParameterizedTest
  @MethodSource("integerExpressions")
  void exactGrammarEvaluatesEveryArithmeticOperator(String source, long expected) {
    ExactExpression expression = ExactExpression.parse(source, 8);

    assertThat(expression.evaluateInteger(Map.of())).isEqualTo(BigInteger.valueOf(expected));
  }

  @Test
  void variablesDecimalsWhitespaceAndAssignmentsRemainExact() {
    ExactExpression variables = ExactExpression.parse(" \t(x_1 + y2) * 2 ");
    assertThat(variables.variables()).containsExactlyInAnyOrder("x_1", "y2");
    assertThat(
            variables.evaluateInteger(
                Map.of("x_1", BigInteger.valueOf(2), "y2", BigInteger.valueOf(3))))
        .isEqualTo(BigInteger.TEN);
    assertThat(ExactExpression.parse(".5 + 0.5").evaluate(Map.of()).toBigIntegerExact("sum"))
        .isEqualTo(BigInteger.ONE);
    assertThatThrownBy(
            () -> variables.evaluate(Map.of("x_1", new ExactRational(BigInteger.ONE))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("undeclared expression variables");
  }

  @ParameterizedTest
  @ValueSource(
      strings = {
        "1 2",
        "1//x",
        "1//-2",
        "1//0",
        "1//(1/2)",
        "2^x",
        "2^(1/2)",
        "2^-1",
        "_private",
        "x__dunder",
        "(1+2",
        ")",
        ".",
        "1..2",
        "@",
        "open(1)",
        "1***2"
      })
  void forbiddenOrMalformedGrammarFailsClosed(String source) {
    assertThatThrownBy(() -> ExactExpression.parse(source, 3))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorAndRuntimeArithmeticGuardsFailClosed() {
    assertThatThrownBy(() -> ExactExpression.parse(null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExactExpression.parse(" \t"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExactExpression.parse("1", -1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExactExpression.parse("2^4", 3))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> ExactExpression.parse("1/0").evaluate(Map.of()))
        .isInstanceOf(ArithmeticException.class);
  }

  private static Stream<Arguments> integerExpressions() {
    return Stream.of(
        Arguments.of("+2", 2),
        Arguments.of("-2", -2),
        Arguments.of("1+2-3", 0),
        Arguments.of("2*3/2", 3),
        Arguments.of("-3//2", -2),
        Arguments.of("3//2", 1),
        Arguments.of("-3%2", 1),
        Arguments.of("3%2", 1),
        Arguments.of("2^3", 8),
        Arguments.of("2**3", 8),
        Arguments.of("(1+2)*3", 9));
  }
}
