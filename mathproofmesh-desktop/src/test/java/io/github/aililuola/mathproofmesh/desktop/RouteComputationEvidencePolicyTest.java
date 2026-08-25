package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationHint;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.EvidenceRef;
import io.github.aililuola.mathproofmesh.contract.ProofAttempt;
import io.github.aililuola.mathproofmesh.contract.ProofStep;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import io.github.aililuola.mathproofmesh.contract.ToolRequest;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RouteComputationEvidencePolicyTest {
  @Test
  void optionalSearchHintDoesNotCreateAMandatoryProofToolGate() {
    StrategyCard hintOnly =
        strategy(
            List.of(),
            List.of(),
            List.of(
                new ComputationHint(
                    false,
                    "Abandon this route if a counterexample is found.",
                    ComputationPurpose.FALSIFY_CLAIM,
                    ComputationMethod.BOUNDED_INTEGER_SEARCH,
                    "The candidate intermediate identity.")));

    assertThat(RouteComputationEvidencePolicy.strategyHasBoundEvidence(hintOnly)).isFalse();
    assertThat(RouteComputationEvidencePolicy.strategyRequestsTool(hintOnly)).isFalse();
  }

  @Test
  void explicitStrategyEvidenceAndRequestsRemainFailClosed() {
    StrategyCard evidenceBound =
        strategy(
            List.of(),
            List.of(new EvidenceRef("artifact://calculation/1", "hash", "result", "exact")),
            List.of());
    StrategyCard toolRequested = strategy(List.of(toolRequest()), List.of(), List.of());

    assertThat(RouteComputationEvidencePolicy.strategyHasBoundEvidence(evidenceBound)).isTrue();
    assertThat(RouteComputationEvidencePolicy.strategyRequestsTool(toolRequested)).isTrue();
  }

  @Test
  void proofStepEvidenceAndRequestsEscalateAfterAnAttemptIsSubmitted() {
    ProofAttempt plain = attempt(step(List.of(), List.of(), List.of("1 + 1 = 2")));
    ProofAttempt evidenceBound =
        attempt(
            step(
                List.of(),
                List.of(new EvidenceRef("artifact://calculation/2", null, null, "replay")),
                List.of()));
    ProofAttempt toolRequested = attempt(step(List.of(toolRequest()), List.of(), List.of()));

    assertThat(RouteComputationEvidencePolicy.attemptHasBoundEvidence(plain)).isFalse();
    assertThat(RouteComputationEvidencePolicy.attemptRequestsTool(plain)).isFalse();
    assertThat(RouteComputationEvidencePolicy.attemptHasBoundEvidence(evidenceBound)).isTrue();
    assertThat(RouteComputationEvidencePolicy.attemptRequestsTool(toolRequested)).isTrue();
  }

  @Test
  void completeSelfContainedAttemptSupersedesAnUnusedStrategyPlanningCheck() {
    StrategyCard planningCheck = strategy(List.of(toolRequest()), List.of(), List.of());
    ProofAttempt complete = attempt(step(List.of(), List.of(), List.of("symbolic identity")));

    assertThat(RouteComputationEvidencePolicy.requiresReplay(planningCheck, null, false)).isTrue();
    assertThat(RouteComputationEvidencePolicy.requiresReplay(planningCheck, complete, false))
        .isFalse();
  }

  @Test
  void observedOrAttemptBoundComputationCanNeverBeSuperseded() {
    StrategyCard planningCheck = strategy(List.of(toolRequest()), List.of(), List.of());
    StrategyCard strategyEvidence =
        strategy(
            List.of(),
            List.of(new EvidenceRef("artifact://calculation/3", "hash", "result", "exact")),
            List.of());
    ProofAttempt attemptRequest = attempt(step(List.of(toolRequest()), List.of(), List.of()));
    ProofAttempt attemptEvidence =
        attempt(
            step(
                List.of(),
                List.of(new EvidenceRef("artifact://calculation/4", null, null, "replay")),
                List.of()));
    ProofAttempt partial =
        attempt(step(List.of(), List.of(), List.of()), AttemptStatus.PARTIAL, List.of());
    ProofAttempt unresolved =
        attempt(
            step(List.of(), List.of(), List.of()),
            AttemptStatus.COMPLETE,
            List.of("one symbolic gap remains"));

    assertThat(RouteComputationEvidencePolicy.requiresReplay(planningCheck, attemptRequest, false))
        .isTrue();
    assertThat(RouteComputationEvidencePolicy.requiresReplay(planningCheck, attemptEvidence, false))
        .isTrue();
    assertThat(RouteComputationEvidencePolicy.requiresReplay(strategyEvidence, attempt(null), false))
        .isTrue();
    assertThat(RouteComputationEvidencePolicy.requiresReplay(planningCheck, partial, false))
        .isTrue();
    assertThat(RouteComputationEvidencePolicy.requiresReplay(planningCheck, unresolved, false))
        .isTrue();
    assertThat(
            RouteComputationEvidencePolicy.requiresReplay(
                strategy(List.of(), List.of(), List.of()), attempt(null), true))
        .isTrue();
  }

  private static StrategyCard strategy(
      List<ToolRequest> checks,
      List<EvidenceRef> evidenceRefs,
      List<ComputationHint> hints) {
    return new StrategyCard(
        null,
        "Establish the exact target.",
        checks,
        evidenceRefs,
        hints,
        "Use a direct symbolic proof.",
        List.of(),
        0.1d,
        0.9d,
        List.of(),
        "Search for a counterexample.",
        "The proof is symbolic and route-local.",
        null,
        null,
        List.of(),
        List.of(),
        "strategy-policy-test",
        List.of(),
        "Policy test");
  }

  private static ProofAttempt attempt(ProofStep step) {
    return attempt(step, AttemptStatus.COMPLETE, List.of());
  }

  private static ProofAttempt attempt(
      ProofStep step, AttemptStatus status, List<String> unresolvedGaps) {
    return new ProofAttempt(
        "author",
        "attempt-policy-test",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        "The target follows.",
        null,
        "route-policy-test",
        "problem-hash",
        "A direct proof.",
        step == null ? List.of() : List.of(step),
        List.of(),
        null,
        null,
        0,
        1,
        0.9d,
        status,
        "strategy-policy-test",
        unresolvedGaps,
        new UsageRecord());
  }

  private static ProofStep step(
      List<ToolRequest> checks, List<EvidenceRef> evidenceRefs, List<String> calculations) {
    return new ProofStep(
        null,
        checks,
        evidenceRefs,
        calculations,
        List.of(),
        0.9d,
        List.of(),
        List.of(),
        true,
        "The symbolic identity follows directly.",
        "The target identity holds.",
        "step-policy-test",
        "derivation");
  }

  private static ToolRequest toolRequest() {
    return new ToolRequest(
        JsonNodeFactory.instance.objectNode(),
        JsonNodeFactory.instance.objectNode(),
        ComputationMethod.BOUNDED_INTEGER_SEARCH.value(),
        10,
        "Replay a bounded falsification check.",
        "tool-request-policy-test");
  }
}
