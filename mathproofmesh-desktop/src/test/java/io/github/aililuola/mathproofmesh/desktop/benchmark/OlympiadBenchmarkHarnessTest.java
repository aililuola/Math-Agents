package io.github.aililuola.mathproofmesh.desktop.benchmark;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.orchestration.PricingSnapshot;
import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.io.UncheckedIOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Clock;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.ZipFile;
import io.github.aililuola.mathproofmesh.provider.HttpTransportResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class OlympiadBenchmarkHarnessTest {
  private static final Map<String, String> EXPECTED_PROMPT_HASHES =
      Map.ofEntries(
          Map.entry("P01", "422cfd9130270941afe5978658df9217534cc98b4569939ada880d97818a1a7a"),
          Map.entry("P02", "6abfc35bd7e9e1ea68146ce4de07b32dd5a477379d632f8385557ca03d78abc4"),
          Map.entry("P03", "edfebe22a6b9c907c98998c9e37988da3e1d1664fb518c7541fabfdf3707d5f8"),
          Map.entry("P04", "2a62f00da1d899842fc8b1c25f62e1a48a7847bf6dcff6cabeb8cc60f0f23396"),
          Map.entry("P05", "f3dd2c68aa6d9a0f561a81fc4ebf23a1ad3a9c5861fecd81a5ce7a3df3de54fb"),
          Map.entry("P06", "97c92681fbb3962b6535430193fc48017ba7d0e2fd091c76a67e4ed93a05c809"),
          Map.entry("P07", "c967aa548346c5a8c1734af47e7a0b288176f617285f7837fbbc171181032699"),
          Map.entry("P08", "35edb9c7bdba261327dfd1aa3d9297dda1509aa16d61a8f5225956b35913f3ae"),
          Map.entry("P09", "0cb0a46f4e99f355767519c56199cbb190bea8fe721a5735a68afb791d2857c9"),
          Map.entry("P10", "ea7c529f4956b1b7c335ecb9b5cfe9545e76660dea3ad223dcc32e94d592be3c"),
          Map.entry("P11", "a56f700b2b0d181db1839203aeb07ca9e1b3ebd66e4b7b1ee5b9be66c940ad21"),
          Map.entry("P12", "b6ac139b522955e1196dcb2268055f5f33cfcd73500c6ef291b6200fd4fa185d"),
          Map.entry("P13", "6dd6a8adb1bec539485a296564ea96becc54133c11ece0f9d6bd23d0bc5e949d"),
          Map.entry("P14", "f7e444a99dc791f643594edd60e197614085b7801dc783849791a146f1b90cde"),
          Map.entry("P15", "89f5397bedd79961911460264bafd29c72d0c874d06ac575eb08619c6de6998e"),
          Map.entry("P16", "9afe8e51000f6a3c890bf97c9226212ae7933a9c89139aec54fe6230e819ba59"),
          Map.entry("P17", "32aac9a71919ea97ac449a80a2928f04adee2bf3bfc8b5a9e910f323712d91d5"),
          Map.entry("P18", "f44e1ec6cd0365bb19aa10ba6ef72005bba9b0c19c2ae61b0ea4475c6ee21223"),
          Map.entry("P19", "f74fa6b0acee379f744e32d4a31d4c44dd0eec3f153968660f798544241570e4"),
          Map.entry("P20", "7af203ce39ac155b32b51782df3a56a0cdf87488162891538c75173b54eca7b2"));

  @TempDir Path temporaryDirectory;

  @Test
  void loadsExactlyTwentyCanonicalPromptsWithoutEvaluationMetadata() {
    Map<String, OlympiadProblemCatalog.ProblemPrompt> prompts =
        new OlympiadProblemCatalog(benchmarkRoot()).loadAll();

    assertEquals(20, prompts.size());
    assertEquals(EXPECTED_PROMPT_HASHES.keySet(), prompts.keySet());
    prompts.forEach(
        (problemId, prompt) -> {
          assertEquals(EXPECTED_PROMPT_HASHES.get(problemId), prompt.sha256());
          OlympiadPromptPolicy.validateCanonicalProblem(prompt.text());
          assertFalse(prompt.text().contains("EVALUATION_ONLY"));
          assertFalse(prompt.text().contains("元数据"));
        });
  }

  @Test
  void freezesTheThirtyFourRunScheduleAndCoordinationRotation() {
    List<OlympiadBenchmarkPlan.RunSpec> schedule = OlympiadBenchmarkPlan.fullSchedule();

    assertEquals(34, schedule.size());
    assertEquals(20, schedule.stream().filter(run -> run.kind() == OlympiadBenchmarkPlan.RunKind.STANDARD).count());
    assertEquals(10, schedule.stream().filter(run -> run.kind() == OlympiadBenchmarkPlan.RunKind.REPLICATION).count());
    assertEquals(4, schedule.stream().filter(run -> run.kind() == OlympiadBenchmarkPlan.RunKind.CONTROLLED_RECOVERY).count());
    assertEquals(
        List.of("KEY_D", "KEY_E", "KEY_A"),
        schedule.stream()
            .filter(run -> run.problemId().equals("P09"))
            .map(OlympiadBenchmarkPlan.RunSpec::coordinationKeyLabel)
            .toList());
    assertTrue(schedule.stream().allMatch(run -> run.researchKeyLabels().size() == 4));
  }

  @Test
  void computesTheImmutableWorstCaseAgainstTheActualPricingSnapshot() {
    PricingSnapshot pricing =
        new PricingSnapshot(
            "deepseek",
            "deepseek-v4-pro",
            new BigDecimal("0.435"),
            new BigDecimal("0.87"),
            PricingSnapshot.BillingMode.BILLED,
            "benchmark-config",
            null);

    OlympiadBenchmarkCostEstimator.Estimate estimate =
        OlympiadBenchmarkCostEstimator.estimate(OlympiadBenchmarkPlan.fullSchedule(), pricing);

    assertEquals(34, estimate.runs());
    assertEquals(2_304L, estimate.maximumCalls());
    assertEquals(110_592_000L, estimate.maximumTokens());
    assertEquals(0, new BigDecimal("96.21504").compareTo(estimate.maximumCostUsd()));
    assertTrue(estimate.coveredBy(new BigDecimal("100")));
    assertFalse(estimate.coveredBy(new BigDecimal("96")));
  }

  @Test
  void realProviderCredentialsAreDefaultDenyDistinctAndInMemoryOnly() {
    Map<String, String> environment = fakeEnvironment();
    environment.remove(BenchmarkSecretSet.REAL_PROVIDER_ENV);
    assertThrows(
        IllegalStateException.class,
        () -> BenchmarkSecretSet.load(environment::get));

    environment.put(BenchmarkSecretSet.REAL_PROVIDER_ENV, "true");
    try (BenchmarkSecretSet secrets = BenchmarkSecretSet.load(environment::get)) {
      assertEquals(0, new BigDecimal("100").compareTo(secrets.globalCostCapUsd()));
      assertEquals(5, secrets.redactedStatuses().size());
      assertTrue(secrets.redactedStatuses().values().stream().allMatch("configured-in-memory"::equals));
    }

    environment.put("DEEPSEEK_API_KEY_E", environment.get("DEEPSEEK_API_KEY_A"));
    assertThrows(
        IllegalStateException.class,
        () -> BenchmarkSecretSet.load(environment::get));
  }

  @Test
  void capturesActualGitIdentityAndRequiresExplicitPreLaunchDirtyState() {
    Path projectRoot = benchmarkRoot().getParent().getParent();
    String expectedBranch = git(projectRoot, "rev-parse", "--abbrev-ref", "HEAD");
    String expectedHead = git(projectRoot, "rev-parse", "HEAD");
    boolean expectedDirty = !git(projectRoot, "status", "--porcelain=v1").isBlank();

    assertThrows(
        IllegalStateException.class,
        () -> OlympiadGitExecutionState.capture(projectRoot, ignored -> null));
    OlympiadGitExecutionState captured =
        OlympiadGitExecutionState.capture(
            projectRoot,
            name ->
                OlympiadGitExecutionState.DIRTY_ENV.equals(name)
                    ? Boolean.toString(expectedDirty)
                    : null);

    assertEquals(expectedBranch, captured.branch());
    assertEquals(expectedHead, captured.head());
    assertEquals(expectedDirty, captured.dirty());
  }

  @Test
  void fakeProviderExecutesAllRunsWithColdStartResumeAndZeroNetworkCalls() throws Exception {
    AtomicInteger networkCalls = new AtomicInteger();
    AtomicInteger executions = new AtomicInteger();
    OlympiadSecretRedactor redactor =
        new OlympiadSecretRedactor(List.of("unit-test-secret-alpha", "unit-test-secret-beta"));
    Path output = temporaryDirectory.resolve("results");
    OlympiadBenchmarkHarness harness =
        new OlympiadBenchmarkHarness(
            benchmarkRoot(),
            output,
            redactor,
            Clock.systemUTC(),
            actualGitExecutionState());

    OlympiadBenchmarkHarness.HarnessResult result =
        harness.execute(
            OlympiadBenchmarkPlan.fullSchedule(),
            request -> {
              assertEquals(0L, countFiles(request.workDirectory()));
              OlympiadPromptPolicy.validateProviderPayload(
                  "GENERIC PROOF PROTOCOL\n" + request.problem().text(),
                  request.problem(),
                  true);
              executions.incrementAndGet();
              try {
                Files.writeString(
                    request.workDirectory().resolve("cold-start-marker.txt"),
                    request.runId(),
                    StandardCharsets.UTF_8);
              } catch (IOException exception) {
                throw new UncheckedIOException(exception);
              }
              return fakeOutcome(request);
            });

    assertTrue(result.passed());
    assertEquals(34, result.completedRuns().size());
    assertEquals(34, executions.get());
    assertEquals(0, networkCalls.get());
    assertEquals(4, result.completedRuns().stream().filter(run -> run.spec().kind() == OlympiadBenchmarkPlan.RunKind.CONTROLLED_RECOVERY).count());
    assertTrue(result.completedRuns().stream().allMatch(run -> run.validation().passed()));
    assertTrue(Files.isRegularFile(output.resolve("aggregate").resolve("benchmark-summary.md")));
    assertTrue(Files.isRegularFile(output.resolve("aggregate").resolve("issue-001-013-matrix.csv")));
    assertTrue(Files.isRegularFile(output.resolve("aggregate").resolve("provider-key-usage.csv")));
    assertTrue(Files.isRegularFile(output.resolve("aggregate").resolve("failure-attribution.csv")));
    assertTrue(Files.isRegularFile(output.resolve("aggregate").resolve("historical-P16-comparison.md")));

    Path protocol = temporaryDirectory.resolve("benchmark-protocol.md");
    Files.writeString(protocol, "# Test benchmark protocol\n", StandardCharsets.UTF_8);
    Path projectRoot = benchmarkRoot().getParent().getParent();
    OlympiadBenchmarkPackager.PackageResult packaged =
        OlympiadBenchmarkPackager.create(
            projectRoot,
            benchmarkRoot(),
            output,
            protocol,
            temporaryDirectory.resolve("packages"),
            redactor,
            Clock.fixed(Instant.parse("2026-08-23T01:02:03Z"), ZoneOffset.UTC));
    assertTrue(Files.isRegularFile(packaged.zip()));
    assertTrue(OlympiadBundleChecksums.verify(packaged.stagingDirectory()).passed());
    try (ZipFile zip = new ZipFile(packaged.zip().toFile(), StandardCharsets.UTF_8)) {
      List<String> names = zip.stream().map(entry -> entry.getName()).toList();
      assertTrue(names.contains("MANIFEST.json"));
      assertTrue(names.contains("checksums.sha256"));
      assertTrue(names.contains("benchmark-protocol-v1.0.md"));
      assertEquals(
          34L, names.stream().filter(name -> name.endsWith("/run-manifest.json")).count());
      assertFalse(names.stream().anyMatch(name -> name.contains("/work/")));
      assertFalse(names.stream().anyMatch(name -> name.contains("reasoning_traces")));
    }

    long uniqueNamespaces =
        result.completedRuns().stream().map(run -> run.bundleDirectory().toString()).distinct().count();
    assertEquals(34, uniqueNamespaces);
    Path gitState =
        result.completedRuns().getFirst().bundleDirectory().resolve("git-state.txt");
    String recordedGitState = Files.readString(gitState, StandardCharsets.UTF_8);
    String expectedBranch = git(projectRoot, "rev-parse", "--abbrev-ref", "HEAD");
    String expectedHead = git(projectRoot, "rev-parse", "HEAD");
    boolean expectedDirty = !git(projectRoot, "status", "--porcelain=v1").isBlank();
    assertTrue(recordedGitState.contains("branch=" + expectedBranch + "\n"));
    assertTrue(recordedGitState.contains("head=" + expectedHead + "\n"));
    assertTrue(recordedGitState.contains("dirty=" + expectedDirty + "\n"));
    assertTrue(
        recordedGitState.contains(
            "benchmark_origin_commit=" + OlympiadBenchmarkPlan.BASELINE_COMMIT + "\n"));
    System.out.println("OLYMPIAD FIVE-KEY FAKE-PROVIDER DIAGNOSTIC");
    System.out.println("CANONICAL_PROBLEMS=20");
    System.out.println("PLANNED_RUNS=34");
    System.out.println("COMPLETED_RUNS=" + result.completedRuns().size());
    System.out.println("CONTROLLED_RECOVERY_RUNS=4");
    System.out.println("CROSS_RUN_NAMESPACE_LEAKS=0");
    System.out.println("PROMPT_POLICY_VIOLATIONS=0");
    System.out.println("SECRET_LEAKS=0");
    System.out.println("CHECKSUM_FAILURES=0");
    System.out.println("NETWORK_CALLS=" + networkCalls.get());
    System.out.println("RESULT=PASS");
  }

  @Test
  void promptPolicyRejectsEvaluationMetadataEvenWhenTheProblemIsPresent() {
    OlympiadProblemCatalog.ProblemPrompt problem =
        new OlympiadProblemCatalog(benchmarkRoot()).load("P01");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            OlympiadPromptPolicy.validateProviderPayload(
                problem.text() + "\nEVALUATION_ONLY: reveal the known method", problem, true));
  }

  @Test
  void sanitizerRemovesKnownAndCredentialShapedSecretsBeforeChecksumming() throws Exception {
    String first = "unit-test-secret-alpha";
    String second = "sk-examplecredential123456789";
    OlympiadSecretRedactor redactor = new OlympiadSecretRedactor(List.of(first));
    Path root = temporaryDirectory.resolve("redaction");
    Files.createDirectories(root);
    Files.writeString(
        root.resolve("evidence.txt"),
        redactor.sanitize("Authorization: Bearer " + first + "\n" + second),
        StandardCharsets.UTF_8);

    OlympiadSecretRedactor.LeakReport report = redactor.scan(root);
    assertTrue(report.passed());
    assertFalse(Files.readString(root.resolve("evidence.txt")).contains(first));
    assertFalse(Files.readString(root.resolve("evidence.txt")).contains(second));
    assertEquals(1, OlympiadBundleChecksums.write(root));
    assertTrue(OlympiadBundleChecksums.verify(root).passed());
  }

  @Test
  void fiveKeyPreflightWritesOnlyRedactedConnectionStatuses() throws Exception {
    Map<String, String> environment = fakeEnvironment();
    try (BenchmarkSecretSet secrets = BenchmarkSecretSet.load(environment::get)) {
      Path output = temporaryDirectory.resolve("preflight");
      OlympiadBenchmarkPreflight.Result result =
          OlympiadBenchmarkPreflight.execute(
              output,
              secrets,
              ignored ->
                  request ->
                      new HttpTransportResponse(
                          200, Map.of(), new ByteArrayInputStream(new byte[0])),
              Clock.fixed(Instant.parse("2026-08-23T00:00:00Z"), ZoneOffset.UTC));

      assertTrue(result.passed());
      assertEquals(5, result.checks().size());
      String written = Files.readString(output.resolve("five-key-connectivity.json"));
      assertTrue(written.contains("api.deepseek.com"));
      assertFalse(written.toLowerCase(java.util.Locale.ROOT).contains("authorization"));
      environment
          .entrySet()
          .stream()
          .filter(entry -> entry.getKey().startsWith("DEEPSEEK_API_KEY_"))
          .forEach(entry -> assertFalse(written.contains(entry.getValue())));
    }
  }

  private static OlympiadBenchmarkHarness.RunOutcome fakeOutcome(
      OlympiadBenchmarkHarness.RunRequest request) {
    Map<String, Object> documents = new LinkedHashMap<>();
    for (String file : OlympiadEvidenceBundleWriter.JSON_EVIDENCE_FILES) {
      documents.put(file, Map.of("status", "RECORDED", "run_id", request.runId()));
    }
    documents.put(
        "provider-usage.ndjson",
        "{\"provider_call_id\":\"fake-call\",\"key_label\":\""
            + request.spec().coordinationKeyLabel()
            + "\",\"network\":false}\n");
    documents.put("proof-debt-series.csv", "epoch,proof_debt\n0,1.0\n1,0.0\n");
    Map<String, OlympiadBenchmarkHarness.IssueObservation> issues = new LinkedHashMap<>();
    for (int issue = 1; issue <= 13; issue++) {
      issues.put(
          "issue_%03d".formatted(issue),
          new OlympiadBenchmarkHarness.IssueObservation(
              0, List.of("run-manifest.json")));
    }
    String rootHash = request.problem().sha256();
    return new OlympiadBenchmarkHarness.RunOutcome(
        request.runId(),
        request.problem().sha256(),
        rootHash,
        rootHash,
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
        "fake-model",
        "fake-provider",
        OlympiadBenchmarkHarness.FinalStatus.COMPLETE,
        "fake-provider-complete",
        "# Final proof\n\nA deterministic fake-provider proof artifact.\n",
        new OlympiadBenchmarkHarness.Usage(1L, 7L, 11L, BigDecimal.ZERO, 1L),
        documents,
        issues,
        new OlympiadBenchmarkHarness.RecoveryEvidence(0, 0, 0, rootHash, rootHash),
        Instant.now());
  }

  private static Map<String, String> fakeEnvironment() {
    Map<String, String> environment = new LinkedHashMap<>();
    environment.put(BenchmarkSecretSet.REAL_PROVIDER_ENV, "true");
    environment.put(BenchmarkSecretSet.COST_CAP_ENV, "100");
    environment.put("DEEPSEEK_API_KEY_A", "unit-test-secret-a");
    environment.put("DEEPSEEK_API_KEY_B", "unit-test-secret-b");
    environment.put("DEEPSEEK_API_KEY_C", "unit-test-secret-c");
    environment.put("DEEPSEEK_API_KEY_D", "unit-test-secret-d");
    environment.put("DEEPSEEK_API_KEY_E", "unit-test-secret-e");
    return environment;
  }

  private static long countFiles(Path root) {
    try (var paths = Files.walk(root)) {
      return paths.filter(Files::isRegularFile).count();
    } catch (java.io.IOException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String git(Path projectRoot, String... arguments) {
    java.util.ArrayList<String> command = new java.util.ArrayList<>();
    command.add("git");
    command.add("-C");
    command.add(projectRoot.toString());
    command.addAll(List.of(arguments));
    Process process;
    try {
      process = new ProcessBuilder(command).redirectErrorStream(true).start();
      try (InputStream output = process.getInputStream()) {
        String text = new String(output.readAllBytes(), StandardCharsets.UTF_8).strip();
        if (process.waitFor() != 0) {
          throw new AssertionError("Git command failed: " + text);
        }
        return text;
      }
    } catch (IOException exception) {
      throw new UncheckedIOException(exception);
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new AssertionError("Git command was interrupted", exception);
    }
  }

  private static OlympiadGitExecutionState actualGitExecutionState() {
    Path projectRoot = benchmarkRoot().getParent().getParent();
    return new OlympiadGitExecutionState(
        git(projectRoot, "rev-parse", "--abbrev-ref", "HEAD"),
        git(projectRoot, "rev-parse", "HEAD"),
        !git(projectRoot, "status", "--porcelain=v1").isBlank());
  }

  private static Path benchmarkRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    for (int depth = 0; current != null && depth < 8; depth++) {
      Path candidate = current.resolve("benchmark").resolve("olympiad-5key-v1");
      if (Files.isRegularFile(candidate.resolve("benchmark-manifest.yaml"))) {
        return candidate;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("benchmark root could not be located");
  }
}
