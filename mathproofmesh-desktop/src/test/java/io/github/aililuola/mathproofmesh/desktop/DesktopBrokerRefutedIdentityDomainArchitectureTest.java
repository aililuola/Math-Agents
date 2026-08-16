package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class DesktopBrokerRefutedIdentityDomainArchitectureTest {
  @Test
  void promptBaselineAndEffectObservationUseTheSameRouteClaimIdProjection()
      throws IOException {
    String source = coordinatorSource();
    String consume =
        methodBody(
            source,
            "private BrokerArtifactPromptBatch consumeBrokerContext(RouteState route)",
            "private ComputationTrace runComputation(");
    String verify =
        methodBody(
            source,
            "private void verifyConsumedArtifactEffects(RouteState route)",
            "private List<AttemptArtifactRecord> harvestAttemptArtifacts(");
    String projection =
        methodBody(
            source,
            "private Set<String> refutedClaimIdsForRoute(RouteState route)",
            "private void verifyConsumedArtifactEffects(RouteState route)");

    assertThat(consume)
        .contains("Set<String> refutedClaims = refutedClaimIdsForRoute(route);")
        .doesNotContain("typedMemory.negatives()");
    assertThat(verify).contains("Set<String> refutedClaims = refutedClaimIdsForRoute(route);");
    assertThat(projection).contains("return Set.copyOf(route.rejectedClaimIds);");
  }

  private static String methodBody(String source, String startMarker, String endMarker) {
    int start = source.indexOf(startMarker);
    int end = source.indexOf(endMarker, start + startMarker.length());
    assertThat(start).isGreaterThanOrEqualTo(0);
    assertThat(end).isGreaterThan(start);
    return source.substring(start, end);
  }

  private static String coordinatorSource() throws IOException {
    return Files.readString(
        projectRoot()
            .resolve(
                "mathproofmesh-desktop/src/main/java/io/github/aililuola/mathproofmesh/desktop/DesktopSolveCoordinator.java"));
  }

  private static Path projectRoot() {
    Path current = Path.of("").toAbsolutePath().normalize();
    while (current != null) {
      if (Files.isRegularFile(current.resolve("config/proof-control-active.yaml"))) {
        return current;
      }
      current = current.getParent();
    }
    throw new IllegalStateException("project root was not found");
  }
}
