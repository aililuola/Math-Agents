package io.github.aililuola.mathproofmesh.strategydiversity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate;
import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import io.github.aililuola.mathproofmesh.contract.MemoryTier;
import io.github.aililuola.mathproofmesh.contract.MessageEnvelope;
import io.github.aililuola.mathproofmesh.contract.MessageType;
import io.github.aililuola.mathproofmesh.contract.RouteRole;
import io.github.aililuola.mathproofmesh.memory.DeterministicNegativeSeed;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeAdmissionGate;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeRegistry;
import io.github.aililuola.mathproofmesh.memory.NegativeKnowledgeTargetType;
import io.github.aililuola.mathproofmesh.memory.VerifiedCounterexampleAuthority;
import java.util.List;
import org.junit.jupiter.api.Test;

class TrustedStrategyPreflightEvidenceSourceTest {
  private static final String PROBLEM = "trusted-preflight-problem";
  private static final String CLAIM = "Every route-specific bridge is valid.";

  @Test
  void exactVerifiedClaimAndFactProvideAuthoritativeSupport() {
    ClaimCard wrongStatus = claim("proposed", CLAIM, List.of(), ClaimStatus.PROPOSED);
    ClaimCard wrongStatement =
        claim("wrong-statement", "A different bridge is valid.", List.of(), ClaimStatus.VERIFIED);
    ClaimCard wrongAssumptions =
        claim("wrong-assumptions", CLAIM, List.of("extra assumption"), ClaimStatus.VERIFIED);
    ClaimCard verified = claim("verified-claim", CLAIM, List.of(), ClaimStatus.VERIFIED);
    MessageEnvelope wrongProblem = fact("wrong-problem", "another-problem", CLAIM, MemoryTier.FACT);
    MessageEnvelope wrongTier = fact("wrong-tier", PROBLEM, CLAIM, MemoryTier.INSIGHT);
    MessageEnvelope fact = fact("verified-fact", PROBLEM, CLAIM, MemoryTier.FACT);
    TrustedStrategyPreflightEvidenceSource source =
        source(
            new NegativeKnowledgeRegistry(),
            List.of(wrongStatus, wrongStatement, wrongAssumptions, verified),
            List.of(wrongProblem, wrongTier, fact));

    CriticalClaimPreflightEvidence evidence = source.evaluate(key(PROBLEM, CLAIM), spec(PROBLEM, CLAIM)).orElseThrow();

    assertThat(evidence.status()).isEqualTo(CriticalClaimPreflightStatus.VERIFIED_SUPPORTED);
    assertThat(evidence.evidenceRefs()).containsExactly("verified-claim", "verified-fact");
    assertThat(evidence.authority()).isEqualTo("verified-claim-memory");
  }

  @Test
  void deterministicAndPossibleNegativeKnowledgeRemainDistinct() {
    String canonical = "The entire sequence contains only finitely many prime divisors.";
    String alias = "The sequence has finite prime support.";
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    registry.registerDeterministicGuardrail(
        PROBLEM,
        DeterministicNegativeSeed.trustedCodeSeed(
            "finite-prime-support",
            NegativeKnowledgeTargetType.CLAIM,
            canonical,
            List.of(alias),
            List.of(),
            List.of(),
            List.of(),
            List.of(),
            "This shortcut is not justified."),
        0);
    TrustedStrategyPreflightEvidenceSource source = source(registry, null, null);

    CriticalClaimPreflightEvidence blocked =
        source.evaluate(key(PROBLEM, alias), spec(PROBLEM, alias)).orElseThrow();
    String possibleStatement =
        "The sequence has finite prime support under the proposed route argument.";
    CriticalClaimPreflightEvidence quarantined =
        source
            .evaluate(key(PROBLEM, possibleStatement), spec(PROBLEM, possibleStatement))
            .orElseThrow();

    assertThat(blocked.status()).isEqualTo(CriticalClaimPreflightStatus.PERMANENTLY_BLOCKED);
    assertThat(blocked.authority()).isEqualTo("deterministic-negative-knowledge");
    assertThat(quarantined.status()).isEqualTo(CriticalClaimPreflightStatus.ERROR);
    assertThat(quarantined.detail()).isEqualTo("POSSIBLE_EQUIVALENT_REQUIRES_TRUSTED_REVIEW");
  }

  @Test
  void verifiedCounterexampleRefutesOnlyItsBoundProblem() {
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    MessageEnvelope counterexample = counterexample("counterexample", CLAIM);
    registry.registerVerifiedCounterexample(
        counterexample,
        VerifiedCounterexampleAuthority.independentReplay(
            true,
            true,
            ComputationEvidenceGate.EvidenceAuthority.REFUTED,
            "experiment://counterexample",
            CLAIM,
            "result-counterexample",
            List.of()));
    TrustedStrategyPreflightEvidenceSource source = source(registry, List.of(), List.of());

    CriticalClaimPreflightEvidence evidence =
        source.evaluate(key(PROBLEM, CLAIM), spec(PROBLEM, CLAIM)).orElseThrow();
    CriticalClaimPreflightEvidence crossProblem =
        source
            .evaluate(
                key("different-problem", CLAIM), spec("different-problem", CLAIM))
            .orElseThrow();

    assertThat(evidence.status()).isEqualTo(CriticalClaimPreflightStatus.VERIFIED_REFUTED);
    assertThat(evidence.authority()).isEqualTo("verified-counterexample");
    assertThat(crossProblem.status()).isEqualTo(CriticalClaimPreflightStatus.ERROR);
    assertThat(crossProblem.detail()).isEqualTo("CROSS_PROBLEM_CLAIM");
  }

  @Test
  void absentAuthorityReturnsNoEvidenceAndNegativeRoundsAreRejected() {
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    TrustedStrategyPreflightEvidenceSource source = source(registry, null, null);

    assertThat(source.evaluate(key(PROBLEM, CLAIM), spec(PROBLEM, CLAIM))).isEmpty();
    assertThatThrownBy(
            () ->
                new TrustedStrategyPreflightEvidenceSource(
                    PROBLEM,
                    new NegativeKnowledgeAdmissionGate(registry),
                    null,
                    null,
                    -1))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static TrustedStrategyPreflightEvidenceSource source(
      NegativeKnowledgeRegistry registry,
      List<ClaimCard> claims,
      List<MessageEnvelope> facts) {
    return new TrustedStrategyPreflightEvidenceSource(
        PROBLEM, new NegativeKnowledgeAdmissionGate(registry), claims, facts, 0);
  }

  private static CriticalClaimSemanticKey key(String problemHash, String statement) {
    return new CriticalClaimKeyCompiler()
        .compile(problemHash, StrategyDiversityTestFixtures.claim("claim", statement, "required"));
  }

  private static CriticalClaimPreflightSpec spec(String problemHash, String statement) {
    io.github.aililuola.mathproofmesh.contract.CriticalClaim claim =
        StrategyDiversityTestFixtures.claim("claim", statement, "required");
    return new CriticalClaimPreflightSpec(
        problemHash,
        claim,
        new CriticalClaimKeyCompiler().compile(problemHash, claim),
        null,
        null);
  }

  private static ClaimCard claim(
      String id, String statement, List<String> assumptions, ClaimStatus status) {
    return new ClaimCard(
        assumptions,
        id,
        statement,
        "",
        "low",
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        List.of(),
        0.9d,
        "trusted-reviewer",
        "attempt",
        null,
        statement,
        status,
        List.of(),
        status == ClaimStatus.VERIFIED ? 1.0d : null);
  }

  private static MessageEnvelope fact(
      String id, String problemHash, String statement, MemoryTier tier) {
    return new MessageEnvelope(
        List.of(),
        List.of(),
        statement,
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.NATURAL_PROOF_AUDITED,
        tier,
        id,
        MessageType.VERIFIED_LEMMA,
        1.0d,
        statement,
        problemHash,
        List.of(),
        null,
        0,
        "1",
        List.of(),
        "trusted-reviewer",
        RouteRole.PROVER,
        "route",
        statement,
        List.of(),
        2,
        List.of(),
        1.0d,
        ClaimStatus.VERIFIED);
  }

  private static MessageEnvelope counterexample(String id, String statement) {
    return new MessageEnvelope(
        List.of("experiment://counterexample"),
        List.of(),
        statement,
        "",
        null,
        List.of(),
        List.of(),
        EvidenceType.COUNTEREXAMPLE,
        MemoryTier.NEGATIVE,
        id,
        MessageType.COUNTEREXAMPLE,
        1.0d,
        statement,
        PROBLEM,
        List.of(),
        "result-counterexample",
        0,
        "1",
        List.of(),
        "independent-computation-replay",
        RouteRole.SKEPTIC,
        "route",
        statement,
        List.of(),
        2,
        List.of(),
        1.0d,
        ClaimStatus.REJECTED);
  }
}
