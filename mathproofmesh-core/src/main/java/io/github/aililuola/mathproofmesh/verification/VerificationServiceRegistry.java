package io.github.aililuola.mathproofmesh.verification;

/** Stable entry point for the phase-09 verification services. */
public record VerificationServiceRegistry(
    ValidationEscalator escalator,
    ValidationEscalationExecutor executor,
    FormalizationCandidateSelector formalSelector,
    CompilerFeedbackInterpreter compilerFeedback,
    ProofMutationHarness mutationHarness,
    BlindReviewPacketFactory blindPacketFactory) {

  public VerificationServiceRegistry {
    java.util.Objects.requireNonNull(escalator, "escalator");
    java.util.Objects.requireNonNull(executor, "executor");
    java.util.Objects.requireNonNull(formalSelector, "formalSelector");
    java.util.Objects.requireNonNull(compilerFeedback, "compilerFeedback");
    java.util.Objects.requireNonNull(mutationHarness, "mutationHarness");
    java.util.Objects.requireNonNull(blindPacketFactory, "blindPacketFactory");
  }

  public static VerificationServiceRegistry defaults() {
    return new VerificationServiceRegistry(
        new ValidationEscalator(ValidationEscalationPolicy.defaults()),
        new ValidationEscalationExecutor(),
        new FormalizationCandidateSelector(),
        new CompilerFeedbackInterpreter(),
        new ProofMutationHarness(),
        new BlindReviewPacketFactory());
  }
}
