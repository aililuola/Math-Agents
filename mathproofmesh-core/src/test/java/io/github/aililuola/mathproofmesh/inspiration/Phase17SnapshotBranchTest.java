package io.github.aililuola.mathproofmesh.inspiration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class Phase17SnapshotBranchTest {

  @Test
  void everyNegativeCounterAndRatioGuardFailsClosed() {
    assertInvalid(-1, 0, 0, 0, 0, 0, 0, 0);
    assertInvalid(0, -1, 0, 0, 0, 0, 0, 0);
    assertInvalid(0, 0, -1, 0, 0, 0, 0, 0);
    assertInvalid(0, 0, 0, -1, 0, 0, 0, 0);
    assertInvalid(0, 0, 0, 0, -1, 0, 0, 0);
    assertInvalid(0, 0, 0, 0, 0, -1, 0, 0);
    for (double redundancy : new double[] {-0.1, 1.1, Double.NaN}) {
      assertInvalid(0, 0, 0, 0, 0, 0, redundancy, 0);
    }
    assertInvalid(0, 0, 0, 0, 0, 0, 0, Double.NaN);
    assertThatThrownBy(() -> snapshot(" ", "domain", 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> snapshot(null, "domain", 0, 0, 0, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void nullCollectionsAndDomainFallbackAreCanonicalized() {
    InspirationSnapshot snapshot =
        new InspirationSnapshot(
            0,
            " hash ",
            null,
            null,
            null,
            null,
            0,
            null,
            0,
            null,
            null,
            0,
            null,
            1,
            2,
            0,
            0,
            null,
            null,
            false,
            false);
    assertThat(snapshot.problemHash()).isEqualTo("hash");
    assertThat(snapshot.domain()).isEqualTo("unknown");
    assertThat(snapshot.activeRouteIds()).isEmpty();
    assertThat(snapshot.proofDebtByRoute()).isEmpty();
    assertThat(snapshot.schedulableCalls()).isZero();
    assertThat(snapshot.pathCapacityAvailable()).isTrue();

    InspirationSnapshot stripped = snapshot("hash", " algebra ", 5, 2, 1, 2);
    assertThat(stripped.domain()).isEqualTo("algebra");
    assertThat(stripped.schedulableCalls()).isEqualTo(3);
    assertThat(stripped.pathCapacityAvailable()).isTrue();
    assertThat(snapshot("hash", "", 5, 2, 2, 2).pathCapacityAvailable()).isFalse();
  }

  private static void assertInvalid(
      int round,
      int gain,
      int remaining,
      int reserve,
      int currentPaths,
      int maxPaths,
      double redundancy,
      double debtReduction) {
    assertThatThrownBy(
            () ->
                new InspirationSnapshot(
                    round,
                    "hash",
                    "domain",
                    List.of(),
                    List.of(),
                    Map.of(),
                    gain,
                    Map.of(),
                    debtReduction,
                    List.of(),
                    List.of(),
                    redundancy,
                    List.of(),
                    remaining,
                    reserve,
                    currentPaths,
                    maxPaths,
                    List.of(),
                    Map.of(),
                    false,
                    false))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static InspirationSnapshot snapshot(
      String hash, String domain, int remaining, int reserve, int currentPaths, int maxPaths) {
    return new InspirationSnapshot(
        0,
        hash,
        domain,
        List.of("r"),
        List.of(),
        Map.of(),
        0,
        Map.of(),
        0,
        List.of(),
        List.of(),
        0,
        List.of(),
        remaining,
        reserve,
        currentPaths,
        maxPaths,
        List.of(),
        Map.of(),
        false,
        false);
  }
}
