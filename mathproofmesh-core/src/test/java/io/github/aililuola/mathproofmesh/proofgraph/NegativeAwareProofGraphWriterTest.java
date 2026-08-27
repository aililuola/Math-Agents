package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.memory.DeterministicNegativeSeed;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeAdmissionGate;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeBlockedException;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRegistry;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeTargetType;
import java.util.List;
import org.junit.jupiter.api.Test;

class NegativeAwareProofGraphWriterTest {
  private static final String REJECTED = "This proof target is deterministically false.";

  @Test
  void blocksPositiveMutationsButKeepsExplicitRootAndFalsificationPaths() {
    NegativeKnowledgeRegistry registry = registry(REJECTED, List.of());
    ProofGraphStore store = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    store.configureNegativeKnowledge(registry, () -> 0, List.of());

    assertThatThrownBy(() -> store.addObligation(obligation("blocked", REJECTED, "route-a")))
        .isInstanceOf(NegativeKnowledgeBlockedException.class);

    ProofObligation falsification =
        store.addFalsificationObligation(obligation("falsification", REJECTED, "route-a"));
    ProofObligation root =
        store.addRootGoalObligation(
            obligation(
                "root", REJECTED, List.of("root-route"), ObligationKind.MAIN_GOAL, "open"));

    MessageEnvelope blockedClaim = claim("claim-blocked", REJECTED, List.of());
    assertThatThrownBy(() -> store.addClaimNode(blockedClaim))
        .isInstanceOf(NegativeKnowledgeBlockedException.class);
    MessageEnvelope falsificationClaim = claim("claim-falsification", REJECTED, List.of());
    assertThat(store.addFalsificationClaimNode(falsificationClaim).messageId())
        .isEqualTo("claim-falsification");

    NegativeAwareProofGraphWriter writer =
        new NegativeAwareProofGraphWriter(
            store, new NegativeKnowledgeAdmissionGate(registry), () -> -4, null);
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                writer.addRootGoalObligation(
                    obligation("bad-root", REJECTED, "root-route"), "model-claimed-root"));
    assertThat(
            writer
                .addFalsificationObligation(
                    obligation("negative-round-falsification", REJECTED, "route-b"))
                .obligationId())
        .isEqualTo("negative-round-falsification");

    assertThat(falsification.status()).isEqualTo("open");
    assertThat(root.kind()).isEqualTo(ObligationKind.MAIN_GOAL);
    assertThat(store.obligations())
        .extracting(ProofObligation::obligationId)
        .containsExactlyInAnyOrder(
            "falsification", "root", "negative-round-falsification");
  }

  @Test
  void usesMessageScopeInsteadOfDefaultScopeWhenPresent() {
    List<String> scope = List.of("trusted narrow scope");
    NegativeKnowledgeRegistry registry = registry(REJECTED, scope);
    ProofGraphStore store = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    store.configureNegativeKnowledge(registry, () -> 0, List.of("different default scope"));

    assertThatThrownBy(() -> store.addClaimNode(claim("scoped-claim", REJECTED, scope)))
        .isInstanceOf(NegativeKnowledgeBlockedException.class);
  }

  @Test
  void restoreRevalidationBlocksOnlyActiveNonRootObligations() {
    ProofGraphStore store = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    store.addObligation(obligation("open", REJECTED, "route-open"));
    store.addObligation(
        obligation(
            "tentative", REJECTED, List.of("route-tentative"), ObligationKind.SUBGOAL,
            "tentative"));
    store.addObligation(
        obligation(
            "inactive", REJECTED, List.of("route-inactive"), ObligationKind.SUBGOAL,
            "blocked"));
    store.addRootGoalObligation(
        obligation(
            "main", REJECTED, List.of("route-main"), ObligationKind.MAIN_GOAL, "open"));
    store.addObligation(obligation("unrelated", "A valid independent target.", "route-valid"));

    store.configureNegativeKnowledge(registry(REJECTED, List.of()), () -> 0, List.of());

    assertThat(store.revalidateNegativeKnowledge()).containsExactlyInAnyOrder("open", "tentative");
    assertThat(status(store, "open")).isEqualTo("blocked");
    assertThat(status(store, "tentative")).isEqualTo("blocked");
    assertThat(status(store, "inactive")).isEqualTo("blocked");
    assertThat(status(store, "main")).isEqualTo("open");
    assertThat(status(store, "unrelated")).isEqualTo("open");
  }

  @Test
  void routeRetirementSkipsRootOtherRoutesAndInactiveObligations() {
    ProofGraphStore store = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    store.addRootGoalObligation(
        obligation(
            "route-root", "Immutable root.", List.of("retired-route"),
            ObligationKind.MAIN_GOAL, "open"));
    store.addObligation(obligation("route-open", "Open route target.", "retired-route"));
    store.addObligation(
        obligation(
            "route-tentative", "Tentative route target.", List.of("retired-route"),
            ObligationKind.SUBGOAL, "tentative"));
    store.addObligation(
        obligation(
            "route-inactive", "Inactive route target.", List.of("retired-route"),
            ObligationKind.SUBGOAL, "blocked"));
    store.addObligation(obligation("other-route", "Other route target.", "active-route"));

    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.blockRouteObligationsForNegativeKnowledge(null));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> store.blockRouteObligationsForNegativeKnowledge(" "));

    assertThat(store.blockRouteObligationsForNegativeKnowledge("retired-route"))
        .containsExactlyInAnyOrder("route-open", "route-tentative");
    assertThat(status(store, "route-root")).isEqualTo("open");
    assertThat(status(store, "route-open")).isEqualTo("blocked");
    assertThat(status(store, "route-tentative")).isEqualTo("blocked");
    assertThat(status(store, "route-inactive")).isEqualTo("blocked");
    assertThat(status(store, "other-route")).isEqualTo("open");
  }

  private static NegativeKnowledgeRegistry registry(String statement, List<String> scope) {
    DeterministicNegativeSeed seed =
        DeterministicNegativeSeed.trustedCodeSeed(
            "proof-graph-test",
            NegativeKnowledgeTargetType.CLAIM,
            statement,
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            scope,
            "The test guardrail is deterministic.");
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    registry.registerDeterministicGuardrail(ProofGraphFixtures.PROBLEM_HASH, seed, 0);
    return registry;
  }

  private static ProofObligation obligation(String id, String statement, String route) {
    return obligation(id, statement, List.of(route), ObligationKind.SUBGOAL, "open");
  }

  private static ProofObligation obligation(
      String id,
      String statement,
      List<String> routes,
      ObligationKind kind,
      String status) {
    return new ProofObligation(
        List.of(),
        0.7d,
        "",
        List.of(),
        List.of(),
        List.of(),
        null,
        kind,
        statement,
        id,
        0.8d,
        ProofGraphFixtures.PROBLEM_HASH,
        List.of(),
        routes,
        statement,
        status);
  }

  private static MessageEnvelope claim(String id, String statement, List<String> scope) {
    return new MessageEnvelope(
        List.of(),
        List.of(),
        statement,
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.NATURAL_PROOF_AUDITED,
        MemoryTier.FACT,
        id,
        MessageType.VERIFIED_LEMMA,
        1.0d,
        statement,
        ProofGraphFixtures.PROBLEM_HASH,
        List.of(),
        null,
        0,
        "1",
        scope,
        "proof-graph-test",
        io.github.aililuola.mathproofmesh.contract.RouteRole.PROVER,
        "route-test",
        statement,
        List.of(),
        2,
        List.of(),
        1.0d,
        ClaimStatus.VERIFIED);
  }

  private static String status(ProofGraphStore store, String obligationId) {
    return store.obligations().stream()
        .filter(obligation -> obligation.obligationId().equals(obligationId))
        .findFirst()
        .orElseThrow()
        .status();
  }
}
