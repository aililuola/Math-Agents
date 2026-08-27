package io.github.aililuola.mathproofmesh.proofgraph;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.aililuola.mathproofmesh.contract.ContractObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DeferredExpansionSnapshotCompatibilityTest {
  @Test
  void missingV10LifecycleFieldsMigrateDeterministicallyToDeferred() {
    DeferredExpansionRecord current =
        new DeferredExpansionRecord(
            "legacy-id",
            "problem",
            7,
            "route",
            "obligation",
            "canonical",
            FocusedRecoveryActionType.NEW_STRATEGY,
            ObligationOccurrenceSchedulingState.DEFERRED_CAPACITY,
            "capacity",
            0);
    ObjectNode tree = (ObjectNode) ContractObjectMapper.toTree(current);
    for (String field :
        new String[] {
          "status",
          "lastEvaluatedRound",
          "reactivatedRound",
          "reactivationReason",
          "reactivatedTaskId",
          "retiredRound",
          "retirementReason"
        }) {
      tree.remove(field);
    }
    DeferredExpansionRecord migrated =
        ContractObjectMapper.read(tree, DeferredExpansionRecord.class);
    DeferredExpansionSnapshot restored =
        ContractObjectMapper.read(
            ContractObjectMapper.write(
                new DeferredExpansionSnapshot(Map.of(migrated.deferredId(), migrated), 1)),
            DeferredExpansionSnapshot.class);

    assertThat(migrated.status()).isEqualTo(DeferredExpansionStatus.DEFERRED);
    assertThat(migrated.lastEvaluatedRound()).isEqualTo(7);
    assertThat(migrated.reactivatedRound()).isEqualTo(-1);
    assertThat(migrated.retiredRound()).isEqualTo(-1);
    assertThat(restored.records()).containsEntry(migrated.deferredId(), migrated);
  }
}
