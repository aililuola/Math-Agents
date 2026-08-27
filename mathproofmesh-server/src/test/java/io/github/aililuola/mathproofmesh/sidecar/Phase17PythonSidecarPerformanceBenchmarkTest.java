package io.github.aililuola.mathproofmesh.sidecar;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class Phase17PythonSidecarPerformanceBenchmarkTest {
  private static final int WARM_CALLS = 5;

  @Test
  void coldAndWarmShortLivedPythonWorkersAreMeasured() throws Exception {
    PythonSidecarWorkerPool workers =
        new PythonSidecarWorkerPool(
            SidecarTestFixtures.python(),
            SidecarTestFixtures.service(),
            2,
            Duration.ofSeconds(10));
    SidecarLimits limits =
        new SidecarLimits(1_000, 20260730, Duration.ofSeconds(10), 100_000);

    long coldStarted = System.nanoTime();
    ObjectNode cold =
        workers.execute(
            "phase17-sidecar-cold",
            "sympy_simplify",
            parameters("x-x"),
            limits);
    long coldNanos = System.nanoTime() - coldStarted;
    assertCertified(cold);

    long warmStarted = System.nanoTime();
    for (int index = 0; index < WARM_CALLS; index++) {
      ObjectNode response =
          workers.execute(
              "phase17-sidecar-warm-" + index,
              "sympy_simplify",
              parameters("(x + 1)**2 - (x**2 + 2*x + 1)"),
              limits);
      assertCertified(response);
    }
    long warmTotalNanos = System.nanoTime() - warmStarted;
    long warmMeanNanos = warmTotalNanos / WARM_CALLS;

    assertThat(coldNanos).isPositive();
    assertThat(warmMeanNanos).isPositive();

    Path report =
        Path.of(System.getProperty("mathproofmesh.projectRoot"))
            .resolve("target/benchmark-reports/phase17-python-sidecar.json");
    Files.createDirectories(report.getParent());
    Files.writeString(
        report,
        """
        {
          "scenario":"python-sidecar-cold-warm-process-startup",
          "worker_lifecycle":"short-lived-process-per-request",
          "cold_calls":1,
          "warm_calls":%d,
          "cold_elapsed_ns":%d,
          "warm_total_elapsed_ns":%d,
          "warm_mean_elapsed_ns":%d,
          "certified_results":%d,
          "result":"PASS"
        }
        """
            .formatted(
                WARM_CALLS,
                coldNanos,
                warmTotalNanos,
                warmMeanNanos,
                WARM_CALLS + 1),
        StandardCharsets.UTF_8);
  }

  private static ObjectNode parameters(String expression) {
    return JsonNodeFactory.instance.objectNode().put("expression", expression);
  }

  private static void assertCertified(ObjectNode response) {
    assertThat(response.path("result").path("outcome").asText())
        .isEqualTo("certified");
    assertThat(response.path("error").isNull()).isTrue();
  }
}
