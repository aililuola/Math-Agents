package io.github.aililuola.mathproofmesh.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

final class ConcurrencyInfrastructureNoProviderSpecificDependencyTest {
  @Test
  void productionPackageContainsNoProviderOrCredentialNames() throws Exception {
    assertThat(ConcurrencyInfrastructureNoProblemSpecificDependencyTest.readProductionSources())
        .doesNotContain("DeepSeek", "deepseek-v4-pro", "DEEPSEEK_API_KEY");
  }
}
