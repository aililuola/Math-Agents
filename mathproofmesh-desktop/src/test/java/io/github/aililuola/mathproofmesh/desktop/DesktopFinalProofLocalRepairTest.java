package io.github.aililuola.mathproofmesh.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.api.RunExecutionBackend;
import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.FailureLevel;
import io.github.aililuola.mathproofmesh.contract.FinalProof;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import io.github.aililuola.mathproofmesh.contract.Severity;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import io.github.aililuola.mathproofmesh.contract.VerificationIssue;
import io.github.aililuola.mathproofmesh.contract.VerificationReport;
import io.github.aililuola.mathproofmesh.contract.VerificationStage;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import io.github.aililuola.mathproofmesh.provider.HttpTransport;
import io.github.aililuola.mathproofmesh.provider.InMemoryProviderCallRepository;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import io.github.aililuola.mathproofmesh.provider.ProviderRequest;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopFinalProofLocalRepairTest {
  private static final String RUN_ID = "final-proof-local-repair";
  private static final String PROBLEM =
      "Prove that every finite tournament contains a directed Hamilton path.";

  @TempDir Path temporaryDirectory;

  @Test
  void repairsOneLocalizedStructuralDefectAndRequiresIndependentRereview() throws Exception {
    RepairProbe probe = new RepairProbe(Scenario.REPAIR_PASSES);
    Path runDirectory = temporaryDirectory.resolve("repair-run");

    RunExecutionBackend.RunExecutionResult result =
        backend(probe)
            .execute(
                new SolveRequest(PROBLEM, RUN_ID, null, "proof_control_active"),
                RUN_ID,
                "trace-final-repair",
                runDirectory,
                sink());

    JsonNode checkpoint =
        ContractObjectMapper.parseTree(
            Files.readString(runDirectory.resolve("structured/desktop-solve-state.json")));
    JsonNode repaired = checkpoint.path("finalProof");

    assertEquals("completed", result.status());
    assertTrue(checkpoint.path("finalValidationPassed").asBoolean());
    assertEquals(2, probe.finalProofCalls.get());
    assertEquals(2, probe.structuralReviewCalls.get());
    assertEquals(1, probe.revisionCalls.get());
    assertEquals(0, probe.rootHashRebindFailures.get());
    assertEquals(0, probe.sourceAttemptRebindFailures.get());
    assertEquals(checkpoint.path("problemHash").asText(), repaired.path("problem_hash").asText());
    assertEquals(probe.authoritativeSourceAttemptIds, repaired.path("source_attempt_ids"));
    assertFalse(repaired.path("source_attempt_ids").toString().contains("model-forged-attempt"));
    assertTrue(
        repaired.path("proof_steps").toString().contains("s7"),
        "the repaired boundary step must cite the prior endpoint fact");

    System.out.println("FINAL PROOF LOCAL REPAIR DIAGNOSTIC");
    System.out.println("INITIAL_STRUCTURAL_FAILURES=" + probe.initialStructuralFailures.get());
    System.out.println("FINAL_REVISION_CALLS=" + probe.revisionCalls.get());
    System.out.println("STRUCTURAL_REREVIEWS=" + (probe.structuralReviewCalls.get() - 1));
    System.out.println("ROOT_HASH_REBIND_FAILURES=" + probe.rootHashRebindFailures.get());
    System.out.println("SOURCE_ATTEMPT_REBIND_FAILURES=" + probe.sourceAttemptRebindFailures.get());
    System.out.println("RESULT=PASS");
  }

  @Test
  void doesNotRepairWhenTheReviewerReportsProblemIntegrityFailure() throws Exception {
    RepairProbe probe = new RepairProbe(Scenario.PROBLEM_INTEGRITY_FAILURE);
    Path runDirectory = temporaryDirectory.resolve("integrity-failure-run");

    RunExecutionBackend.RunExecutionResult result =
        backend(probe)
            .execute(
                new SolveRequest(PROBLEM, RUN_ID + "-integrity", null, "proof_control_active"),
                RUN_ID + "-integrity",
                "trace-integrity-failure",
                runDirectory,
                sink());

    JsonNode checkpoint =
        ContractObjectMapper.parseTree(
            Files.readString(runDirectory.resolve("structured/desktop-solve-state.json")));
    assertEquals("unverified", result.status());
    assertFalse(checkpoint.path("finalValidationPassed").asBoolean());
    assertEquals(1, probe.finalProofCalls.get());
    assertEquals(1, probe.structuralReviewCalls.get());
    assertEquals(0, probe.revisionCalls.get());
  }

  @Test
  void stopsAfterOneRepairWhenStructuralRereviewStillFails() throws Exception {
    RepairProbe probe = new RepairProbe(Scenario.REPAIR_STILL_FAILS);
    Path runDirectory = temporaryDirectory.resolve("rereview-failure-run");

    RunExecutionBackend.RunExecutionResult result =
        backend(probe)
            .execute(
                new SolveRequest(PROBLEM, RUN_ID + "-rereview", null, "proof_control_active"),
                RUN_ID + "-rereview",
                "trace-rereview-failure",
                runDirectory,
                sink());

    JsonNode checkpoint =
        ContractObjectMapper.parseTree(
            Files.readString(runDirectory.resolve("structured/desktop-solve-state.json")));
    assertEquals("unverified", result.status());
    assertFalse(checkpoint.path("finalValidationPassed").asBoolean());
    assertEquals(2, probe.finalProofCalls.get());
    assertEquals(2, probe.structuralReviewCalls.get());
    assertEquals(1, probe.revisionCalls.get());
  }

  private DesktopLiveRunExecutionBackend backend(RepairProbe probe) {
    Path project = DesktopLiveRunExecutionBackendTest.projectRoot();
    DesktopRuntimeLocator locator = new DesktopRuntimeLocator(project, null);
    SystemConfig config =
        DesktopLiveRunExecutionBackendTest.mockConfig(
            locator.loadProfile("proof-control-active.yaml"));
    DesktopLiveRuntimeFactory.PreparedRuntime prepared =
        new DesktopLiveRuntimeFactory.PreparedRuntime("scripted", config, Map.of(), false);
    DesktopLiveRuntimeFactory runtimes = mock(DesktopLiveRuntimeFactory.class);
    when(runtimes.prepare(eq("proof_control_active"), any(DesktopSettings.class)))
        .thenReturn(prepared);
    when(runtimes.openProviders(prepared)).thenAnswer(ignored -> providers(config, probe));
    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("desktop-data"));
    SettingsStore settings = new SettingsStore(paths.settingsFile(), DesktopTestSupport.MAPPER);
    return new DesktopLiveRunExecutionBackend(
        paths,
        settings,
        runtimes,
        locator,
        new DockerSandboxPreflight(),
        InMemoryProviderCallRepository::new,
        DesktopDurableBoundaryObserver.none());
  }

  private static ProviderClientRegistry providers(SystemConfig config, RepairProbe probe) {
    MockResponder responder = request -> probe.respond(request);
    Map<String, MockResponder> responders = new LinkedHashMap<>();
    config.agents().forEach(agent -> responders.put(agent.id(), responder));
    HttpTransport noNetwork = request -> {
      throw new AssertionError("final proof repair test attempted network access");
    };
    return new ProviderClientRegistry(
        config, responders, ignored -> noNetwork, false, ignored -> null);
  }

  private static RunExecutionBackend.ProgressSink sink() {
    return (type, stage, agentId, status, summary, reference) -> {};
  }

  private static final class RepairProbe {
    private final Scenario scenario;
    private final AtomicInteger requestIds = new AtomicInteger();
    private final AtomicInteger finalProofCalls = new AtomicInteger();
    private final AtomicInteger structuralReviewCalls = new AtomicInteger();
    private final AtomicInteger initialStructuralFailures = new AtomicInteger();
    private final AtomicInteger revisionCalls = new AtomicInteger();
    private final AtomicInteger rootHashRebindFailures = new AtomicInteger();
    private final AtomicInteger sourceAttemptRebindFailures = new AtomicInteger();
    private JsonNode authoritativeSourceAttemptIds = JsonNodeFactory.instance.arrayNode();

    private RepairProbe(Scenario scenario) {
      this.scenario = scenario;
    }

    private LLMResponse respond(ProviderRequest request) {
      String prompt = request.messages().getLast().content();
      JsonNode context = sanitizedContext(prompt);
      Object override = null;
      if ("FinalProof".equals(request.schemaName())) {
        finalProofCalls.incrementAndGet();
        if (context.has("structural_repair_report")) {
          revisionCalls.incrementAndGet();
          authoritativeSourceAttemptIds = context.path("authoritative_source_attempt_ids").deepCopy();
          override = repairedProof();
        } else {
          override = flawedProof();
        }
      } else if ("VerificationReport".equals(request.schemaName())
          && "structural".equals(context.path("required_stage").asText())
          && "final_proof".equals(context.path("required_target_type").asText())) {
        int review = structuralReviewCalls.incrementAndGet();
        if (review == 1) {
          initialStructuralFailures.incrementAndGet();
          override =
              failedStructuralReview(scenario != Scenario.PROBLEM_INTEGRITY_FAILURE);
        } else {
          verifyAuthoritativeRevisionContext(context);
          override =
              scenario == Scenario.REPAIR_STILL_FAILS
                  ? failedStructuralReview(true)
                  : passedStructuralReview();
        }
      }
      LLMResponse source =
          override == null
              ? DesktopLiveRunExecutionBackendTest.response(
                  request, DesktopLiveRunExecutionBackendTest.Mode.COMPLETED)
              : response(override);
      return new LLMResponse(
          source.text(),
          source.model(),
          source.provider(),
          source.inputTokens(),
          source.outputTokens(),
          source.latencyMs(),
          source.requestId() + '-' + requestIds.incrementAndGet(),
          source.finishReason(),
          source.streaming(),
          source.metadata());
    }

    private void verifyAuthoritativeRevisionContext(JsonNode context) {
      JsonNode candidate = context.path("candidate_final_proof");
      JsonNode candidateProblemHash = contractField(candidate, "problem_hash", "problemHash");
      JsonNode candidateSourceAttempts =
          contractField(candidate, "source_attempt_ids", "sourceAttemptIds");
      if (!context.path("problem_hash").asText().equals(candidateProblemHash.asText())) {
        rootHashRebindFailures.incrementAndGet();
      }
      if (!authoritativeSourceAttemptIds.equals(candidateSourceAttempts)) {
        sourceAttemptRebindFailures.incrementAndGet();
      }
    }
  }

  private static FinalProof flawedProof() {
    return proof(List.of("s8"), "Maximality alone gives the next edge.", "placeholder-hash");
  }

  private static FinalProof repairedProof() {
    return proof(
        List.of("s8", "s7"),
        "If i < k-1 use maximality; if i = k-1 use the endpoint fact s7.",
        "model-forged-problem-hash");
  }

  private static FinalProof proof(
      List<String> boundaryDependencies, String boundaryJustification, String problemHash) {
    ProofStep endpoint =
        step("s7", List.of("s4"), "The endpoint comparison gives u -> v_k.");
    ProofStep boundary = step("s9", boundaryDependencies, boundaryJustification);
    return new FinalProof(
        "A longest directed path must contain every vertex.",
        List.of(),
        0.99d,
        List.of(),
        problemHash,
        List.of(endpoint, boundary),
        List.of("model-forged-attempt"));
  }

  private static ProofStep step(String id, List<String> dependencies, String justification) {
    return new ProofStep(
        "main",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0.99d,
        dependencies,
        List.of(),
        true,
        justification,
        "Boundary insertion step " + id,
        id,
        "derivation");
  }

  private static VerificationReport failedStructuralReview(boolean problemIntegrity) {
    VerificationIssue issue =
        new VerificationIssue(
            null,
            "The insertion edge is justified in both index cases.",
            null,
            "The i = k-1 boundary does not follow from maximality.",
            "BOUNDARY_CASE_OMITTED",
            "struct-s9-boundary",
            "structural",
            "Step s7 already establishes the endpoint orientation.",
            "Split i < k-1 from i = k-1 and cite s7 in the second case.",
            Severity.ERROR,
            "s9");
    return review(
        VerificationVerdict.FAIL,
        FailureLevel.PLAN,
        List.of(issue),
        problemIntegrity,
        "s9",
        "A localized boundary case is missing.");
  }

  private static VerificationReport passedStructuralReview() {
    return review(
        VerificationVerdict.PASS,
        FailureLevel.NONE,
        List.of(),
        true,
        null,
        "The repaired proof closes the boundary case and dependency graph.");
  }

  private static VerificationReport review(
      VerificationVerdict verdict,
      FailureLevel failureLevel,
      List<VerificationIssue> issues,
      boolean problemIntegrity,
      String firstError,
      String feedback) {
    return new VerificationReport(
        "scripted-structural-reviewer",
        List.of("s7", "s9"),
        feedback,
        0.99d,
        failureLevel,
        firstError,
        issues,
        problemIntegrity,
        null,
        "scripted-structural-review",
        VerificationStage.STRUCTURAL,
        List.of(),
        "final-proof",
        "final_proof",
        List.of(),
        List.of(),
        new UsageRecord(),
        verdict);
  }

  private static LLMResponse response(Object payload) {
    return new LLMResponse(
        ContractObjectMapper.write(payload),
        "scripted-model",
        "mock",
        7,
        11,
        0.5d,
        "scripted-final-repair",
        "stop",
        false,
        JsonNodeFactory.instance.objectNode());
  }

  private static JsonNode sanitizedContext(String prompt) {
    String startMarker = "SANITIZED CONTEXT:\n";
    String endMarker = "\n\nOUTPUT LANGUAGE:";
    int start = prompt.indexOf(startMarker);
    int end = start < 0 ? -1 : prompt.indexOf(endMarker, start + startMarker.length());
    if (start < 0 || end < 0) {
      return ContractObjectMapper.parseTree("{}");
    }
    return ContractObjectMapper.parseTree(prompt.substring(start + startMarker.length(), end));
  }

  private static JsonNode contractField(JsonNode node, String jsonName, String recordName) {
    return node.has(jsonName) ? node.path(jsonName) : node.path(recordName);
  }

  private enum Scenario {
    REPAIR_PASSES,
    PROBLEM_INTEGRITY_FAILURE,
    REPAIR_STILL_FAILS
  }
}
