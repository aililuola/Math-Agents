package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class Phase17TaskContractsHardeningTest {

  @Test
  void inferenceCoversEveryMarkerFallbackAndInstruction() {
    assertEquals(
        List.of(
            TaskRequirement.SOLUTION,
            TaskRequirement.COMPUTATION,
            TaskRequirement.CONJECTURE,
            TaskRequirement.COUNTEREXAMPLE,
            TaskRequirement.CLASSIFICATION,
            TaskRequirement.OPTIMIZATION,
            TaskRequirement.CONSTRUCTION,
            TaskRequirement.PROOF),
        TaskContractsFunctions.inferTaskRequirements(
            "Solve and classify all solutions, compute the first 12 terms, construct an "
                + "optimal example, state a conjecture, find a counterexample, and prove it."));
    assertEquals(
        List.of(TaskRequirement.CONJECTURE),
        TaskContractsFunctions.inferTaskRequirements(
            "State a conjecture without proving it."));
    assertEquals(
        List.of(TaskRequirement.COMPUTATION),
        TaskContractsFunctions.inferTaskRequirements("first 9 terms"));

    assertEquals(
        List.of(TaskRequirement.COMPUTATION),
        TaskContractsFunctions.inferTaskRequirements("unmarked", ProblemKind.CALCULATION));
    assertEquals(
        List.of(TaskRequirement.OPTIMIZATION),
        TaskContractsFunctions.inferTaskRequirements("unmarked", ProblemKind.OPTIMIZATION));
    assertEquals(
        List.of(TaskRequirement.CONSTRUCTION),
        TaskContractsFunctions.inferTaskRequirements("unmarked", ProblemKind.CONSTRUCTION));
    assertEquals(
        List.of(TaskRequirement.RESEARCH_PROGRESS),
        TaskContractsFunctions.inferTaskRequirements("unmarked", ProblemKind.RESEARCH));
    for (ProblemKind kind :
        List.of(
            ProblemKind.PROOF,
            ProblemKind.LOGIC,
            ProblemKind.MIXED,
            ProblemKind.UNKNOWN)) {
      assertEquals(
          List.of(TaskRequirement.PROOF),
          TaskContractsFunctions.inferTaskRequirements("unmarked", kind));
    }

    List<String> instructions =
        TaskContractsFunctions.deliverableInstructions(List.of(TaskRequirement.values()));
    assertEquals(TaskRequirement.values().length, instructions.size());
    assertTrue(instructions.stream().noneMatch(String::isBlank));
  }

  @Test
  void triageCanSupplyExplicitRequirementsWhenTextHasNoProofMarker() {
    ProblemContract problem = problem("Explore the bounded structure.");
    TriageResult triage =
        new TriageResult(
            0.9d,
            Difficulty.RESEARCH,
            List.of(),
            List.of(),
            ProblemKind.UNKNOWN,
            "hybrid",
            "The statement requests bounded research progress.",
            null,
            2,
            2,
            List.of(
                TaskRequirement.RESEARCH_PROGRESS,
                TaskRequirement.RESEARCH_PROGRESS,
                TaskRequirement.CONJECTURE));

    ProblemContract applied = TaskContractsFunctions.applyTaskContract(problem, triage);
    assertEquals(
        List.of(TaskRequirement.RESEARCH_PROGRESS, TaskRequirement.CONJECTURE),
        applied.taskRequirements());
    assertEquals(
        List.of(TaskRequirement.PROOF),
        TaskContractsFunctions.applyTaskContract(problem).taskRequirements());
  }

  @Test
  void assessmentCoversProofComputationConjectureCounterexampleAndResearch() {
    ProblemContract base = problem("Complete all requested deliverables.");
    VerificationReport passing = verification(VerificationVerdict.PASS, 0.95d);
    VerificationReport weak = verification(VerificationVerdict.PASS, 0.50d);
    FinalProof proof =
        new FinalProof(
            "Audited final answer.",
            List.of(),
            0.95d,
            List.of(),
            base.integrityHash(),
            List.of(),
            List.of("attempt-final"));
    CandidateConjecture conjecture =
        ContractObjectMapper.read(
            """
            {
              "statement":"a_n=n",
              "rationale":"A bounded exact prefix supports the candidate.",
              "supporting_experiment_ids":["experiment-1"],
              "scope_limitations":["Finite evidence is not a proof."],
              "proof_obligations":["Prove the candidate for every n."]
            }
            """,
            CandidateConjecture.class);
    ProofAttempt attempt =
        ContractObjectMapper.read(
            """
            {
              "problem_hash":"%s",
              "strategy_id":"bounded",
              "agent_id":"explorer",
              "round_index":0,
              "status":"partial",
              "final_answer":"The bounded values were interpreted.",
              "candidate_conjectures":[%s]
            }
            """
                .formatted(base.integrityHash(), ContractObjectMapper.write(conjecture)),
            ProofAttempt.class);
    ResearchProgressReport progress =
        new ResearchProgressReport(
            null,
            List.of(),
            null,
            List.of(),
            List.of(),
            base.integrityHash(),
            List.of(),
            List.of("one open lemma"),
            attempt.attemptId(),
            "A bounded research report was produced.",
            "budget exhausted",
            List.of(attempt.attemptId()),
            List.of(),
            List.of());

    List<TaskRequirement> requirements = List.of(TaskRequirement.values());
    ProblemContract all =
        base.withTaskContract(
            requirements, TaskContractsFunctions.deliverableInstructions(requirements));
    List<TaskContractsFunctions.ExperimentEvidence> evidence =
        List.of(
            new TaskContractsFunctions.ExperimentEvidence(
                "experiment-1", "request-1", ExperimentOutcome.NOT_REFUTED, null, false),
            new TaskContractsFunctions.ExperimentEvidence(
                "experiment-2", "", ExperimentOutcome.COUNTEREXAMPLE_FOUND, null, true),
            new TaskContractsFunctions.ExperimentEvidence(
                "experiment-3", null, ExperimentOutcome.CERTIFIED, null, true),
            new TaskContractsFunctions.ExperimentEvidence(
                "experiment-error", "request-error", ExperimentOutcome.ERROR, "failed", false));
    var complete =
        TaskContractsFunctions.assessTaskDeliverables(
            all,
            new TaskContractsFunctions.AssessmentState(
                passing, proof, List.of(attempt), progress),
            evidence,
            0.9d);
    assertEquals(TaskStatus.COMPLETED, complete.status());
    assertTrue(
        complete.assessments().stream()
            .allMatch(item -> item.status() == DeliverableStatus.COMPLETED));

    var partial =
        TaskContractsFunctions.assessTaskDeliverables(
            all,
            new TaskContractsFunctions.AssessmentState(weak, null, List.of(attempt), null),
            evidence,
            0.9d);
    assertEquals(TaskStatus.PARTIAL, partial.status());
    assertTrue(
        partial.assessments().stream()
            .anyMatch(item -> item.status() == DeliverableStatus.COMPLETED));
    assertTrue(
        partial.assessments().stream()
            .anyMatch(item -> item.status() == DeliverableStatus.MISSING));

    var incomplete =
        TaskContractsFunctions.assessTaskDeliverables(
            all,
            new TaskContractsFunctions.AssessmentState(null, null, null, null),
            List.of(),
            0.9d);
    assertEquals(TaskStatus.INCOMPLETE, incomplete.status());
    assertFalse(incomplete.assessments().isEmpty());
    assertTrue(
        incomplete.assessments().stream()
            .allMatch(item -> item.status() == DeliverableStatus.MISSING));
  }

  private static ProblemContract problem(String statement) {
    return ContractObjectMapper.read(
        """
        {
          "exact_statement":"%s",
          "normalized_statement":"%s"
        }
        """
            .formatted(statement, statement),
        ProblemContract.class);
  }

  private static VerificationReport verification(
      VerificationVerdict verdict, double confidence) {
    return new VerificationReport(
        "reviewer",
        List.of(),
        "Independent final audit.",
        confidence,
        FailureLevel.NONE,
        null,
        List.of(),
        true,
        null,
        null,
        VerificationStage.FINAL,
        List.of(),
        "final-proof",
        "final_proof",
        List.of(),
        List.of(),
        null,
        verdict);
  }
}
