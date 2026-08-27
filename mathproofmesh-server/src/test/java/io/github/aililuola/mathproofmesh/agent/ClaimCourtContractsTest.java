package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ClaimBlindAdjudicationVerdict;
import io.github.aililuola.mathproofmesh.contract.ClaimMinimalRepairDisposition;
import io.github.aililuola.mathproofmesh.contract.ClaimProofPatch;
import io.github.aililuola.mathproofmesh.contract.StatementFalsificationDisposition;
import java.util.Arrays;
import org.junit.jupiter.api.Test;

final class ClaimCourtContractsTest {
  @Test
  void modelContractsCannotSelfGrantTruthOrServerOwnedRevisionIdentity() {
    assertThatThrownBy(
            () -> StatementFalsificationDisposition.valueOf("VERIFIED_REFUTATION"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThat(Arrays.stream(ClaimMinimalRepairDisposition.values()).map(Enum::name))
        .doesNotContain("VERIFIED", "FACT", "PERMANENT_NEGATIVE");
    assertThat(Arrays.stream(ClaimBlindAdjudicationVerdict.values()).map(Enum::name))
        .doesNotContain("REFUTED", "REJECTED", "PERMANENT_NEGATIVE");
    assertThat(Arrays.stream(ClaimProofPatch.class.getRecordComponents()).map(component -> component.getName()))
        .doesNotContain(
            "statement",
            "assumptions",
            "quantifiers",
            "scopeLimitations",
            "finalRevisionId",
            "finalProofHash");
  }
}
