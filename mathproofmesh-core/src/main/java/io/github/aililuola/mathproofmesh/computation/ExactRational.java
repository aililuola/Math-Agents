package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Objects;

/** Canonical arbitrary-precision rational used by exact computation handlers. */
public record ExactRational(BigInteger numerator, BigInteger denominator)
    implements Comparable<ExactRational> {

  public static final ExactRational ZERO = new ExactRational(BigInteger.ZERO, BigInteger.ONE);
  public static final ExactRational ONE = new ExactRational(BigInteger.ONE, BigInteger.ONE);

  public ExactRational {
    Objects.requireNonNull(numerator, "numerator");
    Objects.requireNonNull(denominator, "denominator");
    if (denominator.signum() == 0) {
      throw new ArithmeticException("rational denominator cannot be zero");
    }
    if (denominator.signum() < 0) {
      numerator = numerator.negate();
      denominator = denominator.negate();
    }
    BigInteger gcd = numerator.gcd(denominator);
    numerator = numerator.divide(gcd);
    denominator = denominator.divide(gcd);
  }

  public ExactRational(BigInteger integer) {
    this(integer, BigInteger.ONE);
  }

  public static ExactRational parse(JsonNode node, String label) {
    if (node == null || node.isNull() || node.isBoolean() || !node.isValueNode()) {
      throw new IllegalArgumentException(label + " must be an exact rational number");
    }
    return parse(node.asText(), label);
  }

  public static ExactRational parse(String raw, String label) {
    if (raw == null || raw.isBlank()) {
      throw new IllegalArgumentException(label + " must be an exact rational number");
    }
    String value = raw.trim();
    int slash = value.indexOf('/');
    try {
      if (slash >= 0) {
        if (slash != value.lastIndexOf('/')) {
          throw new NumberFormatException("multiple fraction separators");
        }
        return new ExactRational(
            new BigInteger(value.substring(0, slash).trim()),
            new BigInteger(value.substring(slash + 1).trim()));
      }
      BigDecimal decimal = new BigDecimal(value);
      BigInteger numerator = decimal.unscaledValue();
      int scale = decimal.scale();
      if (scale < 0) {
        return new ExactRational(numerator.multiply(BigInteger.TEN.pow(-scale)));
      }
      return new ExactRational(numerator, BigInteger.TEN.pow(scale));
    } catch (NumberFormatException | ArithmeticException exception) {
      throw new IllegalArgumentException(label + " must be an exact rational number", exception);
    }
  }

  public BigInteger toBigIntegerExact(String label) {
    if (!denominator.equals(BigInteger.ONE)) {
      throw new IllegalArgumentException(label + " must be an integer");
    }
    return numerator;
  }

  public ExactRational add(ExactRational other) {
    return new ExactRational(
        numerator.multiply(other.denominator).add(other.numerator.multiply(denominator)),
        denominator.multiply(other.denominator));
  }

  public ExactRational subtract(ExactRational other) {
    return add(other.negate());
  }

  public ExactRational multiply(ExactRational other) {
    return new ExactRational(
        numerator.multiply(other.numerator), denominator.multiply(other.denominator));
  }

  public ExactRational divide(ExactRational other) {
    if (other.numerator.signum() == 0) {
      throw new ArithmeticException("division by zero");
    }
    return new ExactRational(
        numerator.multiply(other.denominator), denominator.multiply(other.numerator));
  }

  public ExactRational negate() {
    return new ExactRational(numerator.negate(), denominator);
  }

  public ExactRational abs() {
    return numerator.signum() < 0 ? negate() : this;
  }

  public ExactRational pow(int exponent) {
    if (exponent < 0) {
      if (numerator.signum() == 0) {
        throw new ArithmeticException("zero cannot be raised to a negative power");
      }
      return new ExactRational(denominator.pow(-exponent), numerator.pow(-exponent));
    }
    return new ExactRational(numerator.pow(exponent), denominator.pow(exponent));
  }

  public int signum() {
    return numerator.signum();
  }

  @Override
  public int compareTo(ExactRational other) {
    return numerator.multiply(other.denominator).compareTo(other.numerator.multiply(denominator));
  }

  @Override
  public String toString() {
    return denominator.equals(BigInteger.ONE)
        ? numerator.toString()
        : numerator + "/" + denominator;
  }
}
