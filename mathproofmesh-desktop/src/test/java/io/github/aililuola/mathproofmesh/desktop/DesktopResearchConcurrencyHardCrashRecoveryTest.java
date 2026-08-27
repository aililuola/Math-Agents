package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.concurrency.ResearchTaskLedger;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkKind;
import io.github.aililuola.mathproofmesh.concurrency.ResearchWorkStatus;
import org.junit.jupiter.api.Test;

final class DesktopResearchConcurrencyHardCrashRecoveryTest {
  @Test
  void restoreQuarantinesAnUnacknowledgedProviderCallInsteadOfReplayingIt() {
    var snapshot = DesktopResearchConcurrencyTestSupport.snapshot("uncertain-call-epoch");
    var item =
        DesktopResearchConcurrencyTestSupport.items(
                snapshot, ResearchWorkKind.ROUTE_REVIEW, 1)
            .getFirst();
    ResearchTaskLedger before = new ResearchTaskLedger();
    before.plan(item);
    before.transition(
        item.workItemId(),
        ResearchWorkStatus.RUNNING,
        "agent-0",
        "provider-intent-" + item.workItemId(),
        "",
        "");

    ResearchTaskLedger restored = new ResearchTaskLedger();
    restored.restore(before.snapshot());
    assertThat(restored.require(item.workItemId()).status())
        .isEqualTo(ResearchWorkStatus.QUARANTINED_UNCERTAIN_CALL);
  }
}
