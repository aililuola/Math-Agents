package io.github.aililuola.mathproofmesh.communication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.RouteDescriptor;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.RouteStatus;
import io.github.aililuola.mathproofmesh.contract.StrategyCard;
import java.util.List;
import org.junit.jupiter.api.Test;

class Phase17RouteRegistryHardeningTest {

  @Test
  void constructorRegisterLookupAndMembershipBoundariesAreCovered() {
    for (String hash : new String[] {null, "", " "}) {
      assertThatThrownBy(() -> new RouteRegistry(hash, 1, 1, 0.5d))
          .isInstanceOf(IllegalArgumentException.class);
    }
    assertThatThrownBy(() -> new RouteRegistry("hash", -1, 1, 0.5d))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RouteRegistry("hash", 1, 0, 0.5d))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RouteRegistry("hash", 1, 1, -0.1d))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new RouteRegistry("hash", 1, 1, 1.1d))
        .isInstanceOf(IllegalArgumentException.class);

    RouteRegistry registry = new RouteRegistry(" hash ", 2, 1, 0.9d);
    assertThat(registry.problemHash()).isEqualTo("hash");
    assertThat(registry.routes()).isEmpty();
    assertThat(registry.exists("missing")).isFalse();
    assertThatThrownBy(() -> registry.get("missing")).isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry.register(null)).isInstanceOf(NullPointerException.class);
    RouteDescriptor first = CommunicationFixtures.route("r1", "s1", "alpha");
    assertThat(registry.register(first)).isEqualTo(first);
    assertThat(registry.register(first)).isEqualTo(first);
    assertThat(
            registry.register(CommunicationFixtures.route("different-route", "s1", "different")))
        .isEqualTo(first);
    assertThatThrownBy(
            () -> registry.register(CommunicationFixtures.route("r1", "other-strategy", "x")))
        .isInstanceOf(IllegalArgumentException.class);

    registry.assignMember("r1", "agent", RouteRole.PROVER, 0);
    registry.assignMember("r1", "agent", RouteRole.PROVER, 1);
    registry.assignMember("r1", "agent", RouteRole.REFEREE, 1);
    assertThat(registry.get("r1").members()).hasSize(2);
    assertThat(registry.ownsAgent("r1", "agent", null)).isTrue();
    assertThat(registry.ownsAgent("missing", "agent", null)).isFalse();
    assertThat(registry.ownsAgent("r1", "other", null)).isFalse();
    assertThatThrownBy(() -> registry.assignMember("r1", "other", RouteRole.PROVER, 2))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void strategyRegistrationCoversIndexExactSemanticAndMergedExclusion() {
    RouteRegistry registry = new RouteRegistry("hash", 2, 8, 0.0d);
    StrategyCard alpha =
        strategy("s-alpha", "Commutative Group", "reduce by parity", List.of("algebra", "parity"));
    RouteDescriptor alphaRoute = registry.registerStrategy(alpha);
    assertThat(registry.registerStrategy(alpha)).isEqualTo(alphaRoute);
    assertThat(registry.routeForStrategy("s-alpha")).contains(alphaRoute);

    StrategyCard exact =
        strategy("s-exact", "group commutative", "parity by reduce", List.of("parity", "algebra"));
    RouteDescriptor exactRoute = registry.registerStrategy(exact);
    assertThat(exactRoute).isEqualTo(alphaRoute);
    assertThat(registry.routeForStrategy("s-exact")).contains(alphaRoute);

    StrategyCard semantic =
        strategy("s-semantic", "Commutative group parity", "reduce parity", List.of("algebra"));
    RouteDescriptor semanticRoute = registry.registerStrategy(semantic);
    assertThat(semanticRoute).isEqualTo(alphaRoute);
    assertThat(registry.routeForStrategy("s-semantic")).contains(alphaRoute);
    assertThat(registry.routeForStrategy("missing")).isEmpty();
    assertThatThrownBy(() -> registry.registerStrategy(null)).isInstanceOf(NullPointerException.class);

    RouteDescriptor beta =
        registry.register(CommunicationFixtures.route("route-beta", "s-beta", "geometry"));
    registry.merge(alphaRoute.routeId(), beta.routeId());
    RouteDescriptor recreated =
        registry.registerStrategy(
            strategy("s-new", "Commutative group parity", "reduce parity", List.of("algebra")));
    assertThat(recreated.routeId()).isNotEqualTo(alphaRoute.routeId());
  }

  @Test
  void neighborsCoolingReactivationMergeAndAutomaticActivationCoverAllPredicates() {
    RouteRegistry registry = new RouteRegistry("hash", 2, 8, 0.0d);
    registry.register(CommunicationFixtures.route("a", "sa", ""));
    registry.register(CommunicationFixtures.route("b", "sb", ""));
    registry.register(CommunicationFixtures.route("c", "sc", "c"));
    registry.setNeighbors("a", List.of("a", "b", "b", "c", "missing"));
    assertThat(registry.neighbors("a")).containsExactly("b", "c");

    registry.markCooling("b", 5, false);
    assertThat(registry.neighbors("a")).doesNotContain("b");
    registry.activateCooledRoutes(4);
    assertThat(registry.get("b").status()).isEqualTo(RouteStatus.COOLING);
    registry.activateCooledRoutes(5);
    assertThat(registry.get("b").status()).isEqualTo(RouteStatus.ACTIVE);
    assertThat(registry.get("b").revisionSummary()).isEmpty();

    registry.markCooling("b", 6, true);
    registry.activateCooledRoutes(10);
    assertThat(registry.get("b").status()).isEqualTo(RouteStatus.COOLING);
    assertThatThrownBy(() -> registry.reactivate("b", null))
        .isInstanceOf(IllegalArgumentException.class);
    registry.reactivate("b", " revised ");
    assertThat(registry.get("b").revisionSummary()).isEqualTo("revised");
    registry.markCooling("c", 1, false);
    registry.reactivate("c", null);
    assertThat(registry.get("c").status()).isEqualTo(RouteStatus.ACTIVE);

    assertThatThrownBy(() -> registry.merge("a", "a"))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> registry.merge("missing", "a"))
        .isInstanceOf(IllegalArgumentException.class);
    registry.merge("a", "b");
    assertThat(registry.get("a").neighborRouteIds()).isEmpty();
    assertThat(registry.neighbors("b")).doesNotContain("a");
    registry.recomputeNeighbors();
    assertThat(registry.get("a").status()).isEqualTo(RouteStatus.MERGED);
  }

  private static StrategyCard strategy(
      String id, String title, String core, List<String> tags) {
    return new StrategyCard(
        null,
        "bottleneck",
        List.of(),
        List.of(),
        List.of(),
        core,
        List.of(),
        0.5d,
        0.5d,
        List.of("lemma"),
        "bounded falsification",
        "independent mechanism",
        null,
        null,
        List.of(),
        List.of("given"),
        id,
        tags,
        title);
  }
}
