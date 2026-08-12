package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SchemasParityTest {
  @Test
  void usageAndProblemHashesAreInferredWithoutRecursion() {
    UsageRecord usage = new UsageRecord(null, 7, null, 11, 99);
    assertEquals(18, usage.totalTokens());

    ProblemContract problem =
        ContractObjectMapper.read(
            """
            {
              "exact_statement": "Prove x=x.",
              "normalized_statement": "Prove x=x."
            }
            """,
            ProblemContract.class);
    assertEquals(CanonicalJson.stableHash("Prove x=x."), problem.integrityHash());
    assertEquals(problem.integrityHash(), problem.goalHash());
  }

  @Test
  void claimHashIsContentAddressedAndTamperEvident() {
    ClaimCard claim =
        ContractObjectMapper.read(
            """
            {"statement":"A","conclusion":"B"}
            """,
            ClaimCard.class);
    assertEquals(
        CanonicalJson.stableHash(
            ContractObjectMapper.parseTree(
                """
                {"statement":"A","assumptions":[],"conclusion":"B","dependencies":[]}
                """)),
        claim.contentHash());
    assertThrows(
        ContractValidationException.class,
        () ->
            ContractObjectMapper.read(
                """
                {"statement":"A","conclusion":"B","content_hash":"not-the-right-hash"}
                """,
                ClaimCard.class));
  }

  @Test
  void candidateConjectureRequiresScopeAndSeparateProofObligation() {
    CandidateConjecture candidate =
        ContractObjectMapper.read(
            """
            {
              "statement":"a_n = 2n + 4",
              "rationale":"The exact finite prefix increases by two.",
              "supporting_experiment_ids":["exp-prefix"],
              "scope_limitations":["A finite prefix does not prove a universal recurrence."],
              "proof_obligations":["Prove the recurrence from the least-candidate rule."]
            }
            """,
            CandidateConjecture.class);
    assertEquals("candidate", candidate.status());
    assertFalse(candidate.contentHash().isEmpty());

    ContractValidationException exception =
        assertThrows(
            ContractValidationException.class,
            () ->
                ContractObjectMapper.read(
                    """
                    {
                      "statement":"a_n = 2n + 4",
                      "rationale":"The exact finite prefix increases by two.",
                      "supporting_experiment_ids":["exp-prefix"],
                      "scope_limitations":[],
                      "proof_obligations":["Prove the recurrence."]
                    }
                    """,
                    CandidateConjecture.class));
    assertTrue(exception.getMessage().contains("scope"));
  }

  @Test
  void strictContractsRejectMissingUnknownAndBoundaryViolations() {
    assertThrows(
        ContractValidationException.class,
        () -> ContractObjectMapper.read("{}", QuantifierSpec.class));
    assertThrows(
        ContractValidationException.class,
        () ->
            ContractObjectMapper.read(
                """
                {
                  "order":0,
                  "kind":"forall",
                  "variable_id":"n",
                  "display_name":"n",
                  "domain":"integers",
                  "unknown":true
                }
                """,
                QuantifierSpec.class));
    assertThrows(
        ContractValidationException.class,
        () ->
            ContractObjectMapper.read(
                """
                {
                  "order":-1,
                  "kind":"forall",
                  "variable_id":"n",
                  "display_name":"n",
                  "domain":"integers"
                }
                """,
                QuantifierSpec.class));
  }

  @Test
  void contractRoundTripPreservesCanonicalWireForm() {
    QuantifierSpec original =
        ContractObjectMapper.read(
            """
            {
              "order":0,
              "kind":"forall",
              "variable_id":"n",
              "display_name":"n",
              "domain":"positive integers",
              "restrictions":["n >= 1"]
            }
            """,
            QuantifierSpec.class);
    QuantifierSpec roundTrip =
        ContractObjectMapper.read(ContractObjectMapper.write(original), QuantifierSpec.class);
    assertEquals(original, roundTrip);
    assertEquals(original.canonicalJson(), roundTrip.canonicalJson());
  }
}
