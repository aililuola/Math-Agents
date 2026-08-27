package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class DesktopBlankTargetClaimIdEvidenceRejectedTest {
  @TempDir Path temporaryDirectory;

  @Test
  void blankTargetClaimIdCannotReceiveClaimScopedAuthority() throws Exception {
    var frozen =
        DesktopClaimCourtSemanticContextTestSupport.freeze(
            temporaryDirectory.resolve("frozen"),
            "blank-target-frozen",
            "forall",
            List.of("x"),
            List.of(),
            List.of("scope-D"),
            "positive");
    var binding =
        DesktopClaimEvidenceTestSupport.binding(
            frozen, JsonNodeFactory.instance.objectNode().put("x", "D"));
    var spec = DesktopClaimEvidenceTestSupport.spec(binding);
    var legacyBlankResult = DesktopClaimEvidenceTestSupport.result(spec, binding, null);

    try (DesktopClaimSalvageTestHarness harness =
        DesktopClaimSalvageTestHarness.open(
            temporaryDirectory.resolve("issuer"), "blank-target-issuer")) {
      int accepts =
          harness.trustedComputationEvidence(spec, legacyBlankResult, frozen).size();
      assertThat(accepts).isZero();
      System.out.println("BLANK_TARGET_CLAIM_ID_EVIDENCE_ACCEPTS=" + accepts);
    }
  }
}
