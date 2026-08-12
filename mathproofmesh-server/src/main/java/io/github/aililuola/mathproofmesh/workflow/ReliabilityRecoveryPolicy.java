package io.github.aililuola.mathproofmesh.workflow;

/** Separates transport reliability from mathematical trust and semantic failure. */
public final class ReliabilityRecoveryPolicy {
  public RecoveryDecision classify(
      FailureType failure, boolean structuredArtifactStarted, double currentAgentTrust) {
    if (!Double.isFinite(currentAgentTrust)
        || currentAgentTrust < 0.0d
        || currentAgentTrust > 1.0d) {
      throw new IllegalArgumentException("currentAgentTrust must be in [0,1]");
    }
    return switch (failure) {
      case AUTHENTICATION, SHARED_PROVIDER_CIRCUIT ->
          new RecoveryDecision("pause_run", false, false, currentAgentTrust);
      case TRANSPORT ->
          new RecoveryDecision("retry_or_failover", false, false, currentAgentTrust);
      case LENGTH_LIMIT, EMPTY_RESPONSE ->
          new RecoveryDecision("semantic_failover", false, false, currentAgentTrust);
      case SCHEMA ->
          new RecoveryDecision(
              "small_schema_repair", !structuredArtifactStarted, false, currentAgentTrust);
      case LOCAL_NORMALIZATION ->
          new RecoveryDecision("normalize_locally", false, true, currentAgentTrust);
      case MATHEMATICAL ->
          new RecoveryDecision(
              "record_math_failure",
              false,
              false,
              Math.max(0.0d, currentAgentTrust - 0.1d));
    };
  }

  public enum FailureType {
    AUTHENTICATION,
    SHARED_PROVIDER_CIRCUIT,
    TRANSPORT,
    LENGTH_LIMIT,
    EMPTY_RESPONSE,
    SCHEMA,
    LOCAL_NORMALIZATION,
    MATHEMATICAL
  }

  public record RecoveryDecision(
      String action,
      boolean smallRepairAllowed,
      boolean deepProviderCallAvoided,
      double resultingAgentTrust) {}
}
