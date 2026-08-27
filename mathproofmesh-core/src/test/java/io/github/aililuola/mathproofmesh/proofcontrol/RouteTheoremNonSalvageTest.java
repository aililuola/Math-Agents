package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

final class RouteTheoremNonSalvageTest {

  @Test
  void routeTheoremExistsOnlyForCompleteValidatedVerifiedRoute() {
    var theorem =
        AttemptArtifactFixtures.claim("route-theorem", "The immutable main goal follows.", List.of());
    AttemptArtifactHarvester harvester = new AttemptArtifactHarvester();

    assertThat(
            harvester.harvestRouteTheorem(
                AttemptArtifactFixtures.PROBLEM_HASH, "route-a", "delta-a", "unverified",
                AttemptArtifactFixtures.attempt(AttemptStatus.COMPLETE), theorem, true, true))
        .isEmpty();
    assertThat(
            harvester.harvestRouteTheorem(
                AttemptArtifactFixtures.PROBLEM_HASH, "route-a", "delta-a", "verified",
                AttemptArtifactFixtures.attempt(AttemptStatus.PARTIAL), theorem, true, true))
        .isEmpty();
    assertThat(
            harvester.harvestRouteTheorem(
                AttemptArtifactFixtures.PROBLEM_HASH, "route-a", "delta-a", "verified",
                AttemptArtifactFixtures.attempt(AttemptStatus.COMPLETE), theorem, false, true))
        .isEmpty();
    assertThat(
            harvester.harvestRouteTheorem(
                AttemptArtifactFixtures.PROBLEM_HASH, "route-a", "delta-a", "verified",
                AttemptArtifactFixtures.attempt(AttemptStatus.COMPLETE), theorem, true, true))
        .hasValueSatisfying(
            record -> assertThat(record.kind()).isEqualTo(AttemptArtifactKind.ROUTE_THEOREM));
  }

  @Test
  void lifecycleAlsoRefusesRouteTheoremFromFailedAttempt() {
    ClaimLifecycleController lifecycle = new ClaimLifecycleController();
    lifecycle.register(
        "route-theorem", "attempt-a", "delta-a", List.of(),
        AttemptArtifactKind.ROUTE_THEOREM, AttemptStatus.FAILED, "unverified");
    lifecycle.recordLocalVerification("route-theorem", "local");
    lifecycle.recordIndependentVerification("route-theorem", "reviewer", "author", "review");
    lifecycle.recordRefereeAcceptance(
        "route-theorem", "reviewer", "author", "review", true, true, true, true);
    var proposal =
        lifecycle.proposePromotion(
            "route-theorem",
            new DependencyResolver.Resolution(true, List.of(), List.of(), List.of(), List.of()),
            true,
            List.of());
    assertThat(proposal.eligible()).isFalse();
    assertThat(proposal.reasons()).contains("route theorem requires a complete attempt and verified route");
  }
}
