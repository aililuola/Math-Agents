package io.github.aililuola.mathproofmesh.desktop;

/** Persisted orchestration boundaries exposed only for controlled validation and recovery. */
enum DesktopDurableBoundary {
  FIRST_RESULT_DURABLE,
  ALL_SETTLED,
  MERGE_PREPARED,
  CHECKPOINT_V22
}
