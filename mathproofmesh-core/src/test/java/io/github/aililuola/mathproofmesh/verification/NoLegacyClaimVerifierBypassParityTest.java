package io.github.aililuola.mathproofmesh.verification;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import org.junit.jupiter.api.Test;

class NoLegacyClaimVerifierBypassParityTest {
  private static final String MARKER =
      "REJECTED_ROUTE_LOCAL_CLAIM_DO_NOT_SHARE";

  @Test
  void hierarchical_delta_and_attempt_verifiers_never_see_legacy_claim() {
    ObjectNode legacy =
        VerificationFixtures.object().put("statement", MARKER);
    ObjectNode typed =
        VerificationFixtures.object().put("statement", "admitted typed Fact");

    for (ContextPurpose purpose :
        List.of(
            ContextPurpose.DELTA_VERIFICATION,
            ContextPurpose.ATTEMPT_VERIFICATION)) {
      List<ObjectNode> selected =
          LegacyClaimQuarantine.admissible(
              "hierarchical_sparse", List.of(typed), List.of(legacy));
      String promptContext =
          purpose.name() + ":" + selected;
      assertThat(promptContext).doesNotContain(MARKER);
    }
  }
}
