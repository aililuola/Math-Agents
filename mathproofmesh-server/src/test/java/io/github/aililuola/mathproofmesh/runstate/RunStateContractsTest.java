package io.github.aililuola.mathproofmesh.runstate;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.json.JsonMapper;
import org.junit.jupiter.api.Test;

final class RunStateContractsTest {
  @Test
  void snapshotRoundTripsWithFiveIndependentDimensions() throws Exception {
    RunStateSnapshot state =
        RunStateServerTestSupport.state("contract-run", RunExecutionStatus.FAILED, true);
    var mapper = JsonMapper.builder().findAndAddModules().build();
    RunStateSnapshot restored = mapper.readValue(mapper.writeValueAsBytes(state), RunStateSnapshot.class);
    assertThat(restored).isEqualTo(state);
    assertThat(restored.authority().executionStatus()).isEqualTo(RunExecutionStatus.FAILED);
    assertThat(restored.authority().mathStatus()).isEqualTo(RunMathematicalStatus.PARTIAL_UNVERIFIED);
    assertThat(restored.authority().usageStatus()).isEqualTo(RunUsageStatus.RECORDED);
    assertThat(restored.authority().campaignStatus()).isEqualTo(RunCampaignStatus.RECOVERABLE);
  }
}
