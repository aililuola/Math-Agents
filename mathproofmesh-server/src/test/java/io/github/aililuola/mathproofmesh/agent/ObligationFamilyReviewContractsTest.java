package io.github.aililuola.mathproofmesh.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import io.github.aililuola.mathproofmesh.contract.ContractValidationException;
import io.github.aililuola.mathproofmesh.contract.ObligationFamilyReviewBatch;
import io.github.aililuola.mathproofmesh.contract.ObligationFamilyReviewDecision;
import io.github.aililuola.mathproofmesh.contract.UsageRecord;
import java.util.List;
import org.junit.jupiter.api.Test;

class ObligationFamilyReviewContractsTest {
  @Test
  void contractAllowsOnlySchedulingRelationsAndRoundTripsStrictly() throws Exception {
    ObligationFamilyReviewBatch batch =
        new ObligationFamilyReviewBatch(
            "review",
            "agent",
            "family",
            List.of(
                new ObligationFamilyReviewDecision(
                    "canonical", "REFINEMENT", 0.9d, "bounded relation")),
            "artifact://review",
            new UsageRecord());
    assertThat(
            ContractObjectMapper.read(
                ContractObjectMapper.write(batch), ObligationFamilyReviewBatch.class))
        .isEqualTo(batch);
    assertThatThrownBy(
            () ->
                new ObligationFamilyReviewDecision(
                    "canonical", "MATHEMATICALLY_EQUIVALENT", 1.0d, "forbidden"))
        .isInstanceOf(ContractValidationException.class);
  }
}
