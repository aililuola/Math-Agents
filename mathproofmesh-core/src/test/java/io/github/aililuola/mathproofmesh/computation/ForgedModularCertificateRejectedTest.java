package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import org.junit.jupiter.api.Test;

class ForgedModularCertificateRejectedTest {
  @Test
  void completeDomainFlagCannotReplaceIndependentResidueVerification() {
    var receipt =
        NativeComputationVerifierForgerySupport.forgedCertificate(
            ComputationMethod.MODULAR_EXHAUSTIVE,
            "{\"lhs\":\"n\",\"rhs\":\"n\",\"modulus\":2,\"variables\":[\"n\"],"
                + "\"finite_reduction\":true,\"reduction_justification\":\"parity\"}",
            ComputationJson.object().put("complete_domain", true),
            ComputationJson.object().put("all_cases_satisfied", false));

    assertThat(receipt.valid()).isFalse();
  }
}
