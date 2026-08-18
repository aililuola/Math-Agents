package io.github.aililuola.mathproofmesh.api;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class RunUsageAfterCheckpointNotLostTest {
  @TempDir Path temporaryDirectory;

  @Test
  void monotonicResultAggregateExtendsTheLastSemanticCheckpoint() throws Exception {
    Path structured = temporaryDirectory.resolve("structured");
    Files.createDirectories(structured);
    Files.writeString(
        structured.resolve("desktop-solve-state.json"),
        """
        {"terminal":false,"usageTotals":{"calls":20,"inputTokens":1000,
        "outputTokens":500,"costUsd":1.50,"latencyMs":800}}
        """,
        StandardCharsets.UTF_8);
    RunExecutionBackend.RunExecutionResult result =
        new RunExecutionBackend.RunExecutionResult(
            "failed",
            "proof",
            "late failure",
            List.of(),
            List.of(),
            "",
            9,
            new RunExecutionBackend.ExecutionUsage(
                23L, 1150L, 560L, new BigDecimal("1.75"), 910.0d),
            null);

    var state =
        RunStateApiProjection.reconcile(
            new SolveRequest("Prove P.", "late-usage", null, "smoke"),
            "late-usage",
            "attempt-one",
            temporaryDirectory,
            result,
            null);

    assertThat(state.authority().usage().providerCalls()).isEqualTo(23L);
    assertThat(state.authority().usage().inputTokens()).isEqualTo(1150L);
    assertThat(state.authority().usage().outputTokens()).isEqualTo(560L);
  }
}
