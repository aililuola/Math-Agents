package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ComputationExecutionSnapshotTest {
  @Test
  void executionSnapshotRestoresStableFrontierAndQuota() {
    var first = ComputationFixtures.broker("snapshot-run");
    ComputationIssue010TestSupport.run(first, ComputationIssue010TestSupport.linearAlgebraSpec());
    var snapshot = first.executionService().snapshot();
    var restored = ComputationFixtures.broker("snapshot-run");
    restored.executionService().restore(snapshot);
    assertThat(restored.executionService().snapshot().executions().stableHash())
        .isEqualTo(snapshot.executions().stableHash());
    assertThat(restored.executionService().executions().usage("path-computation"))
        .isEqualTo(first.executionService().executions().usage("path-computation"));
  }
}
