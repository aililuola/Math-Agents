package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class DesktopBrokerRelevantRouteTargetingTest {
  @Test
  void exactMathematicalNeedTargetsOnlyTheRelatedRoute() {
    DesktopBrokerArtifactFixture fixture = new DesktopBrokerArtifactFixture();
    var artifact = fixture.counterexample("hamiltonian-p4", "source-route");
    var result =
        fixture.broker.publish(
            artifact,
            List.of(
                fixture.related("related-route", "hamiltonian-p4"),
                fixture.unrelated("unrelated-route")),
            0,
            8);

    assertThat(result.deliveries())
        .extracting(delivery -> delivery.targetRouteId())
        .containsExactly("related-route");
    assertThat(result.relevanceDecisions())
        .filteredOn(decision -> decision.routeId().equals("unrelated-route"))
        .singleElement()
        .satisfies(decision -> assertThat(decision.relevant()).isFalse());
  }
}
