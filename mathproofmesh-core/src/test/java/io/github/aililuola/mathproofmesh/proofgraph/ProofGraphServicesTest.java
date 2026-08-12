package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.BridgeTask;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.RouteDescriptor;
import io.github.aililuola.mathproofmesh.contract.RouteStatus;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProofGraphServicesTest {

  @Test
  void sharedObligationsCreateOneBoundedBridgeTaskAndCloseExactlyOnce() {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    graph.addObligation(
        ProofGraphFixtures.obligation(
            "left", "shared lemma", "route-a", List.of()));
    graph.addObligation(
        ProofGraphFixtures.obligation(
            "right", "shared lemma", "route-b", List.of()));
    BridgeBroker broker = new BridgeBroker(new BridgePolicy(true, 1, 2), graph);

    List<BridgeTask> tasks = broker.detect(List.of("fact-a"), List.of("negative-a"), true);
    assertThat(tasks).hasSize(1);
    assertThat(broker.detect(List.of(), List.of(), true)).isEmpty();

    MessageEnvelope result = ProofGraphFixtures.fact("bridge-fact", "shared lemma");
    assertThat(broker.acceptVerifiedResult(tasks.getFirst().taskId(), result))
        .containsExactlyInAnyOrder("left", "right");
    assertThat(broker.acceptVerifiedResult(tasks.getFirst().taskId(), result))
        .isEmpty();
  }

  @Test
  void unverifiedBridgeResultCannotCloseObligation() {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    graph.addObligation(
        ProofGraphFixtures.obligation(
            "left", "shared lemma", "route-a", List.of()));
    graph.addObligation(
        ProofGraphFixtures.obligation(
            "right", "shared lemma", "route-b", List.of()));
    BridgeBroker broker = new BridgeBroker(BridgePolicy.defaults(), graph);
    BridgeTask task = broker.detect(List.of(), List.of(), true).getFirst();
    MessageEnvelope insight =
        ProofGraphFixtures.message(
            "insight",
            "shared lemma",
            "shared lemma",
            "route-a",
            "author",
            MessageType.CLAIM_PROPOSAL,
            EvidenceType.UNVERIFIED_IDEA,
            MemoryTier.INSIGHT,
            ClaimStatus.UNCERTAIN);

    assertThatThrownBy(
            () -> broker.acceptVerifiedResult(task.taskId(), insight))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("verified fact");
  }

  @Test
  void contradictionBlocksRelatedObligationUntilIndependentResolution() {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    graph.addObligation(
        ProofGraphFixtures.obligation("target", "claim p", "route-a", List.of()));
    MessageEnvelope verified = ProofGraphFixtures.fact("verified", "claim p");
    MessageEnvelope rejected =
        ProofGraphFixtures.message(
            "rejected",
            "claim p",
            "claim p",
            "route-b",
            "author-b",
            MessageType.CLAIM_PROPOSAL,
            EvidenceType.UNVERIFIED_IDEA,
            MemoryTier.INSIGHT,
            ClaimStatus.REJECTED);
    ContradictionBroker broker =
        new ContradictionBroker(ContradictionPolicy.defaults(), graph);

    ContradictionRecord conflict =
        broker.detect(List.of(verified, rejected)).getFirst();
    assertThat(conflict.status()).isEqualTo("open");
    assertThat(graph.getObligation("target").status()).isEqualTo("blocked");

    MessageEnvelope resolution =
        ProofGraphFixtures.message(
            "resolution",
            "claim p resolution",
            "claim p resolution",
            "route-c",
            "resolver-author",
            MessageType.CONTRADICTION_NOTICE,
            EvidenceType.NATURAL_PROOF_AUDITED,
            MemoryTier.FACT,
            ClaimStatus.VERIFIED);
    assertThatThrownBy(
            () -> broker.resolve(conflict.contradictionId(), resolution, "resolver-author"))
        .hasMessageContaining("independent");

    ContradictionRecord resolved =
        broker.resolve(conflict.contradictionId(), resolution, "independent-reviewer");
    assertThat(resolved.status()).isEqualTo("resolved");
    assertThat(graph.getObligation("target").status()).isEqualTo("open");
  }

  @Test
  void exactCounterexampleResolvesConflictWithoutVoting() {
    ProofGraphStore graph = new ProofGraphStore(ProofGraphFixtures.PROBLEM_HASH);
    MessageEnvelope verified = ProofGraphFixtures.fact("verified", "claim p");
    MessageEnvelope counterexample =
        ProofGraphFixtures.message(
            "counterexample",
            "claim p",
            "claim p",
            "route-b",
            "hunter",
            MessageType.COUNTEREXAMPLE,
            EvidenceType.COUNTEREXAMPLE,
            MemoryTier.NEGATIVE,
            ClaimStatus.REJECTED);
    ContradictionBroker broker =
        new ContradictionBroker(ContradictionPolicy.defaults(), graph);

    ContradictionRecord conflict =
        broker.detect(List.of(verified, counterexample)).getFirst();

    assertThat(conflict.status()).isEqualTo("resolved");
    assertThat(conflict.resolutionMessageId()).isEqualTo("counterexample");
    assertThat(broker.unresolved()).isEmpty();
  }

  @Test
  void duplicateRouteDetectionRequiresMechanismObligationAndFactOverlap() {
    DuplicateRouteDetector detector = new DuplicateRouteDetector(0.75);
    RouteDescriptor left =
        ProofGraphFixtures.route(
            "route-a", RouteStatus.ACTIVE, List.of("descent", "parity"));
    RouteDescriptor right =
        ProofGraphFixtures.route(
            "route-b", RouteStatus.ACTIVE, List.of("descent", "parity"));
    RouteDescriptor distinct =
        ProofGraphFixtures.route(
            "route-c", RouteStatus.ACTIVE, List.of("geometry"));

    List<DuplicateRouteMatch> matches =
        detector.detect(
            List.of(left, right, distinct),
            Map.of(
                "route-a", List.of("obl"),
                "route-b", List.of("obl"),
                "route-c", List.of("other")),
            Map.of(
                "route-a", List.of("fact"),
                "route-b", List.of("fact"),
                "route-c", List.of()),
            Map.of("route-a", 0.7, "route-b", 0.5));

    assertThat(matches).singleElement()
        .satisfies(
            match -> {
              assertThat(match.sourceRouteId()).isEqualTo("route-b");
              assertThat(match.survivorRouteId()).isEqualTo("route-a");
            });
  }
}
