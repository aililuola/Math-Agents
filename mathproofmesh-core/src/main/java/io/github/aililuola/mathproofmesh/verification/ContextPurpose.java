package io.github.aililuola.mathproofmesh.verification;

/** Purpose-specific context views prevent a broad packet from leaking into review. */
public enum ContextPurpose {
  INSPIRATION,
  DELTA_VERIFICATION,
  ATTEMPT_VERIFICATION,
  FINAL_VERIFICATION,
  SYNTHESIS,
  BLIND_REVIEW,
  FINAL_REVISION
}
