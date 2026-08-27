package io.github.aililuola.mathproofmesh.contract;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public final class TaskContractsFunctions {
  private static final List<String> PROOF_MARKERS =
      List.of("\u8bc1\u660e", "\u6c42\u8bc1", "prove", "show that", "demonstrate that");
  private static final List<String> DEFERRED_PROOF_MARKERS =
      List.of(
          "\u53e6\u884c\u8bc1\u660e",
          "\u65e0\u9700\u8bc1\u660e",
          "\u4e0d\u8981\u6c42\u8bc1\u660e",
          "\u53ea\u63d0\u51fa\u731c\u60f3",
          "\u5c1a\u4e0d\u8bc1\u660e",
          "must be proved separately",
          "separate proof obligation",
          "without proving",
          "no proof required");
  private static final List<String> SOLUTION_MARKERS =
      List.of(
          "\u6c42\u89e3",
          "\u89e3\u65b9\u7a0b",
          "\u89e3\u4e0d\u7b49\u5f0f",
          "\u6240\u6709\u89e3",
          "\u5168\u90e8\u89e3",
          "solve",
          "find all solutions",
          "determine all solutions");
  private static final List<String> COMPUTATION_MARKERS =
      List.of(
          "\u8ba1\u7b97",
          "\u6c42\u503c",
          "\u6570\u503c\u8fd1\u4f3c",
          "\u5217\u51fa",
          "\u524d\u51e0\u9879",
          "\u5b9a\u5411\u8ba1\u7b97",
          "compute",
          "calculate",
          "evaluate",
          "numerical approximation",
          "first terms");
  private static final List<String> CONJECTURE_MARKERS =
      List.of(
          "\u731c\u60f3",
          "\u5019\u9009\u89c4\u5f8b",
          "\u63d0\u51fa\u89c4\u5f8b",
          "conjecture",
          "hypothesis",
          "candidate pattern");
  private static final List<String> COUNTEREXAMPLE_MARKERS =
      List.of("\u53cd\u4f8b", "\u8bc1\u4f2a", "counterexample", "falsify", "disprove by example");
  private static final List<String> CLASSIFICATION_MARKERS =
      List.of("\u5206\u7c7b", "\u5206\u60c5\u51b5\u5217\u51fa", "classify", "classification");
  private static final List<String> OPTIMIZATION_MARKERS =
      List.of(
          "\u6700\u5927\u503c",
          "\u6700\u5c0f\u503c",
          "\u6781\u503c",
          "\u6700\u4f18",
          "maximum",
          "minimum",
          "extremum",
          "optimal",
          "optimize");
  private static final List<String> CONSTRUCTION_MARKERS =
      List.of(
          "\u6784\u9020",
          "\u7ed9\u51fa\u4e00\u4e2a\u4f8b\u5b50",
          "construct",
          "construction",
          "exhibit");
  private static final Pattern NUMBERED_TERMS =
      Pattern.compile("(?:\u524d|first)\\s*\\d+\\s*(?:\u9879|terms?)");

  private TaskContractsFunctions() {}

  public static List<TaskRequirement> inferTaskRequirements(String statement) {
    return inferTaskRequirements(statement, ProblemKind.UNKNOWN);
  }

  public static List<TaskRequirement> inferTaskRequirements(
      String statement, ProblemKind problemKind) {
    String text =
        ContractStrings.required("statement", statement)
            .toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ")
            .strip();
    List<TaskRequirement> requirements = new ArrayList<>();
    boolean proofDeferred = containsAny(text, DEFERRED_PROOF_MARKERS);
    addIf(text, SOLUTION_MARKERS, TaskRequirement.SOLUTION, requirements);
    if (containsAny(text, COMPUTATION_MARKERS) || NUMBERED_TERMS.matcher(text).find()) {
      add(requirements, TaskRequirement.COMPUTATION);
    }
    addIf(text, CONJECTURE_MARKERS, TaskRequirement.CONJECTURE, requirements);
    addIf(text, COUNTEREXAMPLE_MARKERS, TaskRequirement.COUNTEREXAMPLE, requirements);
    addIf(text, CLASSIFICATION_MARKERS, TaskRequirement.CLASSIFICATION, requirements);
    addIf(text, OPTIMIZATION_MARKERS, TaskRequirement.OPTIMIZATION, requirements);
    addIf(text, CONSTRUCTION_MARKERS, TaskRequirement.CONSTRUCTION, requirements);
    if (!proofDeferred && containsAny(text, PROOF_MARKERS)) {
      add(requirements, TaskRequirement.PROOF);
    }
    if (!requirements.isEmpty()) {
      return List.copyOf(requirements);
    }
    return List.of(
        switch (problemKind) {
          case CALCULATION -> TaskRequirement.COMPUTATION;
          case OPTIMIZATION -> TaskRequirement.OPTIMIZATION;
          case CONSTRUCTION -> TaskRequirement.CONSTRUCTION;
          case RESEARCH -> TaskRequirement.RESEARCH_PROGRESS;
          case PROOF, LOGIC, MIXED, UNKNOWN -> TaskRequirement.PROOF;
        });
  }

  public static List<String> deliverableInstructions(List<TaskRequirement> requirements) {
    return requirements.stream().map(TaskContractsFunctions::instruction).toList();
  }

  public static ProblemContract applyTaskContract(ProblemContract problem) {
    return applyTaskContract(problem, null);
  }

  public static ProblemContract applyTaskContract(ProblemContract problem, TriageResult triage) {
    ProblemKind kind = triage == null ? problem.problemKind() : triage.problemKind();
    List<TaskRequirement> inferred = inferTaskRequirements(problem.exactStatement(), kind);
    if (inferred.equals(List.of(TaskRequirement.PROOF))
        && triage != null
        && !triage.taskRequirements().isEmpty()
        && !containsAny(problem.exactStatement().toLowerCase(Locale.ROOT), PROOF_MARKERS)) {
      inferred = distinct(triage.taskRequirements());
    }
    return problem.withTaskContract(inferred, deliverableInstructions(inferred));
  }

  public static TaskAssessment assessTaskDeliverables(
      ProblemContract problem,
      AssessmentState state,
      List<ExperimentEvidence> experiments,
      double verificationThreshold) {
    VerificationReport verification = state.finalVerification();
    boolean proofVerified =
        verification != null
            && verification.verdict() == VerificationVerdict.PASS
            && verification.confidence() >= verificationThreshold;
    List<CandidateConjecture> candidates =
        state.attempts().stream()
            .flatMap(attempt -> attempt.candidateConjectures().stream())
            .toList();
    boolean submittedAnswer =
        state.attempts().stream().anyMatch(attempt -> attempt.finalAnswer() != null);
    Set<ExperimentOutcome> successfulOutcomes =
        EnumSet.of(
            ExperimentOutcome.NOT_REFUTED,
            ExperimentOutcome.CERTIFIED,
            ExperimentOutcome.COUNTEREXAMPLE_FOUND);
    List<ExperimentEvidence> successful =
        experiments.stream()
            .filter(item -> item.error() == null && successfulOutcomes.contains(item.outcome()))
            .toList();
    List<ExperimentEvidence> checkedCounterexamples =
        successful.stream()
            .filter(item -> item.outcome() == ExperimentOutcome.COUNTEREXAMPLE_FOUND)
            .filter(ExperimentEvidence::independentlyVerified)
            .toList();

    List<DeliverableAssessment> assessments = new ArrayList<>();
    Set<TaskRequirement> proofBacked =
        EnumSet.of(
            TaskRequirement.PROOF,
            TaskRequirement.SOLUTION,
            TaskRequirement.CLASSIFICATION,
            TaskRequirement.OPTIMIZATION,
            TaskRequirement.CONSTRUCTION);
    for (TaskRequirement requirement : problem.taskRequirements()) {
      boolean complete;
      List<String> evidenceIds;
      String summary;
      if (proofBacked.contains(requirement)) {
        complete = proofVerified;
        evidenceIds =
            complete && state.finalProof() != null
                ? state.finalProof().sourceAttemptIds()
                : List.of();
        summary =
            complete
                ? "The requested result passed the configured independent verification."
                : "The requested result still requires a passing final audit.";
      } else if (requirement == TaskRequirement.COMPUTATION) {
        boolean interpreted =
            submittedAnswer
                || (problem.taskRequirements().contains(TaskRequirement.CONJECTURE)
                    && !candidates.isEmpty());
        complete = (!successful.isEmpty() && interpreted) || proofVerified;
        evidenceIds = successful.stream().map(ExperimentEvidence::evidenceId).toList();
        summary =
            complete
                ? "The requested bounded computation completed with auditable evidence."
                : "No successful auditable computation result was produced.";
      } else if (requirement == TaskRequirement.CONJECTURE) {
        complete = !candidates.isEmpty();
        evidenceIds = candidates.stream().map(CandidateConjecture::conjectureId).toList();
        summary =
            complete
                ? "A scoped candidate conjecture and separate proof obligation were produced."
                : "No auditable candidate conjecture was produced.";
      } else if (requirement == TaskRequirement.COUNTEREXAMPLE) {
        complete = !checkedCounterexamples.isEmpty() || proofVerified;
        evidenceIds =
            checkedCounterexamples.stream().map(ExperimentEvidence::evidenceId).toList();
        summary =
            complete
                ? "An independently checked counterexample was produced."
                : "No independently checked counterexample was produced.";
      } else {
        complete = state.researchProgressReport() != null;
        evidenceIds = List.of();
        summary =
            complete
                ? "An auditable research-progress report was produced."
                : "No research-progress report was produced.";
      }
      assessments.add(
          new DeliverableAssessment(
              evidenceIds,
              requirement,
              complete ? DeliverableStatus.COMPLETED : DeliverableStatus.MISSING,
              summary));
    }
    long completed =
        assessments.stream()
            .filter(item -> item.status() == DeliverableStatus.COMPLETED)
            .count();
    TaskStatus status =
        !assessments.isEmpty() && completed == assessments.size()
            ? TaskStatus.COMPLETED
            : completed > 0 ? TaskStatus.PARTIAL : TaskStatus.INCOMPLETE;
    return new TaskAssessment(status, assessments);
  }

  public record AssessmentState(
      VerificationReport finalVerification,
      FinalProof finalProof,
      List<ProofAttempt> attempts,
      ResearchProgressReport researchProgressReport) {
    public AssessmentState {
      attempts = ImmutableCollections.listOrEmpty(attempts);
    }

    public List<ProofAttempt> attempts() {
      return List.copyOf(attempts);
    }
  }

  public record ExperimentEvidence(
      String experimentId,
      String requestHash,
      ExperimentOutcome outcome,
      String error,
      boolean independentlyVerified) {
    String evidenceId() {
      return requestHash == null || requestHash.isEmpty() ? experimentId : requestHash;
    }
  }

  public record TaskAssessment(TaskStatus status, List<DeliverableAssessment> assessments) {
    public TaskAssessment {
      status = ContractValues.required("status", status);
      assessments = ImmutableCollections.requiredList("assessments", assessments);
    }

    public List<DeliverableAssessment> assessments() {
      return List.copyOf(assessments);
    }
  }

  private static String instruction(TaskRequirement requirement) {
    return switch (requirement) {
      case PROOF -> "Give a complete auditable proof of the frozen mathematical claim.";
      case SOLUTION ->
          "Give the requested solution set and justify validity, completeness, and absence of extraneous solutions.";
      case COMPUTATION ->
          "Return the requested computed values with an auditable deterministic calculation record and the exact finite scope.";
      case CONJECTURE ->
          "State a concrete falsifiable conjecture, its bounded evidence, scope limitations, and a separate proof obligation; do not claim it is proved.";
      case COUNTEREXAMPLE ->
          "Return an independently checked counterexample that satisfies the hypotheses and violates the target conclusion.";
      case CLASSIFICATION ->
          "Return the complete requested classification with coverage and mutual-exclusion justification.";
      case OPTIMIZATION ->
          "Return the optimum, attainment conditions, and a global optimality argument.";
      case CONSTRUCTION -> "Return a construction and verify every requested property.";
      case RESEARCH_PROGRESS ->
          "Return an auditable research-progress report that separates established facts, candidates, refutations, and open obligations.";
    };
  }

  private static void addIf(
      String text,
      List<String> markers,
      TaskRequirement requirement,
      List<TaskRequirement> output) {
    if (containsAny(text, markers)) {
      add(output, requirement);
    }
  }

  private static void add(List<TaskRequirement> output, TaskRequirement requirement) {
    if (!output.contains(requirement)) {
      output.add(requirement);
    }
  }

  private static boolean containsAny(String text, List<String> markers) {
    return markers.stream().anyMatch(text::contains);
  }

  private static List<TaskRequirement> distinct(List<TaskRequirement> requirements) {
    List<TaskRequirement> output = new ArrayList<>();
    requirements.forEach(item -> add(output, item));
    return List.copyOf(output);
  }
}
