package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class TaskContractsParityTest {
  private static final String COMPUTE_AND_CONJECTURE =
      "\u5148\u5b9a\u5411\u8ba1\u7b97\u524d12\u9879\uff0c"
          + "\u518d\u636e\u6b64\u63d0\u51fa\u4e00\u4e2a\u5fc5\u987b"
          + "\u53e6\u884c\u8bc1\u660e\u7684\u5019\u9009\u89c4\u5f8b\u3002";

  @Test
  void computeThenConjectureDoesNotSilentlyRequireProof() {
    assertEquals(
        List.of(TaskRequirement.COMPUTATION, TaskRequirement.CONJECTURE),
        TaskContractsFunctions.inferTaskRequirements(COMPUTE_AND_CONJECTURE));
  }

  @Test
  void explicitProofAndSolutionRequestsKeepStrictVerification() {
    assertEquals(
        List.of(TaskRequirement.SOLUTION, TaskRequirement.PROOF),
        TaskContractsFunctions.inferTaskRequirements(
            "\u6c42\u65b9\u7a0b\u7684\u6240\u6709\u89e3\uff0c"
                + "\u5e76\u8bc1\u660e\u6ca1\u6709\u9057\u6f0f\u3002"));
    assertEquals(
        List.of(TaskRequirement.PROOF),
        TaskContractsFunctions.inferTaskRequirements(
            "\u8bc1\u660e\u8be5\u547d\u9898\u6210\u7acb\u3002"));
  }

  @Test
  void scopedConjectureCanCompleteTaskWithoutBecomingVerified() {
    ProblemContract problem =
        TaskContractsFunctions.applyTaskContract(
            ContractObjectMapper.read(
                """
                {
                  "exact_statement":"%s",
                  "normalized_statement":"%s"
                }
                """
                    .formatted(COMPUTE_AND_CONJECTURE, COMPUTE_AND_CONJECTURE),
                ProblemContract.class));
    CandidateConjecture candidate =
        ContractObjectMapper.read(
            """
            {
              "statement":"a_n=2n+4",
              "rationale":"The first twelve exact values have this form.",
              "supporting_experiment_ids":["experiment-prefix"],
              "scope_limitations":["A finite prefix is not an infinite proof."],
              "proof_obligations":["Prove the formula for every positive integer n."]
            }
            """,
            CandidateConjecture.class);
    ProofAttempt attempt =
        ContractObjectMapper.read(
            """
            {
              "problem_hash":"%s",
              "strategy_id":"prefix",
              "agent_id":"explorer-a",
              "round_index":0,
              "status":"partial",
              "candidate_conjectures":[%s]
            }
            """
                .formatted(problem.integrityHash(), ContractObjectMapper.write(candidate)),
            ProofAttempt.class);
    TaskContractsFunctions.AssessmentState state =
        new TaskContractsFunctions.AssessmentState(null, null, List.of(attempt), null);
    TaskContractsFunctions.ExperimentEvidence experiment =
        new TaskContractsFunctions.ExperimentEvidence(
            "experiment-prefix",
            "request-prefix",
            ExperimentOutcome.NOT_REFUTED,
            null,
            false);

    TaskContractsFunctions.TaskAssessment assessment =
        TaskContractsFunctions.assessTaskDeliverables(
            problem, state, List.of(experiment), 0.9d);

    assertEquals(TaskStatus.COMPLETED, assessment.status());
    assertEquals(
        List.of(DeliverableStatus.COMPLETED, DeliverableStatus.COMPLETED),
        assessment.assessments().stream().map(DeliverableAssessment::status).toList());
  }
}
