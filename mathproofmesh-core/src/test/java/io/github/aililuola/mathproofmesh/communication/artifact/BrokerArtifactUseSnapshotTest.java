package io.github.aililuola.mathproofmesh.communication.artifact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseClaim;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseKind;
import io.github.aililuola.mathproofmesh.contract.BrokerArtifactUseManifest;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BrokerArtifactUseSnapshotTest {
  @Test
  void manifestsAndLineageRestoreExactlyOnce() {
    var scenario = BrokerArtifactTestFixtures.delivered(2.0d);
    scenario
        .broker()
        .acknowledge(
            "provider-request-1",
            BrokerArtifactTestFixtures.useManifest(
                scenario.artifact(), BrokerArtifactUseKind.PREMISE_IN_PROOF_STEP),
            Set.of("downstream-step"));
    var snapshot = scenario.broker().useSnapshot();
    BrokerArtifactUseLedger restored = new BrokerArtifactUseLedger();
    restored.restore(snapshot);
    assertThat(CanonicalJson.stableHash(restored.snapshot()))
        .isEqualTo(CanonicalJson.stableHash(snapshot));
    assertThat(restored.records()).hasSize(1);
  }

  @Test
  void ledgerIsIdempotentButRejectsConflictingOrUnboundEffectUpdates() {
    BrokerArtifactUseLedger ledger = new BrokerArtifactUseLedger();
    BrokerArtifactUseClaim repairUse =
        new BrokerArtifactUseClaim(
            "artifact-1",
            BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
            List.of(),
            List.of("claim-1"),
            List.of("obligation-1"),
            "repair the exact obstruction");
    BrokerArtifactUseManifest manifest =
        new BrokerArtifactUseManifest("request-1", List.of(repairUse));

    assertThat(ledger.recordManifest(manifest)).isSameAs(manifest);
    assertThat(ledger.recordManifest(manifest)).isSameAs(manifest);
    assertThatThrownBy(
            () ->
                ledger.recordManifest(
                    new BrokerArtifactUseManifest(
                        "request-1",
                        List.of(
                            new BrokerArtifactUseClaim(
                                "artifact-2",
                                BrokerArtifactUseKind.TRIGGERS_LOCAL_REPAIR,
                                List.of(),
                                List.of("claim-2"),
                                List.of("obligation-2"),
                                "conflicting use")))))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("BROKER_USE_MANIFEST_ID_COLLISION");

    BrokerArtifactLineageRecord lineage =
        ledger.recordLineage("delivery-1", repairUse, "request-1");
    assertThat(ledger.recordLineage("delivery-1", repairUse, "request-1"))
        .isEqualTo(lineage);
    assertThatThrownBy(() -> ledger.bindEffectTarget("missing", "repair-1"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("UNKNOWN_BROKER_LINEAGE");
    assertThat(ledger.bindEffectTarget(lineage.lineageId(), "repair-1").repairId())
        .isEqualTo("repair-1");
    long boundVersion = ledger.snapshot().version();
    ledger.bindEffectTarget(lineage.lineageId(), "repair-1");
    assertThat(ledger.snapshot().version()).isEqualTo(boundVersion);

    ledger.markVerified(lineage.lineageId());
    long verifiedVersion = ledger.snapshot().version();
    ledger.markVerified(lineage.lineageId());
    ledger.markVerified("missing");
    assertThat(ledger.snapshot().version()).isEqualTo(verifiedVersion);

    BrokerArtifactLineageRecord ordinary =
        new BrokerArtifactLineageRecord(
            "ordinary-lineage",
            "artifact-2",
            "delivery-2",
            BrokerArtifactUseKind.SUPPORTS_CLAIM,
            List.of(),
            List.of("claim-2"),
            List.of(),
            null,
            null,
            null,
            "request-2",
            false);
    assertThatThrownBy(() -> ordinary.bindEffectTarget("unsupported"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("use kind has no effect target identity");

    ledger.restore(null);
    assertThat(ledger.records()).isEmpty();
    assertThat(ledger.manifest("request-1")).isEmpty();
  }
}
