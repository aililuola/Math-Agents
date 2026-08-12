package io.github.aililuola.mathproofmesh.memory;

import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;

public final class MemoryEnvelopeTransitions {
  private MemoryEnvelopeTransitions() {}

  public static MessageEnvelope toFact(
      MessageEnvelope source, double confidence) {
    return withTier(source, MemoryTier.FACT, ClaimStatus.VERIFIED, confidence);
  }

  public static MessageEnvelope toNegative(MessageEnvelope source) {
    return withTier(
        source,
        MemoryTier.NEGATIVE,
        ClaimStatus.REJECTED,
        source.verificationConfidence());
  }

  public static MessageEnvelope toInsight(MessageEnvelope source) {
    return withTier(
        source,
        MemoryTier.INSIGHT,
        ClaimStatus.UNCERTAIN,
        source.verificationConfidence());
  }

  private static MessageEnvelope withTier(
      MessageEnvelope source,
      MemoryTier tier,
      ClaimStatus status,
      double confidence) {
    return new MessageEnvelope(
        source.artifactRefs(),
        source.assumptions(),
        source.conclusion(),
        "",
        source.createdAt(),
        source.dependencies(),
        source.dependencyRefs(),
        source.evidenceType(),
        tier,
        source.messageId(),
        source.messageType(),
        source.normalizationConfidence(),
        source.normalizedStatement(),
        source.problemHash(),
        source.quantifiers(),
        source.rawSourceRef(),
        source.roundCreated(),
        source.schemaVersion(),
        source.scopeLimitations(),
        source.sourceAgentId(),
        source.sourceRole(),
        source.sourceRouteId(),
        source.statement(),
        source.targetRouteIds(),
        source.ttlRounds(),
        source.variableBindings(),
        confidence,
        status);
  }
}
