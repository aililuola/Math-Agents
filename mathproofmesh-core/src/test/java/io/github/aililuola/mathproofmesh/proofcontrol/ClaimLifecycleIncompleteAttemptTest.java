package io.github.aililuola.mathproofmesh.proofcontrol;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.aililuola.mathproofmesh.contract.AttemptStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

final class ClaimLifecycleIncompleteAttemptTest {

  @Test
  void incompleteAttemptDoesNotBlockAnIndependentlyVerifiedLocalLemma() {
    ClaimLifecycleController lifecycle = new ClaimLifecycleController();
    lifecycle.register(
        "local", "attempt-a", "delta-a", List.of(), AttemptArtifactKind.LOCAL_LEMMA,
        AttemptStatus.FAILED, "unverified");
    lifecycle.recordLocalVerification("local", "author-proof");
    lifecycle.recordIndependentVerification("local", "reviewer", "author", "review-a");
    lifecycle.recordRefereeAcceptance(
        "local", "reviewer", "author", "review-a", true, true, true, true);

    ClaimLifecycleController.PromotionProposal proposal =
        lifecycle.proposePromotion(
            "local", new DependencyResolver.Resolution(true, List.of(), List.of(), List.of(), List.of()),
            true, List.of());

    assertThat(proposal.eligible()).isTrue();
    assertThat(lifecycle.get("local").sourceAttemptIncomplete()).isTrue();
    assertThat(lifecycle.get("local").claimKind()).isEqualTo(AttemptArtifactKind.LOCAL_LEMMA);
  }

  @Test
  void duplicateEvidenceCannotRegressAnAlreadyAdmittedSemanticClaim() {
    ClaimLifecycleController lifecycle = new ClaimLifecycleController();
    lifecycle.register(
        "local",
        "attempt-a",
        "delta-a",
        List.of(),
        AttemptArtifactKind.LOCAL_LEMMA,
        AttemptStatus.FAILED,
        "unverified");
    lifecycle.recordLocalVerification("local", "author-proof-a");
    lifecycle.recordIndependentVerification("local", "reviewer-a", "author", "review-a");
    lifecycle.recordRefereeAcceptance(
        "local", "reviewer-a", "author", "review-a", true, true, true, true);
    DependencyResolver.Resolution resolved =
        new DependencyResolver.Resolution(true, List.of(), List.of(), List.of(), List.of());
    lifecycle.proposePromotion("local", resolved, true, List.of());
    lifecycle.observeExternalAdmission("local", "fact-a");

    lifecycle.recordLocalVerification("local", "author-proof-b");
    lifecycle.recordIndependentVerification("local", "reviewer-b", "author", "review-b");
    lifecycle.recordRefereeAcceptance(
        "local", "reviewer-b", "author", "review-b", true, true, true, true);
    assertThat(lifecycle.proposePromotion("local", resolved, true, List.of()).eligible()).isTrue();
    lifecycle.observeExternalAdmission("local", "fact-a");

    assertThat(lifecycle.get("local").state())
        .isEqualTo(ClaimLifecycleController.State.EXTERNALLY_ADMITTED_FACT);
  }
}
