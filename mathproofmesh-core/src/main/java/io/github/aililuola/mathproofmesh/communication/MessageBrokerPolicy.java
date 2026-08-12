package io.github.aililuola.mathproofmesh.communication;

public record MessageBrokerPolicy(
    String schemaVersion,
    int maxMessageChars,
    int maxAssumptions,
    int maxDependencies,
    int maxMessagesPerRoutePerRound,
    int maxGlobalMessagesPerRound,
    int maxNeighborsPerRoute,
    int initialIsolationRounds,
    double factPassThreshold,
    boolean crossRouteEnabled,
    boolean shareVerifiedFacts,
    boolean shareCounterexamples,
    boolean shareOpenObligations,
    boolean shareFailureRecords,
    boolean shareUnverifiedInsights) {

  public MessageBrokerPolicy {
    if (schemaVersion == null || schemaVersion.isBlank()) {
      throw new IllegalArgumentException("schemaVersion is required");
    }
    if (maxMessageChars < 1
        || maxAssumptions < 0
        || maxDependencies < 0
        || maxMessagesPerRoutePerRound < 1
        || maxGlobalMessagesPerRound < 1
        || maxNeighborsPerRoute < 0
        || initialIsolationRounds < 0) {
      throw new IllegalArgumentException("message broker limits are invalid");
    }
    if (factPassThreshold < 0.0 || factPassThreshold > 1.0) {
      throw new IllegalArgumentException("factPassThreshold must be between zero and one");
    }
  }

  public static MessageBrokerPolicy strictDefaults() {
    return new MessageBrokerPolicy(
        "1", 32_000, 64, 64, 8, 128, 3, 0, 0.9, true, true, true, true, true, false);
  }
}
