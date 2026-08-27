package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class CounterexampleArtifactTargetingTest {

  @Test
  void counterexampleRequiresExactlyOneExistingTargetAndCheckedWitness() {
    var valid = AttemptArtifactFixtures.claim(
        "valid-counter", "n=2 refutes the claim.",
        List.of("artifact:counterexample", "counterexample-target:obligation-exact"));
    var missing = AttemptArtifactFixtures.claim(
        "missing-counter", "No target.", List.of("artifact:counterexample"));
    var unknown = AttemptArtifactFixtures.claim(
        "unknown-counter", "Unknown target.",
        List.of("artifact:counterexample", "counterexample-target:missing"));
    AttemptArtifactHarvester harvester = new AttemptArtifactHarvester();
    List<AttemptArtifactRecord> records =
        harvester.harvest(
            AttemptArtifactFixtures.PROBLEM_HASH, "route-a", "delta-a", "failed",
            AttemptArtifactFixtures.attempt(AttemptStatus.FAILED, valid, missing, unknown),
            Set.of("obligation-exact"));

    assertThat(records).filteredOn(record -> record.claimId().equals("valid-counter"))
        .singleElement()
        .satisfies(record -> {
          assertThat(record.kind()).isEqualTo(AttemptArtifactKind.COUNTEREXAMPLE);
          assertThat(record.targetObligationId()).isEqualTo("obligation-exact");
          assertThat(record.status()).isEqualTo(AttemptArtifactStatus.HARVESTED);
        });
    assertThat(records).filteredOn(record -> !record.claimId().equals("valid-counter"))
        .allSatisfy(record -> assertThat(record.status()).isEqualTo(AttemptArtifactStatus.UNCERTAIN));

    AttemptArtifactLedger ledger = new AttemptArtifactLedger();
    ledger.addAll(List.of(records.getFirst()));
    ledger.markReviewPending("attempt-a");
    ledger.applyReviewBatch(
        AttemptArtifactFixtures.batch(
            "unchecked-witness",
            AttemptArtifactFixtures.decision("valid-counter", VerificationVerdict.PASS, false)),
        0.8d);
    assertThat(ledger.records()).singleElement()
        .satisfies(record -> assertThat(record.status()).isEqualTo(AttemptArtifactStatus.UNCERTAIN));
  }
}
