package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ComputationVerificationReceipt;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import org.junit.jupiter.api.Test;

class ComputationVerificationReceiptTest {
  @Test
  void receiptRoundTripRetainsItsContentHash() throws Exception {
    var outcome =
        ComputationIssue010TestSupport.run(
            ComputationFixtures.broker("receipt-round-trip"),
            ComputationIssue010TestSupport.finiteMapSpec());
    String json = ContractObjectMapper.write(outcome.verificationReceipt());
    var restored = ContractObjectMapper.read(json, ComputationVerificationReceipt.class);
    assertThat(restored.receiptHash()).isEqualTo(outcome.verificationReceipt().receiptHash());
  }
}
