package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.desktop.benchmark.BenchmarkSecretSet;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadAggregateWriter;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBenchmarkCostEstimator;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBenchmarkHarness;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBenchmarkPlan;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBenchmarkPackager;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadBenchmarkPreflight;
import io.github.aililuola.mathproofmesh.desktop.benchmark.OlympiadSecretRedactor;
import java.nio.file.Path;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/** Explicitly gated, real-provider execution of the frozen 34-run benchmark protocol. */
final class OlympiadFiveKeyRealBenchmarkTest {
  private static final DateTimeFormatter CAMPAIGN_STAMP =
      DateTimeFormatter.ofPattern("uuuuMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

  @Test
  void executesTheAuthorizedFiveKeyBenchmarkThroughTheProductionCoordinator() {
    Assumptions.assumeTrue(
        Boolean.getBoolean("benchmark.execute.real"),
        "real benchmark requires -Dbenchmark.execute.real=true");
    Path projectRoot = DesktopLiveRunExecutionBackendTest.projectRoot();
    Path benchmarkRoot = projectRoot.resolve("benchmark/olympiad-5key-v1");
    Path outputRoot =
        benchmarkRoot
            .resolve("results")
            .resolve("real-" + CAMPAIGN_STAMP.format(Instant.now()));

    try (BenchmarkSecretSet secrets = BenchmarkSecretSet.load(System::getenv)) {
      DesktopOlympiadProductionExecutor executor =
          new DesktopOlympiadProductionExecutor(
              projectRoot, secrets, secrets.globalCostCapUsd());
      OlympiadBenchmarkCostEstimator.Estimate estimate =
          OlympiadBenchmarkCostEstimator.estimate(
              OlympiadBenchmarkPlan.fullSchedule(), executor.pricing());
      assertTrue(estimate.coveredBy(secrets.globalCostCapUsd()));
      System.out.println("OLYMPIAD FIVE-KEY REAL-PROVIDER PRE-RUN GATE");
      System.out.println("PLANNED_RUNS=" + estimate.runs());
      System.out.println("MAXIMUM_CALLS=" + estimate.maximumCalls());
      System.out.println("MAXIMUM_TOKENS=" + estimate.maximumTokens());
      System.out.println("MAXIMUM_COST_USD=" + estimate.maximumCostUsd().toPlainString());
      System.out.println("USER_COST_CAP_USD=" + secrets.globalCostCapUsd().toPlainString());
      System.out.println("PRICING_HASH=" + estimate.pricingHash());

      OlympiadBenchmarkPreflight.Result preflight =
          OlympiadBenchmarkPreflight.execute(outputRoot.resolve("preflight"), secrets);
      assertTrue(preflight.passed());
      System.out.println("PREFLIGHT_KEYS_PASSED=" + preflight.checks().size());

      OlympiadSecretRedactor redactor =
          new OlympiadSecretRedactor(secrets.transientValues());
      List<OlympiadBenchmarkHarness.CompletedRun> completed = new ArrayList<>();
      List<OlympiadBenchmarkPlan.RunSpec> full = OlympiadBenchmarkPlan.fullSchedule();
      runGate(benchmarkRoot, outputRoot, redactor, executor, standardRange(full, 1, 5), completed);
      runGate(benchmarkRoot, outputRoot, redactor, executor, standardRange(full, 6, 10), completed);
      runGate(benchmarkRoot, outputRoot, redactor, executor, standardRange(full, 11, 15), completed);
      runGate(benchmarkRoot, outputRoot, redactor, executor, standardRange(full, 16, 20), completed);
      runGate(
          benchmarkRoot,
          outputRoot,
          redactor,
          executor,
          full.stream()
              .filter(run -> run.kind() == OlympiadBenchmarkPlan.RunKind.REPLICATION)
              .toList(),
          completed);
      runGate(
          benchmarkRoot,
          outputRoot,
          redactor,
          executor,
          full.stream()
              .filter(run -> run.kind() == OlympiadBenchmarkPlan.RunKind.CONTROLLED_RECOVERY)
              .toList(),
          completed);

      OlympiadAggregateWriter.write(outputRoot.resolve("aggregate"), full, completed);
      OlympiadSecretRedactor.LeakReport leakReport = redactor.scan(outputRoot);
      assertTrue(leakReport.passed());
      assertEquals(34, completed.size());
      assertEquals(34L, completed.stream().map(run -> run.spec().identity()).distinct().count());
      Path protocolDocument =
          projectRoot
              .getParent()
              .getParent()
              .resolve("MathProofMesh_五Key数学竞赛Benchmark_Codex执行说明书_v1.0.md");
      OlympiadBenchmarkPackager.PackageResult packaged =
          OlympiadBenchmarkPackager.create(
              projectRoot,
              benchmarkRoot,
              outputRoot,
              protocolDocument,
              benchmarkRoot.resolve("results/packages"),
              redactor);
      System.out.println("OLYMPIAD FIVE-KEY REAL-PROVIDER DIAGNOSTIC");
      System.out.println("COMPLETED_RUNS=" + completed.size());
      System.out.println("HARD_INVARIANT_VIOLATIONS=0");
      System.out.println("SECRET_LEAKS=" + leakReport.secretLeaks());
      System.out.println("AUTHORIZATION_HEADER_LEAKS=" + leakReport.authorizationHeaderLeaks());
      System.out.println("CREDENTIAL_PATTERN_LEAKS=" + leakReport.credentialPatternLeaks());
      System.out.println("OUTPUT_ROOT=" + outputRoot);
      System.out.println("SANITIZED_ZIP=" + packaged.zip());
      System.out.println("RESULT=PASS");
    }
  }

  private static void runGate(
      Path benchmarkRoot,
      Path outputRoot,
      OlympiadSecretRedactor redactor,
      DesktopOlympiadProductionExecutor executor,
      List<OlympiadBenchmarkPlan.RunSpec> schedule,
      List<OlympiadBenchmarkHarness.CompletedRun> completed) {
    OlympiadBenchmarkHarness.HarnessResult result =
        new OlympiadBenchmarkHarness(benchmarkRoot, outputRoot, redactor)
            .execute(schedule, executor);
    assertTrue(result.passed());
    OlympiadSecretRedactor.LeakReport gateScan = redactor.scan(outputRoot);
    assertTrue(gateScan.passed());
    completed.addAll(result.completedRuns());
    System.out.println(
        "LAYER_GATE=PASS RUNS="
            + result.completedRuns().size()
            + " TOTAL_COMPLETED="
            + completed.size());
  }

  private static List<OlympiadBenchmarkPlan.RunSpec> standardRange(
      List<OlympiadBenchmarkPlan.RunSpec> schedule, int firstProblem, int lastProblem) {
    return schedule.stream()
        .filter(run -> run.kind() == OlympiadBenchmarkPlan.RunKind.STANDARD)
        .filter(
            run -> {
              int number = OlympiadBenchmarkPlan.problemNumber(run.problemId());
              return number >= firstProblem && number <= lastProblem;
            })
        .toList();
  }
}
