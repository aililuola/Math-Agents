package io.github.aililuola.mathproofmesh.communication;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.contract.RouteStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

class RouteRegistryParityTest {
  @Test
  void capsAndDeduplicatesSparseNeighbors() {
    RouteRegistry routes =
        new RouteRegistry(CommunicationFixtures.PROBLEM_HASH, 1, 8, 0.9);
    routes.register(CommunicationFixtures.route("r0", "s0", "a"));
    routes.register(CommunicationFixtures.route("r1", "s1", "b"));
    routes.register(CommunicationFixtures.route("r2", "s2", "c"));
    routes.setNeighbors("r0", List.of("r1", "r2", "r1", "r0"));
    assertEquals(List.of("r1"), routes.neighbors("r0"));
  }

  @Test
  void membershipOwnershipIncludesRole() {
    RouteRegistry routes = CommunicationFixtures.routes();
    assertTrue(routes.ownsAgent("route-a", "author-a", RouteRole.PROVER));
    assertFalse(routes.ownsAgent("route-a", "author-a", RouteRole.REFEREE));
  }

  @Test
  void routeMemberLimitIsEnforced() {
    RouteRegistry routes =
        new RouteRegistry(CommunicationFixtures.PROBLEM_HASH, 1, 1, 0.9);
    routes.register(CommunicationFixtures.route("route-a", "s", "mechanism"));
    routes.assignMember("route-a", "first", RouteRole.PROVER, 0);
    assertThrows(
        IllegalStateException.class,
        () -> routes.assignMember("route-a", "second", RouteRole.REFEREE, 0));
  }

  @Test
  void mergedRouteRemainsAuditable() {
    RouteRegistry routes = CommunicationFixtures.routes();
    routes.merge("route-a", "route-b");
    assertEquals(RouteStatus.MERGED, routes.get("route-a").status());
    assertEquals("route-b", routes.get("route-a").mergedIntoRouteId());
    assertTrue(routes.get("route-b").mechanismSignature().contains("mechanism-a"));
  }

  @Test
  void counterexampleCoolingRequiresExplicitRevision() {
    RouteRegistry routes = CommunicationFixtures.routes();
    routes.markCooling("route-a", 4, true);
    assertThrows(
        IllegalArgumentException.class, () -> routes.reactivate("route-a", ""));
    routes.reactivate("route-a", "replace the refuted premise");
    assertEquals(RouteStatus.ACTIVE, routes.get("route-a").status());
  }
}
