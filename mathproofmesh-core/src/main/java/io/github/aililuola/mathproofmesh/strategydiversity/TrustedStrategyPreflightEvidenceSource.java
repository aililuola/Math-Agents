package io.github.aililuola.mathproofmesh.strategydiversity;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.memory.NegativeCandidateIntent;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeAdmissionGate;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeCandidate;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeDecision;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeDecisionCode;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeKind;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRecord;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeSurface;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeTargetType;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/** Read-only adapter over already-authoritative claims, facts, and negative knowledge. */
public final class TrustedStrategyPreflightEvidenceSource
    implements StrategyPreflightEvidenceSource {
  private final String problemHash;
  private final NegativeKnowledgeAdmissionGate negativeGate;
  private final List<ClaimCard> verifiedClaims;
  private final List<MessageEnvelope> verifiedFacts;
  private final int currentRound;
  private final CriticalClaimKeyCompiler keyCompiler = new CriticalClaimKeyCompiler();

  public TrustedStrategyPreflightEvidenceSource(
      String problemHash,
      NegativeKnowledgeAdmissionGate negativeGate,
      Collection<ClaimCard> verifiedClaims,
      Collection<MessageEnvelope> verifiedFacts,
      int currentRound) {
    this.problemHash = StrategySemanticNormalizer.require(problemHash, "problemHash");
    this.negativeGate = java.util.Objects.requireNonNull(negativeGate, "negativeGate");
    this.verifiedClaims = verifiedClaims == null ? List.of() : List.copyOf(verifiedClaims);
    this.verifiedFacts = verifiedFacts == null ? List.of() : List.copyOf(verifiedFacts);
    if (currentRound < 0) {
      throw new IllegalArgumentException("currentRound must be nonnegative");
    }
    this.currentRound = currentRound;
  }

  @Override
  public Optional<CriticalClaimPreflightEvidence> evaluate(
      CriticalClaimSemanticKey key, CriticalClaimPreflightSpec spec) {
    if (!problemHash.equals(key.problemHash()) || !problemHash.equals(spec.problemHash())) {
      return Optional.of(
          new CriticalClaimPreflightEvidence(
              CriticalClaimPreflightStatus.ERROR,
              "server-problem-binding",
              List.of(),
              "CROSS_PROBLEM_CLAIM"));
    }
    NegativeKnowledgeDecision negative =
        negativeGate.evaluate(
            new NegativeKnowledgeCandidate(
                problemHash,
                NegativeKnowledgeTargetType.CLAIM,
                spec.claim().statement(),
                key.normalizedStatement(),
                spec.context().assumptions(),
                spec.context().quantifiers(),
                spec.context().variableBindings(),
                spec.context().scopeLimitations(),
                spec.context().polarity(),
                NegativeKnowledgeSurface.STRATEGY_ADMISSION,
                NegativeCandidateIntent.POSITIVE_DEPENDENCY),
            currentRound);
    if (negative.code() == NegativeKnowledgeDecisionCode.QUARANTINE_POSSIBLE_EQUIVALENT) {
      return Optional.of(
          new CriticalClaimPreflightEvidence(
              CriticalClaimPreflightStatus.ERROR,
              "permanent-negative-knowledge",
              negative.matchedNegativeIds(),
              "POSSIBLE_EQUIVALENT_REQUIRES_TRUSTED_REVIEW"));
    }
    if (negative.code() == NegativeKnowledgeDecisionCode.BLOCK_PERMANENT) {
      Set<String> matched = Set.copyOf(negative.matchedNegativeIds());
      boolean verifiedCounterexample =
          negativeGate.registry().records().stream()
              .filter(record -> matched.contains(record.negativeId()))
              .map(NegativeKnowledgeRecord::kinds)
              .flatMap(Set::stream)
              .anyMatch(NegativeKnowledgeKind.VERIFIED_COUNTEREXAMPLE::equals);
      return Optional.of(
          new CriticalClaimPreflightEvidence(
              verifiedCounterexample
                  ? CriticalClaimPreflightStatus.VERIFIED_REFUTED
                  : CriticalClaimPreflightStatus.PERMANENTLY_BLOCKED,
              verifiedCounterexample
                  ? "verified-counterexample"
                  : "deterministic-negative-knowledge",
              negative.matchedNegativeIds(),
              negative.detail()));
    }

    LinkedHashSet<String> refs = new LinkedHashSet<>();
    verifiedClaims.stream()
        .filter(claim -> claim.status() == ClaimStatus.VERIFIED)
        .filter(
            claim ->
                keyCompiler.exactEvidenceMatch(
                    key,
                    claim.statement(),
                    new CriticalClaimContext(
                        claim.assumptions(),
                        List.of(),
                        claim.scopeLimitations(),
                        List.of(),
                        "positive")))
        .map(ClaimCard::claimId)
        .forEach(refs::add);
    verifiedFacts.stream()
        .filter(fact -> problemHash.equals(fact.problemHash()))
        .filter(fact -> fact.memoryTier() == MemoryTier.FACT)
        .filter(fact -> fact.verificationStatus() == ClaimStatus.VERIFIED)
        .filter(
            fact ->
                keyCompiler.exactEvidenceMatch(
                        key,
                        fact.statement(),
                        new CriticalClaimContext(
                            fact.assumptions(),
                            fact.quantifiers(),
                            fact.scopeLimitations(),
                            fact.variableBindings(),
                            "positive"))
                    || keyCompiler.exactEvidenceMatch(
                        key,
                        fact.normalizedStatement(),
                        new CriticalClaimContext(
                            fact.assumptions(),
                            fact.quantifiers(),
                            fact.scopeLimitations(),
                            fact.variableBindings(),
                            "positive")))
        .map(MessageEnvelope::messageId)
        .forEach(refs::add);
    if (!refs.isEmpty()) {
      return Optional.of(
          new CriticalClaimPreflightEvidence(
              CriticalClaimPreflightStatus.VERIFIED_SUPPORTED,
              "verified-claim-memory",
              List.copyOf(refs),
              "EXACT_VERIFIED_SUPPORT"));
    }
    return Optional.empty();
  }

}
