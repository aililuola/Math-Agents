package io.github.aililuola.mathproofmesh.desktop;

/** Persisted orchestration boundaries exposed only for controlled validation and recovery. */
enum DesktopDurableBoundary {
  FIRST_RESULT_DURABLE,
  ALL_SETTLED,
  MERGE_PREPARED,
  CHECKPOINT_V22;

  String researchCheckpointStage() {
    return switch (this) {
      case FIRST_RESULT_DURABLE -> "research_epoch_first_result_durable";
      case ALL_SETTLED -> "research_epoch_all_settled";
      case MERGE_PREPARED -> "research_epoch_merge_prepared";
      case CHECKPOINT_V22 -> throw new IllegalArgumentException("checkpoint boundary is internal");
    };
  }
}
