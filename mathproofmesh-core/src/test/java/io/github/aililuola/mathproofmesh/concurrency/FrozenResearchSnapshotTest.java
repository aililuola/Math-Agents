package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;
import org.junit.jupiter.api.Test;

final class FrozenResearchSnapshotTest {
  @Test
  void suppliedHashMustMatchFrozenContent() {
    FrozenResearchSnapshot snapshot = ConcurrencyTestFixtures.snapshot();
    assertThatThrownBy(
            () -> new FrozenResearchSnapshot(
                snapshot.epochId(), snapshot.authority(), Map.of("changed", "input"), snapshot.snapshotHash()))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
