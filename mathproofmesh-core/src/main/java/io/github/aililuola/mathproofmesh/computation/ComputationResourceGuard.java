package io.github.aililuola.mathproofmesh.computation;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/** Enforces capability-owned limits before and during native execution and verification. */
final class ComputationResourceGuard {
  private ComputationResourceGuard() {}

  static void validateRequest(
      ExperimentSpec spec, ComputationResourceEnvelope envelope) {
    String serialized = ContractObjectMapper.write(spec);
    requireChars(serialized.length(), envelope.maxOutputChars(), "COMPUTATION_INPUT_TOO_LARGE");
    requireMemory(serialized, envelope.maxMemoryBytes());
    int nodes = nodeCount(spec.arguments()) + nodeCount(spec.domains());
    if (nodes > envelope.maxCertificateNodes()) {
      throw new IllegalArgumentException("COMPUTATION_INPUT_NODE_LIMIT");
    }
    validateRationalBitLengths(spec.arguments(), envelope.maxRationalBitLength());
    validateRationalBitLengths(spec.domains(), envelope.maxRationalBitLength());
    switch (spec.method()) {
      case EXACT_LINEAR_ALGEBRA -> validateMatrix(spec.arguments().path("matrix"), envelope);
      case FINITE_SET_MAP_CHECK -> validateFiniteSetMap(spec.arguments(), envelope);
      case HYPERGRAPH_TRANSVERSAL -> validateHypergraph(spec.arguments(), envelope);
      default -> {
        // The generic node, bit-length, memory, and output guards still apply.
      }
    }
  }

  static void validateResult(
      ComputationResultArtifact result, ComputationResourceEnvelope envelope) {
    String serialized = ContractObjectMapper.write(result);
    requireChars(serialized.length(), envelope.maxOutputChars(), "COMPUTATION_OUTPUT_LIMIT");
    requireChars(serialized.length(), envelope.maxResultChars(), "COMPUTATION_RESULT_LIMIT");
    requireMemory(serialized, envelope.maxMemoryBytes());
    int nodes =
        nodeCount(result.scope())
            + nodeCount(result.counterexample())
            + nodeCount(result.certificate());
    if (nodes > envelope.maxCertificateNodes()) {
      throw new IllegalArgumentException("COMPUTATION_CERTIFICATE_NODE_LIMIT");
    }
  }

  static <T> T callWithin(Supplier<T> operation, ComputationResourceEnvelope envelope) {
    FutureTask<T> task =
        new FutureTask<>(
            () -> operation.get());
    Thread worker =
        Thread.ofVirtual().name("bounded-native-computation").unstarted(task);
    worker.start();
    long timeoutNanos =
        Math.max(1L, Duration.ofMillis(Math.max(1L, Math.round(envelope.maxCpuSeconds() * 1_000.0d))).toNanos());
    try {
      return task.get(timeoutNanos, TimeUnit.NANOSECONDS);
    } catch (TimeoutException exception) {
      task.cancel(true);
      throw new IllegalStateException("COMPUTATION_TIMEOUT", exception);
    } catch (InterruptedException exception) {
      task.cancel(true);
      Thread.currentThread().interrupt();
      throw new IllegalStateException("COMPUTATION_INTERRUPTED", exception);
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause();
      if (cause instanceof Error error) {
        throw error;
      }
      throw new IllegalStateException("COMPUTATION_EXECUTION_FAILED", cause);
    }
  }

  private static void validateMatrix(
      JsonNode matrix, ComputationResourceEnvelope envelope) {
    if (!matrix.isArray()) {
      return;
    }
    if (matrix.size() > envelope.maxMatrixRows()) {
      throw new IllegalArgumentException("COMPUTATION_MATRIX_ROW_LIMIT");
    }
    for (JsonNode row : matrix) {
      if (row.isArray() && row.size() > envelope.maxMatrixColumns()) {
        throw new IllegalArgumentException("COMPUTATION_MATRIX_COLUMN_LIMIT");
      }
    }
  }

  private static void validateFiniteSetMap(
      JsonNode arguments, ComputationResourceEnvelope envelope) {
    if (arguments.path("domain").size() > envelope.maxFiniteSetSize()
        || arguments.path("codomain").size() > envelope.maxFiniteSetSize()
        || arguments.path("mapping").size() > envelope.maxFiniteSetSize()) {
      throw new IllegalArgumentException("COMPUTATION_FINITE_SET_LIMIT");
    }
  }

  private static void validateHypergraph(
      JsonNode arguments, ComputationResourceEnvelope envelope) {
    if (arguments.path("vertices").size() > envelope.maxHypergraphVertices()) {
      throw new IllegalArgumentException("COMPUTATION_HYPERGRAPH_VERTEX_LIMIT");
    }
    if (arguments.path("edges").size() > envelope.maxFiniteSetSize()) {
      throw new IllegalArgumentException("COMPUTATION_HYPERGRAPH_EDGE_LIMIT");
    }
  }

  private static void validateRationalBitLengths(JsonNode root, int maximum) {
    if (root == null) {
      return;
    }
    Deque<JsonNode> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      JsonNode current = pending.removeFirst();
      if (current.isContainerNode()) {
        current.forEach(pending::addLast);
        continue;
      }
      BigInteger[] values = rationalParts(current, maximum);
      for (BigInteger value : values) {
        if (value.abs().bitLength() > maximum) {
          throw new IllegalArgumentException("COMPUTATION_RATIONAL_BIT_LENGTH_LIMIT");
        }
      }
    }
  }

  private static BigInteger[] rationalParts(JsonNode value, int maximum) {
    try {
      if (value.isIntegralNumber()) {
        return new BigInteger[] {value.bigIntegerValue()};
      }
      if (value.isFloatingPointNumber()) {
        BigDecimal decimal = value.decimalValue().stripTrailingZeros();
        validateDecimalBitLength(decimal, maximum);
        return new BigInteger[] {decimal.unscaledValue()};
      }
      if (!value.isTextual()) {
        return new BigInteger[0];
      }
      String text = value.textValue().strip();
      if (isSignedInteger(text)) {
        return new BigInteger[] {new BigInteger(text)};
      }
      int slash = text.indexOf('/');
      if (slash > 0
          && slash == text.lastIndexOf('/')
          && isSignedInteger(text.substring(0, slash).strip())
          && isSignedInteger(text.substring(slash + 1).strip())) {
        String[] parts = text.split("/", -1);
        return new BigInteger[] {
          new BigInteger(parts[0].strip()), new BigInteger(parts[1].strip())
        };
      }
      if (isDecimalLiteral(text)) {
        BigDecimal decimal = new BigDecimal(text).stripTrailingZeros();
        validateDecimalBitLength(decimal, maximum);
        return new BigInteger[] {decimal.unscaledValue()};
      }
      return new BigInteger[0];
    } catch (ArithmeticException | NumberFormatException exception) {
      throw new IllegalArgumentException("COMPUTATION_INVALID_EXACT_NUMBER", exception);
    }
  }

  private static boolean isSignedInteger(String value) {
    if (value.isEmpty()) {
      return false;
    }
    int index = value.charAt(0) == '+' || value.charAt(0) == '-' ? 1 : 0;
    if (index == value.length()) {
      return false;
    }
    while (index < value.length()) {
      if (!Character.isDigit(value.charAt(index++))) {
        return false;
      }
    }
    return true;
  }

  private static boolean isDecimalLiteral(String value) {
    if (value.isEmpty()) {
      return false;
    }
    int index = value.charAt(0) == '+' || value.charAt(0) == '-' ? 1 : 0;
    int digits = 0;
    while (index < value.length() && Character.isDigit(value.charAt(index))) {
      index++;
      digits++;
    }
    boolean decimalPoint = index < value.length() && value.charAt(index) == '.';
    if (decimalPoint) {
      index++;
      while (index < value.length() && Character.isDigit(value.charAt(index))) {
        index++;
        digits++;
      }
    }
    if (digits == 0 || (!decimalPoint && index == value.length())) {
      return false;
    }
    if (index < value.length() && (value.charAt(index) == 'e' || value.charAt(index) == 'E')) {
      index++;
      if (index < value.length() && (value.charAt(index) == '+' || value.charAt(index) == '-')) {
        index++;
      }
      int exponentStart = index;
      while (index < value.length() && Character.isDigit(value.charAt(index))) {
        index++;
      }
      if (index == exponentStart) {
        return false;
      }
    }
    return index == value.length();
  }

  private static void validateDecimalBitLength(BigDecimal decimal, int maximum) {
    long decimalPower = Math.abs((long) decimal.scale());
    long powerBitLength = (decimalPower * 3_322L + 999L) / 1_000L;
    long numeratorBits = decimal.unscaledValue().abs().bitLength();
    long effectiveBits = decimal.scale() < 0 ? numeratorBits + powerBitLength : powerBitLength;
    if (numeratorBits > maximum || effectiveBits > maximum) {
      throw new IllegalArgumentException("COMPUTATION_RATIONAL_BIT_LENGTH_LIMIT");
    }
  }

  private static int nodeCount(JsonNode root) {
    if (root == null) {
      return 0;
    }
    int count = 0;
    Deque<JsonNode> pending = new ArrayDeque<>();
    pending.add(root);
    while (!pending.isEmpty()) {
      JsonNode current = pending.removeFirst();
      count++;
      if (current.isContainerNode()) {
        current.forEach(pending::addLast);
      }
    }
    return count;
  }

  private static void requireChars(int actual, int maximum, String code) {
    if (actual > maximum) {
      throw new IllegalArgumentException(code + ": " + actual + " > " + maximum);
    }
  }

  private static void requireMemory(String serialized, long maximumBytes) {
    long bytes = serialized.getBytes(StandardCharsets.UTF_8).length;
    if (bytes > maximumBytes) {
      throw new IllegalArgumentException("COMPUTATION_MEMORY_ENVELOPE_EXCEEDED");
    }
  }
}
