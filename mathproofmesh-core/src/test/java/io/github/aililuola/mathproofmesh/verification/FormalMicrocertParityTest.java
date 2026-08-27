package io.github.aililuola.mathproofmesh.verification;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.FormalCertificateRef;
import io.github.aililuola.mathproofmesh.contract.ObligationKind;
import io.github.aililuola.mathproofmesh.contract.ProofObligation;
import io.github.aililuola.mathproofmesh.proofgraph.ProofGraphStore;
import java.util.List;
import org.junit.jupiter.api.Test;

class FormalMicrocertParityTest {

  @Test
  void formal_selector_chooses_shared_high_centrality_obligation() {
    ProofObligation high =
        VerificationFixtures.obligation(
            "high", ObligationKind.LEMMA, 0.95, List.of("a", "b", "c"));
    ProofObligation low =
        VerificationFixtures.obligation(
            "low", ObligationKind.SUBGOAL, 0.05, List.of("a"));

    assertThat(new FormalizationCandidateSelector().select(List.of(low, high), 2))
        .containsExactly(high);
  }

  @Test
  void formal_failure_creates_task_without_refuting_natural_claim() {
    ProofGraphStore graph = new ProofGraphStore(VerificationFixtures.PROBLEM_HASH);
    ProofObligation source =
        graph.addObligation(
            VerificationFixtures.obligation(
                "source", ObligationKind.LEMMA, 0.9, List.of("a", "b")));
    FormalizationCandidateSelector selector = new FormalizationCandidateSelector();
    var packet = selector.packet(source);
    CompilerFeedbackInterpreter interpreter = new CompilerFeedbackInterpreter();

    assertThat(interpreter.unavailable(packet, "lean").status()).isEqualTo("pending");
    ProofObligation task =
        interpreter.applyFailure(
            packet,
            new FormalCertificateRef(
                null,
                "lean",
                null,
                null,
                List.of("type mismatch"),
                packet.packetId(),
                "bad-encoding",
                "failed"),
            graph);

    assertThat(task).isNotNull();
    assertThat(task.kind()).isEqualTo(ObligationKind.FORMALIZATION_TASK);
    assertThat(graph.getObligation("source").status()).isEqualTo("open");
  }
}
