package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.orchestration.StageTokenEnvelopeResolver;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

final class StageTokenEnvelopeDiagnosticTest {
  @Test
  void everyTokenBoundaryUsesTheMinimumAndContinuationRequiresCertifiedGain()
      throws Exception {
    StageTokenEnvelopeResolver resolver = new StageTokenEnvelopeResolver();
    int ordinaryStageLimitBypasses = 0;
    int agentMaxTokenBypasses = 0;
    int providerMaxTokenBypasses = 0;
    int globalRemainingTokenBypasses = 0;
    int actionEnvelopeTokenBypasses = 0;
    int continuationWithoutCertifiedGain = 0;
    int deepExplorationTierLosses = 0;
    int synthesisTokenReserveViolations = 0;
    int jsonRepairUnboundedCalls = 0;

    List<LimitCase> cases =
        List.of(
            new LimitCase("stage", request(10_000, 9_000, 8_000, 700, 6_000, 5_000, 20_000, 20_000, 0), 700),
            new LimitCase("agent", request(600, 500, 8_000, 7_000, 6_000, 5_000, 20_000, 20_000, 0), 500),
            new LimitCase("provider", request(600, 9_000, 400, 7_000, 6_000, 5_000, 20_000, 20_000, 0), 400),
            new LimitCase("action", request(600, 9_000, 8_000, 7_000, 6_000, 300, 20_000, 20_000, 0), 300),
            new LimitCase("deep-tier", request(600, 9_000, 8_000, 7_000, 250, 5_000, 20_000, 20_000, 0), 250),
            new LimitCase("global", request(600, 9_000, 8_000, 7_000, 6_000, 5_000, 20_000, 800, 0), 200),
            new LimitCase("finish", request(600, 9_000, 8_000, 7_000, 6_000, 5_000, 20_000, 1_000, 250), 150));
    for (LimitCase testCase : cases) {
      var resolution = resolver.resolve(testCase.request());
      assertThat(resolution.allowed()).as(testCase.name()).isTrue();
      assertThat(resolution.maxOutputTokens()).as(testCase.name()).isEqualTo(testCase.expected());
      switch (testCase.name()) {
        case "stage" -> ordinaryStageLimitBypasses += resolution.maxOutputTokens() > 700 ? 1 : 0;
        case "agent" -> agentMaxTokenBypasses += resolution.maxOutputTokens() > 500 ? 1 : 0;
        case "provider" -> providerMaxTokenBypasses += resolution.maxOutputTokens() > 400 ? 1 : 0;
        case "action" -> actionEnvelopeTokenBypasses += resolution.maxOutputTokens() > 300 ? 1 : 0;
        case "deep-tier" -> deepExplorationTierLosses += resolution.maxOutputTokens() > 250 ? 1 : 0;
        case "global" -> globalRemainingTokenBypasses += resolution.reservedTotalTokens() > 800 ? 1 : 0;
        case "finish" -> synthesisTokenReserveViolations += resolution.reservedTotalTokens() > 750 ? 1 : 0;
        default -> throw new IllegalStateException("unknown token boundary");
      }
    }

    Path root = projectRoot();
    String coordinator =
        Files.readString(
            root.resolve(
                "mathproofmesh-desktop/src/main/java/io/github/aililuola/mathproofmesh/desktop/DesktopSolveCoordinator.java"));
    String runner =
        Files.readString(
            root.resolve(
                "mathproofmesh-server/src/main/java/io/github/aililuola/mathproofmesh/agent/StructuredAgentRunner.java"));
    if (!coordinator.contains(
        "if (!verifiedGain || verdict == ProofControlModels.GateVerdict.BLOCK)")) {
      continuationWithoutCertifiedGain++;
    }
    if (!runner.contains("Math.min(original.maxOutputTokens(), jsonRepairMaxOutputTokens)")) {
      jsonRepairUnboundedCalls++;
    }

    assertThat(ordinaryStageLimitBypasses).isZero();
    assertThat(agentMaxTokenBypasses).isZero();
    assertThat(providerMaxTokenBypasses).isZero();
    assertThat(globalRemainingTokenBypasses).isZero();
    assertThat(actionEnvelopeTokenBypasses).isZero();
    assertThat(continuationWithoutCertifiedGain).isZero();
    assertThat(deepExplorationTierLosses).isZero();
    assertThat(synthesisTokenReserveViolations).isZero();
    assertThat(jsonRepairUnboundedCalls).isZero();

    System.out.println("STAGE TOKEN ENVELOPE DIAGNOSTIC");
    System.out.println("ORDINARY_STAGE_LIMIT_BYPASSES=" + ordinaryStageLimitBypasses);
    System.out.println("AGENT_MAX_TOKEN_BYPASSES=" + agentMaxTokenBypasses);
    System.out.println("PROVIDER_MAX_TOKEN_BYPASSES=" + providerMaxTokenBypasses);
    System.out.println("GLOBAL_REMAINING_TOKEN_BYPASSES=" + globalRemainingTokenBypasses);
    System.out.println("ACTION_ENVELOPE_TOKEN_BYPASSES=" + actionEnvelopeTokenBypasses);
    System.out.println("CONTINUATION_WITHOUT_CERTIFIED_GAIN=" + continuationWithoutCertifiedGain);
    System.out.println("DEEP_EXPLORATION_TIER_LOSSES=" + deepExplorationTierLosses);
    System.out.println("SYNTHESIS_TOKEN_RESERVE_VIOLATIONS=" + synthesisTokenReserveViolations);
    System.out.println("JSON_REPAIR_UNBOUNDED_CALLS=" + jsonRepairUnboundedCalls);
    System.out.println("RESULT=PASS");
  }

  private static StageTokenEnvelopeResolver.Request request(
      long input,
      long agent,
      long provider,
      long stage,
      long deep,
      long actionOutput,
      long actionTotal,
      long global,
      long finish) {
    return new StageTokenEnvelopeResolver.Request(
        input, agent, provider, stage, deep, actionOutput, actionTotal, global, finish);
  }

  private static Path projectRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("config/proof-control-active.yaml"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("project root was not found");
  }

  private record LimitCase(
      String name, StageTokenEnvelopeResolver.Request request, int expected) {}
}
