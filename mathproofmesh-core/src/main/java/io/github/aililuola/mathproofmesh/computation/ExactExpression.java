package io.github.aililuola.mathproofmesh.computation;

import java.math.BigInteger;
import java.util.Collections;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Small expression parser for exact handlers.
 *
 * <p>It intentionally has no function calls, attributes, indexing, strings, imports, or dynamic
 * evaluation.
 */
public final class ExactExpression {
  private final Node root;
  private final Set<String> variables;

  private ExactExpression(Node root, Set<String> variables) {
    this.root = root;
    this.variables = Collections.unmodifiableSet(new HashSet<>(variables));
  }

  public static ExactExpression parse(String expression) {
    return parse(expression, 1_000);
  }

  public static ExactExpression parse(String expression, int maximumExponent) {
    Parser parser = new Parser(expression, maximumExponent);
    Node root = parser.parse();
    return new ExactExpression(root, parser.variables);
  }

  public Set<String> variables() {
    return variables;
  }

  public ExactRational evaluate(Map<String, ExactRational> assignment) {
    if (!assignment.keySet().containsAll(variables)) {
      Set<String> missing = new HashSet<>(variables);
      missing.removeAll(assignment.keySet());
      throw new IllegalArgumentException("undeclared expression variables: " + missing);
    }
    return root.evaluate(assignment);
  }

  public BigInteger evaluateInteger(Map<String, BigInteger> assignment) {
    Map<String, ExactRational> rationalAssignment =
        assignment.entrySet().stream()
            .collect(
                java.util.stream.Collectors.toUnmodifiableMap(
                    Map.Entry::getKey, entry -> new ExactRational(entry.getValue())));
    return evaluate(rationalAssignment).toBigIntegerExact("expression result");
  }

  private sealed interface Node permits Literal, Variable, Unary, Binary {
    ExactRational evaluate(Map<String, ExactRational> assignment);
  }

  private record Literal(ExactRational value) implements Node {
    @Override
    public ExactRational evaluate(Map<String, ExactRational> assignment) {
      return value;
    }
  }

  private record Variable(String name) implements Node {
    @Override
    public ExactRational evaluate(Map<String, ExactRational> assignment) {
      ExactRational value = assignment.get(name);
      if (value == null) {
        throw new IllegalArgumentException("missing expression variable: " + name);
      }
      return value;
    }
  }

  private record Unary(char operator, Node operand) implements Node {
    @Override
    public ExactRational evaluate(Map<String, ExactRational> assignment) {
      ExactRational value = operand.evaluate(assignment);
      return operator == '-' ? value.negate() : value;
    }
  }

  private record Binary(String operator, Node left, Node right) implements Node {
    @Override
    public ExactRational evaluate(Map<String, ExactRational> assignment) {
      ExactRational leftValue = left.evaluate(assignment);
      ExactRational rightValue = right.evaluate(assignment);
      return switch (operator) {
        case "+" -> leftValue.add(rightValue);
        case "-" -> leftValue.subtract(rightValue);
        case "*" -> leftValue.multiply(rightValue);
        case "/" -> leftValue.divide(rightValue);
        case "//" ->
            new ExactRational(
                floorDivide(
                    leftValue.toBigIntegerExact("floor-division dividend"),
                    rightValue.toBigIntegerExact("floor-division divisor")));
        case "%" -> {
          BigInteger dividend = leftValue.toBigIntegerExact("modulo dividend");
          BigInteger divisor = rightValue.toBigIntegerExact("modulo divisor");
          BigInteger quotient = floorDivide(dividend, divisor);
          yield new ExactRational(dividend.subtract(quotient.multiply(divisor)));
        }
        case "^" -> {
          BigInteger exponent = rightValue.toBigIntegerExact("exponent");
          yield leftValue.pow(exponent.intValueExact());
        }
        default -> throw new IllegalStateException("unknown exact operator: " + operator);
      };
    }
  }

  private static BigInteger floorDivide(BigInteger dividend, BigInteger divisor) {
    if (divisor.signum() == 0) {
      throw new ArithmeticException("division by zero");
    }
    BigInteger[] quotientAndRemainder = dividend.divideAndRemainder(divisor);
    BigInteger quotient = quotientAndRemainder[0];
    if (quotientAndRemainder[1].signum() != 0
        && dividend.signum() != divisor.signum()) {
      quotient = quotient.subtract(BigInteger.ONE);
    }
    return quotient;
  }

  private static final class Parser {
    private final String source;
    private final int maximumExponent;
    private final Set<String> variables = new HashSet<>();
    private int offset;
    private Token token;

    private Parser(String source, int maximumExponent) {
      if (source == null || source.isBlank()) {
        throw new IllegalArgumentException("expression must be non-empty");
      }
      if (maximumExponent < 0) {
        throw new IllegalArgumentException("maximumExponent must be nonnegative");
      }
      this.source = source;
      this.maximumExponent = maximumExponent;
      this.token = nextToken();
    }

    private Node parse() {
      Node result = additive();
      if (token.kind != Kind.END) {
        throw error("unexpected token " + token.text);
      }
      return result;
    }

    private Node additive() {
      Node result = multiplicative();
      while (token.text.equals("+") || token.text.equals("-")) {
        String operator = consume().text;
        result = new Binary(operator, result, multiplicative());
      }
      return result;
    }

    private Node multiplicative() {
      Node result = unary();
      while (token.text.equals("*")
          || token.text.equals("/")
          || token.text.equals("//")
          || token.text.equals("%")) {
        String operator = consume().text;
        Node right = unary();
        if ((operator.equals("//") || operator.equals("%"))
            && (!(right instanceof Literal literal)
                || !literal.value.denominator().equals(BigInteger.ONE)
                || literal.value.numerator().signum() <= 0)) {
          throw error("integer division and modulo require a positive constant divisor");
        }
        result = new Binary(operator, result, right);
      }
      return result;
    }

    private Node unary() {
      if (token.text.equals("+") || token.text.equals("-")) {
        char operator = consume().text.charAt(0);
        return new Unary(operator, unary());
      }
      return power();
    }

    private Node power() {
      Node result = primary();
      if (token.text.equals("^") || token.text.equals("**")) {
        consume();
        Node exponent = unary();
        if (!(exponent instanceof Literal literal)
            || !literal.value.denominator().equals(BigInteger.ONE)
            || literal.value.numerator().signum() < 0
            || literal.value.numerator().compareTo(BigInteger.valueOf(maximumExponent)) > 0) {
          throw error(
              "powers require a nonnegative constant exponent no greater than "
                  + maximumExponent);
        }
        result = new Binary("^", result, exponent);
      }
      return result;
    }

    private Node primary() {
      if (token.kind == Kind.NUMBER) {
        return new Literal(ExactRational.parse(consume().text, "numeric literal"));
      }
      if (token.kind == Kind.IDENTIFIER) {
        String name = consume().text;
        if (name.startsWith("_") || name.contains("__")) {
          throw error("private/dunder names are forbidden");
        }
        variables.add(name);
        return new Variable(name);
      }
      if (token.text.equals("(")) {
        consume();
        Node result = additive();
        require(")");
        return result;
      }
      throw error("expected a number, variable, or parenthesized expression");
    }

    private void require(String text) {
      if (!token.text.equals(text)) {
        throw error("expected " + text);
      }
      consume();
    }

    private Token consume() {
      Token current = token;
      token = nextToken();
      return current;
    }

    private Token nextToken() {
      while (offset < source.length() && Character.isWhitespace(source.charAt(offset))) {
        offset++;
      }
      if (offset >= source.length()) {
        return new Token(Kind.END, "");
      }
      char current = source.charAt(offset);
      if (Character.isDigit(current) || current == '.') {
        int start = offset++;
        boolean dotSeen = current == '.';
        while (offset < source.length()) {
          char candidate = source.charAt(offset);
          if (Character.isDigit(candidate)) {
            offset++;
          } else if (candidate == '.' && !dotSeen) {
            dotSeen = true;
            offset++;
          } else {
            break;
          }
        }
        String value = source.substring(start, offset);
        if (value.equals(".")) {
          throw error("invalid numeric literal");
        }
        return new Token(Kind.NUMBER, value);
      }
      if (Character.isLetter(current) || current == '_') {
        int start = offset++;
        while (offset < source.length()) {
          char candidate = source.charAt(offset);
          if (Character.isLetterOrDigit(candidate) || candidate == '_') {
            offset++;
          } else {
            break;
          }
        }
        return new Token(Kind.IDENTIFIER, source.substring(start, offset));
      }
      if (offset + 1 < source.length()) {
        String pair = source.substring(offset, offset + 2);
        if (pair.equals("//") || pair.equals("**")) {
          offset += 2;
          return new Token(Kind.OPERATOR, pair);
        }
      }
      if ("+-*/%^()".indexOf(current) >= 0) {
        offset++;
        return new Token(Kind.OPERATOR, Character.toString(current));
      }
      throw error("unsupported expression character: " + current);
    }

    private IllegalArgumentException error(String message) {
      return new IllegalArgumentException(
          message + " at offset " + Math.min(offset, source.length()));
    }
  }

  private enum Kind {
    NUMBER,
    IDENTIFIER,
    OPERATOR,
    END
  }

  private record Token(Kind kind, String text) {}
}
