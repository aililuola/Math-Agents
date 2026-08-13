package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.github.aililuola.mathproofmesh.computation.ComputationEvidenceGate;
import io.github.aililuola.mathproofmesh.contract.EvidenceType;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class NegativeKnowledgeRegistryRobustnessTest {
  @Test
  void validatesTemporaryLifetimeEvidenceAndCurrentRound() {
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    NegativeKnowledgeCandidate candidate = candidate("A temporary rejection.");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> registry.registerTemporaryRejection(candidate, "evidence", -1, 2));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> registry.registerTemporaryRejection(candidate, "evidence", 0, 0));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> registry.registerTemporaryRejection(candidate, " ", 0, 2));
    assertThatIllegalArgumentException().isThrownBy(() -> registry.decide(candidate, -1));

    assertThat(NegativeKnowledgeRegistry.restore(null).records()).isEmpty();
  }

  @Test
  void mergesTrustedAliasesAndKeepsTheLongestTemporaryLifetime() {
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();
    NegativeKnowledgeRecord seeded =
        registry.registerDeterministicGuardrail(
            NegativeKnowledgeFixtures.PROBLEM_A,
            NegativeKnowledgeFixtures.deterministicSeed(),
            3);

    NegativeKnowledgeRecord aliasDuplicate =
        registry.registerTemporaryRejection(
            candidate(NegativeKnowledgeFixtures.FINITE_PRIMES), "alias-evidence", 5, 2);

    assertThat(aliasDuplicate.negativeId()).isEqualTo(seeded.negativeId());
    assertThat(aliasDuplicate.permanent()).isTrue();
    assertThat(aliasDuplicate.expiresAfterRound()).isNull();
    assertThat(aliasDuplicate.trustedAliasStatements())
        .contains(NegativeKnowledgeFixtures.FINITE_PRIMES);

    NegativeKnowledgeCandidate temporary = candidate("A different temporary rejection.");
    NegativeKnowledgeRecord first =
        registry.registerTemporaryRejection(temporary, "temporary-1", 2, 2);
    NegativeKnowledgeRecord extended =
        registry.registerTemporaryRejection(temporary, "temporary-2", 3, 5);

    assertThat(extended.negativeId()).isEqualTo(first.negativeId());
    assertThat(extended.expiresAfterRound()).isEqualTo(8);
    assertThat(extended.evidenceMessageIds()).containsExactly("temporary-1", "temporary-2");
  }

  @Test
  void rejectsTrustedAuthorityWhenAnyReplayBindingIsMalformed() {
    var counterexample =
        NegativeKnowledgeFixtures.counterexample("authority-target", "The target is false.");
    NegativeKnowledgeRegistry registry = new NegativeKnowledgeRegistry();

    assertRejected(
        registry,
        counterexample,
        authority(false, true, "experiment://authority-target", "The target is false.",
            "result-hash-authority-target"));
    assertRejected(
        registry,
        counterexample,
        authority(true, false, "experiment://authority-target", "The target is false.",
            "result-hash-authority-target"));
    assertRejected(
        registry,
        counterexample,
        VerifiedCounterexampleAuthority.independentReplay(
            true,
            true,
            ComputationEvidenceGate.EvidenceAuthority.VERIFIED,
            "experiment://authority-target",
            "The target is false.",
            "result-hash-authority-target",
            List.of()));
    assertRejected(
        registry,
        counterexample,
        authority(true, true, "artifact-without-scheme", "The target is false.",
            "result-hash-authority-target"));
    assertRejected(
        registry,
        counterexample,
        authority(true, true, "experiment://different", "The target is false.",
            "result-hash-authority-target"));
    assertRejected(
        registry,
        counterexample,
        authority(true, true, "experiment://authority-target", "The target is false.",
            "different-raw-result"));
    assertRejected(
        registry,
        counterexample,
        authority(true, true, "experiment://authority-target", "A different target is false.",
            "result-hash-authority-target"));

    var wrongEvidence =
        NegativeKnowledgeFixtures.negative(
            "wrong-evidence",
            NegativeKnowledgeFixtures.PROBLEM_A,
            "The target is false.",
            0,
            2,
            "independent-computation-replay",
            EvidenceType.UNVERIFIED_IDEA,
            List.of("experiment://wrong-evidence"),
            "result-hash-wrong-evidence",
            List.of(),
            List.of(),
            List.of(),
            NegativeKnowledgeFixtures.GREEDY_SCOPE);
    assertRejected(
        registry,
        wrongEvidence,
        authority(true, true, "experiment://wrong-evidence", "The target is false.",
            "result-hash-wrong-evidence"));

    assertThat(registry.records()).isEmpty();
  }

  @Test
  void recordValidationPreservesPermanentAndTemporaryLifetimeSemantics() {
    NegativeKnowledgeCandidate candidate = candidate("Record validation target.");

    assertThatIllegalArgumentException()
        .isThrownBy(() -> record(candidate, null, 0, 2, 1));
    assertThatIllegalArgumentException()
        .isThrownBy(() -> record(candidate, Set.of(), 0, 2, 1));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                record(
                    candidate,
                    Set.of(NegativeKnowledgeKind.DETERMINISTIC_GUARDRAIL),
                    0,
                    2,
                    1));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                record(
                    candidate,
                    Set.of(NegativeKnowledgeKind.TEMPORARY_HYPOTHESIS_REJECTION),
                    0,
                    null,
                    1));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                record(
                    candidate,
                    Set.of(NegativeKnowledgeKind.TEMPORARY_HYPOTHESIS_REJECTION),
                    2,
                    1,
                    1));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                record(
                    candidate,
                    Set.of(NegativeKnowledgeKind.TEMPORARY_HYPOTHESIS_REJECTION),
                    -1,
                    2,
                    1));
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                record(
                    candidate,
                    Set.of(NegativeKnowledgeKind.TEMPORARY_HYPOTHESIS_REJECTION),
                    0,
                    2,
                    0));

    NegativeKnowledgeRecord temporary =
        record(
            candidate,
            Set.of(NegativeKnowledgeKind.TEMPORARY_HYPOTHESIS_REJECTION),
            0,
            2,
            1);
    assertThat(temporary.activeAt(2)).isTrue();
    assertThat(temporary.activeAt(3)).isFalse();
    assertThatIllegalArgumentException().isThrownBy(() -> temporary.activeAt(-1));
  }

  private static NegativeKnowledgeCandidate candidate(String statement) {
    return NegativeKnowledgeFixtures.candidate(
        NegativeKnowledgeFixtures.PROBLEM_A,
        statement,
        NegativeKnowledgeTargetType.CLAIM,
        NegativeKnowledgeSurface.FACT_PROMOTION,
        NegativeCandidateIntent.FACT_PROMOTION);
  }

  private static VerifiedCounterexampleAuthority authority(
      boolean resultPresent,
      boolean replayValid,
      String artifact,
      String target,
      String rawSourceRef) {
    return VerifiedCounterexampleAuthority.independentReplay(
        resultPresent,
        replayValid,
        ComputationEvidenceGate.EvidenceAuthority.REFUTED,
        artifact,
        target,
        rawSourceRef,
        List.of());
  }

  private static void assertRejected(
      NegativeKnowledgeRegistry registry,
      io.github.aililuola.mathproofmesh.contract.MessageEnvelope counterexample,
      VerifiedCounterexampleAuthority authority) {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> registry.registerVerifiedCounterexample(counterexample, authority));
  }

  private static NegativeKnowledgeRecord record(
      NegativeKnowledgeCandidate candidate,
      Set<NegativeKnowledgeKind> kinds,
      int firstSeenRound,
      Integer expiresAfterRound,
      long version) {
    return new NegativeKnowledgeRecord(
        "negative-test",
        candidate.problemHash(),
        candidate.targetType(),
        candidate.semanticKey(),
        null,
        null,
        kinds,
        candidate.statement(),
        candidate.normalizedStatement(),
        null,
        null,
        null,
        null,
        null,
        firstSeenRound,
        expiresAfterRound,
        version);
  }
}
