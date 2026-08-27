package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import io.github.aililuola.mathproofmesh.api.SolveRequest;
import io.github.aililuola.mathproofmesh.concurrency.ResearchEpochStatus;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import io.github.aililuola.mathproofmesh.config.SystemConfig;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.provider.HttpTransport;
import io.github.aililuola.mathproofmesh.provider.LLMResponse;
import io.github.aililuola.mathproofmesh.provider.MockResponder;
import io.github.aililuola.mathproofmesh.provider.ProviderClientRegistry;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopAuthoritativePipelineUsesFrozenEpochTest {
  @TempDir Path temporaryDirectory;

  @Test
  void realDesktopPipelineUsesFrozenWorkResultsAndStableSingleWriterMerges()
      throws Exception {
    String runId = "authoritative-frozen-pipeline";
    Path runDirectory = temporaryDirectory.resolve("authoritative-pipeline");
    DesktopLiveRunExecutionBackend backend = backend();

    var result =
        backend.execute(
            new SolveRequest("Prove that 1 + 1 = 2.", runId, null, "scripted"),
            runId,
            "trace-authoritative-frozen-pipeline",
            runDirectory,
            (type, stage, agentId, status, summary, reference) -> {});
    DesktopSolveCheckpoint checkpoint =
        ContractObjectMapper.read(
            Files.readString(
                runDirectory.resolve("structured").resolve("desktop-solve-state.json")),
            DesktopSolveCheckpoint.class);

    Set<ResearchWorkKind> workKinds =
        checkpoint.researchTasks().tasks().stream()
            .map(record -> record.item().kind())
            .collect(Collectors.toUnmodifiableSet());
    long committedEpochs =
        checkpoint.researchEpochs().epochs().stream()
            .filter(epoch -> epoch.status() == ResearchEpochStatus.COMMITTED)
            .count();
    long staleSnapshotEpochs =
        checkpoint.researchEpochs().epochs().stream()
            .filter(epoch -> epoch.status() == ResearchEpochStatus.STALE_SNAPSHOT)
            .count();
    int productionWorkItems = checkpoint.researchTasks().tasks().size();
    int productionResultArtifacts = checkpoint.researchResults().artifacts().size();

    assertThat(result.status()).as(result.summary()).isEqualTo("completed");
    assertThat(productionWorkItems).isPositive();
    assertThat(productionResultArtifacts).isPositive();
    assertThat(committedEpochs).isPositive();
    assertThat(workKinds)
        .contains(
            ResearchWorkKind.ROUTE_EXPLORATION,
            ResearchWorkKind.ROUTE_REVIEW,
            ResearchWorkKind.CLAIM_PROOF_AUDIT);
    assertThat(staleSnapshotEpochs).isZero();
    assertThat(checkpoint.researchTasks().tasks())
        .allMatch(record -> record.item().snapshotHash().equals(
            checkpoint.researchEpochs().epochs().stream()
                .filter(epoch -> epoch.epochId().equals(record.item().epochId()))
                .findFirst()
                .orElseThrow()
                .snapshotHash()));

    System.out.println("AUTHORITATIVE PIPELINE FROZEN EPOCH DIAGNOSTIC");
    System.out.println("PRODUCTION_WORK_ITEMS=" + productionWorkItems);
    System.out.println("PRODUCTION_RESULT_ARTIFACTS=" + productionResultArtifacts);
    System.out.println("PRODUCTION_MERGE_RECEIPTS=" + committedEpochs);
    System.out.println("PRODUCTION_WORK_KINDS=" + workKinds);
    System.out.println("DIRECT_ROUTE_WORKER_AUTHORITY_MUTATIONS=" + staleSnapshotEpochs);
    System.out.println("RESULT=PASS");
  }

  private DesktopLiveRunExecutionBackend backend() {
    Path project = DesktopLiveRunExecutionBackendTest.projectRoot();
    DesktopRuntimeLocator locator = new DesktopRuntimeLocator(project, null);
    SystemConfig config =
        DesktopLiveRunExecutionBackendTest.mockConfig(
            locator.loadProfile("proof-control-active.yaml"));
    DesktopLiveRuntimeFactory.PreparedRuntime prepared =
        new DesktopLiveRuntimeFactory.PreparedRuntime("scripted", config, Map.of(), false);
    DesktopLiveRuntimeFactory runtimes = mock(DesktopLiveRuntimeFactory.class);
    when(runtimes.prepare(eq("scripted"), any(DesktopSettings.class))).thenReturn(prepared);
    when(runtimes.openProviders(prepared)).thenAnswer(ignored -> providers(config));
    DesktopPaths paths = DesktopTestSupport.paths(temporaryDirectory.resolve("desktop-data"));
    SettingsStore settings = new SettingsStore(paths.settingsFile(), DesktopTestSupport.MAPPER);
    return new DesktopLiveRunExecutionBackend(
        paths, settings, runtimes, locator, new DockerSandboxPreflight());
  }

  private static ProviderClientRegistry providers(SystemConfig config) {
    AtomicLong requestIds = new AtomicLong();
    MockResponder responder =
        request -> {
          LLMResponse source =
              DesktopLiveRunExecutionBackendTest.response(
                  request, DesktopLiveRunExecutionBackendTest.Mode.COMPLETED);
          return new LLMResponse(
              source.text(),
              source.model(),
              source.provider(),
              source.inputTokens(),
              source.outputTokens(),
              source.latencyMs(),
              source.requestId() + "-frozen-" + requestIds.incrementAndGet(),
              source.finishReason(),
              source.streaming(),
              source.metadata());
        };
    Map<String, MockResponder> responders = new LinkedHashMap<>();
    config.agents().forEach(agent -> responders.put(agent.id(), responder));
    HttpTransport noNetwork =
        request -> {
          throw new AssertionError("authoritative pipeline test attempted network access");
        };
    return new ProviderClientRegistry(
        config, responders, ignored -> noNetwork, false, ignored -> null);
  }
}
