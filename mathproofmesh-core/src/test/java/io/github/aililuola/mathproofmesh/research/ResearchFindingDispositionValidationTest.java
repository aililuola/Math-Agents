package io.github.aililuola.mathproofmesh.research;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ResearchFindingDisposition;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingDispositionAction;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingKind;
import io.github.aililuola.mathproofmesh.contract.ResearchFindingUpdateBatch;
import java.util.List;
import org.junit.jupiter.api.Test;

class ResearchFindingDispositionValidationTest {
  @Test
  void rejectsUnknownCrossRouteAndAuthoritySkippingPromotions() {
    ResearchCheckpointLedger ledger = new ResearchCheckpointLedger();
    ledger.appendEnvelopeFrame(
        "problem",
        "route",
        "independent_exploration",
        "call",
        ResearchCheckpointTestFixtures.frame(
            0,
            ResearchCheckpointTestFixtures.finding(
                ResearchFindingKind.EXACT_EXAMPLE, "an exact example")));
    String id = ledger.findings().getFirst().findingId();

    assertThatThrownBy(
            () ->
                ledger.applyUpdates(
                    "other-route",
                    new ResearchFindingUpdateBatch(
                        List.of(
                            new ResearchFindingDisposition(
                                id,
                                ResearchFindingDispositionAction.KEEP_ACTIVE,
                                null,
                                null)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("route boundary");
    assertThatThrownBy(
            () ->
                ledger.applyUpdates(
                    "route",
                    new ResearchFindingUpdateBatch(
                        List.of(
                            new ResearchFindingDisposition(
                                id,
                                ResearchFindingDispositionAction.PROMOTE_TO_PROPOSED_LEMMA,
                                null,
                                null)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("candidate_lemma");
  }
}
