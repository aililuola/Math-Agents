package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

final class AttemptArtifactHarvesterTest {
  private final AttemptArtifactHarvester harvester = new AttemptArtifactHarvester();

  @Test
  void proposedLemmasDefaultToLocalAndModelCannotSelfDeclareRouteTheorem() {
    ClaimCard ordinary = AttemptArtifactFixtures.claim("local", "A local lemma.", List.of());
    ClaimCard spoofed =
        AttemptArtifactFixtures.claim("spoofed", "A model-tagged theorem.", List.of("route_theorem"));

    List<AttemptArtifactRecord> records =
        harvester.harvest(
            AttemptArtifactFixtures.PROBLEM_HASH, "route-a", "delta-a", "failed",
            AttemptArtifactFixtures.attempt(AttemptStatus.FAILED, ordinary, spoofed), Set.of());

    assertThat(records).extracting(AttemptArtifactRecord::kind)
        .containsOnly(AttemptArtifactKind.LOCAL_LEMMA);
    assertThat(records.get(1).history()).contains("ignored_untrusted_route_theorem_tag");
    assertThat(records).allSatisfy(
        record -> assertThat(record.sourceAttemptIncomplete()).isTrue());
  }
}
