package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComputationResourceGuardBoundaryTest {
  @Test
  void everyEnvelopeDimensionIsValidatedIndependently() {
    List<ComputationResourceEnvelope> validVariants =
        List.of(
            envelope(1, 1.0d, 1_000_000L, 10_000, 8, 8, 256, 8, 8, 1_000, 10_000),
            new ComputationResourceEnvelope(1, 1.0d, 1_000_000L, 10_000));
    assertThat(validVariants).hasSize(2);

    assertInvalidEnvelope(0, 1.0d, 1_000_000L, 10_000, 8, 8, 256, 8, 8, 1_000, 10_000);
    assertInvalidEnvelope(1, Double.NaN, 1_000_000L, 10_000, 8, 8, 256, 8, 8, 1_000, 10_000);
    assertInvalidEnvelope(1, 0.0d, 1_000_000L, 10_000, 8, 8, 256, 8, 8, 1_000, 10_000);
    assertInvalidEnvelope(1, 1.0d, 0L, 10_000, 8, 8, 256, 8, 8, 1_000, 10_000);
    assertInvalidEnvelope(1, 1.0d, 1_000_000L, 255, 8, 8, 256, 8, 8, 1_000, 255);
    assertInvalidEnvelope(1, 1.0d, 1_000_000L, 10_000, -1, 8, 256, 8, 8, 1_000, 10_000);
    assertInvalidEnvelope(1, 1.0d, 1_000_000L, 10_000, 8, -1, 256, 8, 8, 1_000, 10_000);
    assertInvalidEnvelope(1, 1.0d, 1_000_000L, 10_000, 8, 8, -1, 8, 8, 1_000, 10_000);
    assertInvalidEnvelope(1, 1.0d, 1_000_000L, 10_000, 8, 8, 256, -1, 8, 1_000, 10_000);
    assertInvalidEnvelope(1, 1.0d, 1_000_000L, 10_000, 8, 8, 256, 8, -1, 1_000, 10_000);
    assertInvalidEnvelope(1, 1.0d, 1_000_000L, 10_000, 8, 8, 256, 8, 8, -1, 10_000);
    assertInvalidEnvelope(1, 1.0d, 1_000_000L, 10_000, 8, 8, 256, 8, 8, 1_000, 255);
    assertInvalidEnvelope(1, 1.0d, 1_000_000L, 10_000, 8, 8, 256, 8, 8, 1_000, 10_001);
  }

  @Test
  void methodSpecificAndGenericRequestLimitsFailClosed() {
    assertRequestRejected(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"matrix\":[[1],[2]],\"operation\":\"rank\"}",
        envelope(100, 1.0d, 1_000_000L, 10_000, 1, 8, 256, 8, 8, 1_000, 10_000),
        "COMPUTATION_MATRIX_ROW_LIMIT");
    assertRequestRejected(
        ComputationMethod.EXACT_LINEAR_ALGEBRA,
        "{\"matrix\":[[1,2]],\"operation\":\"rank\"}",
        envelope(100, 1.0d, 1_000_000L, 10_000, 8, 1, 256, 8, 8, 1_000, 10_000),
        "COMPUTATION_MATRIX_COLUMN_LIMIT");
    assertRequestRejected(
        ComputationMethod.FINITE_SET_MAP_CHECK,
        "{\"domain\":[1],\"codomain\":[1,2],\"mapping\":{\"1\":1}}",
        envelope(100, 1.0d, 1_000_000L, 10_000, 8, 8, 256, 1, 8, 1_000, 10_000),
        "COMPUTATION_FINITE_SET_LIMIT");
    assertRequestRejected(
        ComputationMethod.FINITE_SET_MAP_CHECK,
        "{\"domain\":[1],\"codomain\":[1],\"mapping\":{\"1\":1,\"2\":1}}",
        envelope(100, 1.0d, 1_000_000L, 10_000, 8, 8, 256, 1, 8, 1_000, 10_000),
        "COMPUTATION_FINITE_SET_LIMIT");
    assertRequestRejected(
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        "{\"vertices\":[1,2],\"edges\":[[1]]}",
        envelope(100, 1.0d, 1_000_000L, 10_000, 8, 8, 256, 8, 1, 1_000, 10_000),
        "COMPUTATION_HYPERGRAPH_VERTEX_LIMIT");
    assertRequestRejected(
        ComputationMethod.HYPERGRAPH_TRANSVERSAL,
        "{\"vertices\":[1],\"edges\":[[1],[1]]}",
        envelope(100, 1.0d, 1_000_000L, 10_000, 8, 8, 256, 1, 8, 1_000, 10_000),
        "COMPUTATION_HYPERGRAPH_EDGE_LIMIT");

    var ordinary =
        ComputationFixtures.spec(
            ComputationMethod.NUMBER_THEORY_CHECK,
            "{\"operation\":\"is_prime\",\"n\":37}");
    assertThatThrownBy(
            () ->
                ComputationResourceGuard.validateRequest(
                    ordinary,
                    envelope(100, 1.0d, 1L, 10_000, 8, 8, 256, 8, 8, 1_000, 10_000)))
        .hasMessageContaining("COMPUTATION_MEMORY_ENVELOPE_EXCEEDED");
    assertThatThrownBy(
            () ->
                ComputationResourceGuard.validateRequest(
                    ordinary,
                    envelope(100, 1.0d, 1_000_000L, 10_000, 8, 8, 256, 8, 8, 1, 10_000)))
        .hasMessageContaining("COMPUTATION_INPUT_NODE_LIMIT");
  }

  @Test
  void exactNumberScannerCoversSignedFractionalDecimalAndNonNumericLeaves() {
    var spec =
        ComputationFixtures.spec(
            ComputationMethod.NUMBER_THEORY_CHECK,
            "{\"operation\":\"is_prime\",\"n\":37,\"scanner_values\":["
                + "\"\",\"+\",\"-\",\"+12\",\"-12\",\"1/2\",\"1//2\","
                + "\"1/\",\"/2\",\".5\",\"1.\",\"1e2\",\"1E+2\","
                + "\"1e-2\",\"1e\",\"abc\",true,1,1.5]}");

    assertThatCode(
            () ->
                ComputationResourceGuard.validateRequest(
                    spec,
                    envelope(
                        100,
                        1.0d,
                        1_000_000L,
                        10_000,
                        8,
                        8,
                        4_096,
                        32,
                        8,
                        1_000,
                        10_000)))
        .doesNotThrowAnyException();
  }

  @Test
  void resultMemoryNodeAndResultSpecificLimitsAreIndependent() {
    ComputationResultArtifact result = result("x".repeat(300));
    assertThatThrownBy(
            () ->
                ComputationResourceGuard.validateResult(
                    result,
                    envelope(100, 1.0d, 100_000L, 10_000, 8, 8, 256, 8, 8, 1_000, 256)))
        .hasMessageContaining("COMPUTATION_RESULT_LIMIT");
    assertThatThrownBy(
            () ->
                ComputationResourceGuard.validateResult(
                    result,
                    envelope(100, 1.0d, 1L, 10_000, 8, 8, 256, 8, 8, 1_000, 10_000)))
        .hasMessageContaining("COMPUTATION_MEMORY_ENVELOPE_EXCEEDED");
    assertThatThrownBy(
            () ->
                ComputationResourceGuard.validateResult(
                    result,
                    envelope(100, 1.0d, 100_000L, 10_000, 8, 8, 256, 8, 8, 1, 10_000)))
        .hasMessageContaining("COMPUTATION_CERTIFICATE_NODE_LIMIT");
  }

  @Test
  void executionWrapperPreservesErrorsAndContainsRuntimeFailuresAndInterrupts() {
    var envelope = new ComputationResourceEnvelope(100, 1.0d, 1_000_000L, 1_000);
    AssertionError fatal = new AssertionError("simulated process failure");
    assertThatThrownBy(() -> ComputationResourceGuard.callWithin(() -> { throw fatal; }, envelope))
        .isSameAs(fatal);
    assertThatThrownBy(
            () ->
                ComputationResourceGuard.callWithin(
                    () -> {
                      throw new IllegalArgumentException("producer failed");
                    },
                    envelope))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("COMPUTATION_EXECUTION_FAILED");

    try {
      Thread.currentThread().interrupt();
      assertThatThrownBy(() -> ComputationResourceGuard.callWithin(() -> "unused", envelope))
          .isInstanceOf(IllegalStateException.class)
          .hasMessageContaining("COMPUTATION_INTERRUPTED");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }

  private static void assertRequestRejected(
      ComputationMethod method,
      String arguments,
      ComputationResourceEnvelope envelope,
      String code) {
    var spec = ComputationFixtures.spec(method, arguments);
    assertThatThrownBy(() -> ComputationResourceGuard.validateRequest(spec, envelope))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(code);
  }

  private static void assertInvalidEnvelope(
      int maxCases,
      double maxCpuSeconds,
      long maxMemoryBytes,
      int maxOutputChars,
      int maxMatrixRows,
      int maxMatrixColumns,
      int maxRationalBitLength,
      int maxFiniteSetSize,
      int maxHypergraphVertices,
      int maxCertificateNodes,
      int maxResultChars) {
    assertThatThrownBy(
            () ->
                envelope(
                    maxCases,
                    maxCpuSeconds,
                    maxMemoryBytes,
                    maxOutputChars,
                    maxMatrixRows,
                    maxMatrixColumns,
                    maxRationalBitLength,
                    maxFiniteSetSize,
                    maxHypergraphVertices,
                    maxCertificateNodes,
                    maxResultChars))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static ComputationResourceEnvelope envelope(
      int maxCases,
      double maxCpuSeconds,
      long maxMemoryBytes,
      int maxOutputChars,
      int maxMatrixRows,
      int maxMatrixColumns,
      int maxRationalBitLength,
      int maxFiniteSetSize,
      int maxHypergraphVertices,
      int maxCertificateNodes,
      int maxResultChars) {
    return new ComputationResourceEnvelope(
        maxCases,
        maxCpuSeconds,
        maxMemoryBytes,
        maxOutputChars,
        maxMatrixRows,
        maxMatrixColumns,
        maxRationalBitLength,
        maxFiniteSetSize,
        maxHypergraphVertices,
        maxCertificateNodes,
        maxResultChars);
  }

  private static ComputationResultArtifact result(String content) {
    return new ComputationResultArtifact(
        "request-hash",
        "execution-hash",
        ExperimentOutcome.CERTIFIED,
        EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
        ComputationJson.object().put("content", content),
        null,
        ComputationJson.object().put("verified", true),
        true,
        1,
        0.001d,
        "boundary-producer",
        "1",
        "",
        null);
  }
}
