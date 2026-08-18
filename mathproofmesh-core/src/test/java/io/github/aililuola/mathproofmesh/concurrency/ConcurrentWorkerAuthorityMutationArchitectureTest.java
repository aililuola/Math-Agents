package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class ConcurrentWorkerAuthorityMutationArchitectureTest {
  @Test
  void executorContractExposesOnlyFrozenInputAndResultOutput() {
    assertThat(ResearchEpochExecutor.class.getDeclaredMethods()).hasSize(1);
    assertThat(ResearchEpochExecutor.class.getDeclaredMethods()[0].getParameterTypes())
        .containsExactly(FrozenResearchSnapshot.class, java.util.List.class);
  }
}
