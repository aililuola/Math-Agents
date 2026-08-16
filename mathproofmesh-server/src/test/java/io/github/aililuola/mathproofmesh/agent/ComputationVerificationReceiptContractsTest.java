package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;
import io.github.aililuola.mathproofmesh.contract.ComputationVerificationStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationVerifiedAuthority;
import io.github.aililuola.mathproofmesh.contract.ContractValidationException;
import java.util.List;
import org.junit.jupiter.api.Test;

class ComputationVerificationReceiptContractsTest {
  @Test
  void invalidReceiptCannotSelfReportMathematicalAuthority() {
    assertThatThrownBy(
            () -> new ComputationVerificationReceipt(
                "receipt", "c".repeat(64), "verifier", "1", ComputationVerificationStatus.INVALID,
                false, ComputationVerifiedAuthority.FORMAL_CERTIFICATE, "s".repeat(64), List.of(), null, null))
        .isInstanceOf(ContractValidationException.class);
  }
}
