package io.github.aililuola.mathproofmesh.memory;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.ClaimCard;
import io.github.aililuola.mathproofmesh.contract.ClaimStatus;
import io.github.aililuola.mathproofmesh.contract.VerificationVerdict;
import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings("deprecation")
final class LemmaMemoryClaimScopedVerificationTest {

  @Test
  void attemptPassCannotWholesaleVerifyClaimsWithoutClaimScopedDecisions() {
    LemmaMemory memory = new LemmaMemory();
    ClaimCard first =
        MemoryFixtures.claim("claim-first", List.of(), ClaimStatus.PROPOSED, "attempt-a", null);
    ClaimCard second =
        MemoryFixtures.claim("claim-second", List.of(), ClaimStatus.PROPOSED, "attempt-a", null);
    memory.addMany(List.of(first, second));

    memory.markAttemptVerified(
        "attempt-a",
        MemoryFixtures.report("attempt-a", "attempt", VerificationVerdict.PASS, List.of()));

    assertThat(memory.claims())
        .extracting(ClaimCard::status)
        .containsOnly(ClaimStatus.PROPOSED);
  }
}
