package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ComputationQuotaRestoreTest {
  @Test
  void restoredLedgerDoesNotResetExperimentOrCpuUsage() {
    var before = ComputationFixtures.broker("quota-run");
    ComputationIssue010TestSupport.run(before, ComputationIssue010TestSupport.linearAlgebraSpec());
    ComputationIssue010TestSupport.run(before, ComputationIssue010TestSupport.finiteMapSpec());
    var usage = before.executionService().executions().usage("path-computation");
    var after = ComputationFixtures.broker("quota-run");
    after.executionService().restore(before.executionService().snapshot());
    assertThat(after.executionService().executions().usage("path-computation")).isEqualTo(usage);
    assertThat(usage.experiments()).isEqualTo(2);
  }
}
