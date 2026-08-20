package io.github.aililuola.mathproofmesh.desktop;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

final class NoEpochInternalCheckpointPersistArchitectureTest {
  @Test
  void stableCommitMethodsCannotWriteAFormalCheckpointPerResult() throws IOException {
    String source = coordinatorSource();
    String exploration =
        methodBody(
            source,
            "private void commitExplorationResultsInStableOrder(",
            "private ExplorationTurnDraft explorationDraft(");
    String claimCourt =
        methodBody(
            source,
            "private void commitClaimCourtResultsInStableOrder(",
            "private FrozenClaimSnapshot freezeClaimForCourt(");
    String routeReview =
        methodBody(
            source,
            "private void commitRouteReviewResultsInStableOrder(",
            "private static RouteState copyRouteState(");
    String projection =
        methodBody(
            source,
            "private void projectClaimCourtOutcome(",
            "private void persistAuthorityProjectionOutsideEpoch(");

    assertNoFormalPersist(exploration);
    assertNoFormalPersist(claimCourt);
    assertNoFormalPersist(routeReview);
    assertNoFormalPersist(projection);
    assertThat(projection).contains("persistAuthorityProjectionOutsideEpoch");
  }

  @Test
  void epochContextSuppressesNestedPersistersAndHasOneCommittedWrite() throws IOException {
    String source = coordinatorSource();
    String epoch =
        methodBody(
            source,
            "private AuthoritativeEpochRun executeAuthoritativeEpoch(",
            "private List<ResearchWorkItem> compileResearchWorkItems(");
    String persistenceGate =
        methodBody(
            source,
            "private synchronized void persistUnchecked(",
            "private Path statePath()");

    assertThat(epoch).contains("activeEpochAuthorityCommit.set");
    assertThat(epoch).containsOnlyOnce("persistUnchecked(\"research_epoch_committed\"");
    assertThat(persistenceGate).contains("activeEpochAuthorityCommit.get() != null");
  }

  private static void assertNoFormalPersist(String body) {
    assertThat(body).doesNotContain("persistUnchecked(");
    assertThat(body).doesNotContain("persist(");
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
