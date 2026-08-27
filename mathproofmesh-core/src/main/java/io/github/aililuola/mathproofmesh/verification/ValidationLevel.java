package io.github.aililuola.mathproofmesh.verification;

/** Ordered, deterministic-first validation ladder. */
public enum ValidationLevel {
  DETERMINISTIC,
  BLIND_SAME_MODEL,
  ADVERSARIAL_BLIND,
  CROSS_PROVIDER,
  TOOL_OR_FORMAL
}
