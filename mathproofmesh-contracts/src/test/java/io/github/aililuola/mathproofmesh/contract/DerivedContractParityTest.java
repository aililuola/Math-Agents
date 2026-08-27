package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DerivedContractParityTest {
  @Test
  void derivedSignatureHashesAreNormalizedAndTamperEvident() {
    NoveltySignature novelty =
        ContractObjectMapper.read(
            """
            {
              "representation_tags":["Graph","Graph"],
              "mechanism_tags":["Bridge"],
              "key_transformations":["Dualize"],
              "proof_principles":["Induction"],
              "targeted_obligation_ids":["obl-b","obl-a"]
            }
            """,
            NoveltySignature.class);
    assertEquals(
        List.of("Graph"), values(novelty.normalizedPayload(), "representation_tags"));
    assertEquals(
        CanonicalJson.stableHash(novelty.normalizedPayload()), novelty.normalizedHash());
    assertThrows(
        ContractValidationException.class,
        () ->
            ContractObjectMapper.read(
                """
                {"normalized_hash":"tampered"}
                """,
                NoveltySignature.class));

    MechanismChainSignature chain =
        MechanismChainSignature.fromNoveltySignature(novelty);
    assertTrue(chain.complete());
    assertEquals(
        CanonicalJson.stableHash(chain.normalizedPayload()), chain.chainHash());
    NoveltySignature roundTrip =
        chain.toNoveltySignature(novelty.targetedObligationIds());
    assertEquals(novelty.representationTags(), roundTrip.representationTags());
    assertEquals(novelty.targetedObligationIds(), roundTrip.targetedObligationIds());
  }

  @Test
  void obligationAndProgramHashesRejectTampering() {
    ProofObligation obligation =
        ContractObjectMapper.read(
            """
            {
              "problem_hash":"problem-a",
              "route_ids":["route-a"],
              "kind":"lemma",
              "statement":"Prove A.",
              "normalized_statement":"Prove A."
            }
            """,
            ProofObligation.class);
    assertFalse(obligation.contentHash().isEmpty());
    assertThrows(
        ContractValidationException.class,
        () ->
            ContractObjectMapper.read(
                """
                {
                  "problem_hash":"problem-a",
                  "route_ids":["route-a"],
                  "kind":"lemma",
                  "statement":"Prove A.",
                  "normalized_statement":"Prove A.",
                  "content_hash":"tampered"
                }
                """,
                ProofObligation.class));
    assertThrows(
        ContractValidationException.class,
        () ->
            ContractObjectMapper.read(
                """
                {
                  "problem_hash":"problem-a",
                  "route_ids":["route-a"],
                  "kind":"lemma",
                  "statement":"Prove A.",
                  "normalized_statement":"Prove A.",
                  "status":"closed"
                }
                """,
                ProofObligation.class));

    ExperimentProgram program =
        ContractObjectMapper.read(
            """
            {"experiment_id":"experiment-a","source":"print('exact')"}
            """,
            ExperimentProgram.class);
    assertEquals(CanonicalJson.stableHash(program.source()), program.codeHash());
    assertThrows(
        ContractValidationException.class,
        () ->
            ContractObjectMapper.read(
                """
                {
                  "experiment_id":"experiment-a",
                  "source":"print('exact')",
                  "code_hash":"tampered"
                }
                """,
                ExperimentProgram.class));
  }

  @Test
  void experimentRequestsBindRuntimeIdentityWithoutMutation() {
    ExperimentSpec spec = experimentSpec();
    assertEquals(0, spec.normalizedExecutionPayload().get("arguments").get("candidate_min").intValue());
    assertEquals(
        1_000_000,
        spec.normalizedExecutionPayload().get("arguments").get("candidate_max").intValue());
    assertTrue(
        spec.normalizedExecutionPayload().get("arguments").get("strictly_increasing").booleanValue());
    assertFalse(spec.normalizedExecutionPayload().get("runtime_fingerprint").has("seed"));
    assertEquals(
        CanonicalJson.stableHash(spec.normalizedExecutionPayload()), spec.executionHash());
    assertEquals(CanonicalJson.stableHash(spec.normalizedPayload()), spec.requestHash());

    ObjectNode runtime =
        (ObjectNode) ContractObjectMapper.parseTree(
            """
            {"engine":"java-25","seed":17}
            """);
    ExperimentSpec bound = spec.bindRuntimeFingerprint(runtime);
    assertTrue(spec.runtimeFingerprint().isEmpty());
    assertEquals("java-25", bound.runtimeFingerprint().get("engine").textValue());
    assertNotEquals(spec.requestHash(), bound.requestHash());
    assertNotEquals(spec.executionHash(), bound.executionHash());
    ObjectNode exposedArguments = spec.arguments();
    exposedArguments.put("candidate_min", 99);
    assertFalse(spec.arguments().has("candidate_min"));

    assertThrows(
        ContractValidationException.class,
        () ->
            ContractObjectMapper.read(
                """
                {
                  "purpose":"falsify_claim",
                  "target_claim":"A",
                  "reasoning_basis":"B",
                  "why_computation_is_needed":"C",
                  "decision_if_confirmed":"D",
                  "decision_if_refuted":"E",
                  "noncomputational_alternative":"F",
                  "method":"bounded_integer_search",
                  "broad_search":true
                }
                """,
                ExperimentSpec.class));
  }

  @Test
  void resultEvidenceHashesAndCrossRouteDtosStayTyped() {
    ExperimentResult result =
        ContractObjectMapper.read(
            """
            {
              "experiment_id":"experiment-a",
              "request_hash":"request-a",
              "target_claim":"All bounded cases agree.",
              "method":"bounded_integer_search",
              "outcome":"not_refuted",
              "evidence_strength":"bounded_evidence",
              "scope":{"min":0,"max":8},
              "exact_arithmetic":true,
              "cases_checked":9,
              "tool_name":"java-handler",
              "tool_version":"1",
              "artifact_refs":[{"artifact_ref":"artifact://result/a.json"}]
            }
            """,
            ExperimentResult.class);
    assertFalse(result.resultHash().isEmpty());
    ComputationPlan plan = ComputationPlan.fromSpec(experimentSpec());
    assertEquals(plan.requestHash(), experimentSpec().requestHash());
    assertEquals(100_000, plan.boundedScope().get("max_cases").intValue());
    ComputationCertificate certificate = ComputationCertificate.fromResult(result);
    assertEquals(EvidenceType.BOUNDED_EXPERIMENT, certificate.evidenceType());
    assertEquals(List.of("artifact://result/a.json"), certificate.artifactRefs());

    assertThrows(
        ContractValidationException.class,
        () ->
            ContractObjectMapper.read(
                """
                {
                  "experiment_id":"experiment-a",
                  "request_hash":"request-a",
                  "target_claim":"A",
                  "method":"bounded_integer_search",
                  "outcome":"counterexample_found",
                  "evidence_strength":"counterexample",
                  "counterexample":{"x":1},
                  "tool_name":"java-handler",
                  "tool_version":"1"
                }
                """,
                ExperimentResult.class));
  }

  @Test
  void legacyViewsAndDerivedStrategyClaimsMatchPythonValidators() {
    ProblemSemanticView view =
        ContractObjectMapper.read(
            """
            {
              "source_statement_hash":"hash-a",
              "source_language":"zh-CN",
              "english_statement":"Prove A.",
              "candidate_confidence":0.8,
              "status":"usable"
            }
            """,
            ProblemSemanticView.class);
    assertEquals("rejected", view.status());
    assertTrue(view.notes().getFirst().contains("deterministic bilingual audit"));

    StrategyCard strategy =
        ContractObjectMapper.read(
            """
            {
              "title":"Induction",
              "core_idea":"Induct on n.",
              "independence_basis":"A separate route.",
              "bottleneck":"Establish the inductive step.",
              "falsification_test":"Check the first failed n.",
              "estimated_success":0.7
            }
            """,
            StrategyCard.class);
    assertEquals(1, strategy.criticalClaims().size());
    assertTrue(strategy.criticalClaims().getFirst().claimId().startsWith("critical_"));

    ProofStep step =
        ContractObjectMapper.read(
            """
            {
              "step_id":"step-a",
              "statement":"A follows.",
              "justification":"By induction.",
              "dependency_refs":[{"kind":"local_claim","target_id":"claim-a"}]
            }
            """,
            ProofStep.class);
    assertFalse(step.checkpointPayload().has("dependency_refs"));
    assertFalse(step.checkpointPayload().has("calculation_checks"));
  }

  @Test
  void idsTimestampsCollectionsAndBudgetPropertiesAreStable() {
    String identifier = PythonCompatibleIdGenerator.newId("claim");
    assertTrue(identifier.matches("claim_[0-9a-f]{12}"));
    AtomicInteger attempts = new AtomicInteger();
    assertThrows(
        ContractValidationException.class,
        () ->
            PythonCompatibleIdGenerator.newId(
                "claim",
                ignored -> {
                  attempts.incrementAndGet();
                  return true;
                }));
    assertEquals(5, attempts.get());

    Clock clock =
        Clock.fixed(
            Instant.parse("2026-07-30T10:20:30.123456789Z"), ZoneOffset.UTC);
    assertEquals(
        "2026-07-30T10:20:30.123456+00:00",
        PythonIsoTimestampCodec.now(clock));
    assertEquals(
        ZoneOffset.UTC,
        PythonIsoTimestampCodec.parse("2026-07-30T10:20:30.123456+00:00").getOffset());

    SurpriseBudgetState budget =
        new SurpriseBudgetState(null, 1, null, 2, 10, 3);
    assertEquals(5, budget.remainingCalls());
    InspirationCallReservation reservation =
        ContractObjectMapper.read(
            """
            {
              "task_id":"task-a",
              "trigger_id":"trigger-a",
              "round_index":0,
              "proposer_calls":2,
              "referee_calls":1,
              "reserved_calls":3,
              "consumed_calls":1
            }
            """,
            InspirationCallReservation.class);
    assertEquals(2, reservation.remainingReservedCalls());
    assertThrows(
        UnsupportedOperationException.class,
        () -> reservation.phaseCalls().put("review", 1));
    NoveltySignature nestedCollections =
        ContractObjectMapper.read(
            """
            {"raw_tags":{"representation":["graph"]}}
            """,
            NoveltySignature.class);
    assertThrows(
        UnsupportedOperationException.class,
        () -> nestedCollections.rawTags().get("representation").add("mutable"));
    ClaimCard claim =
        ContractObjectMapper.read(
            """
            {
              "statement":"A",
              "conclusion":"B",
              "dependency_refs":[{"kind":"local_claim","target_id":"claim-a"}]
            }
            """,
            ClaimCard.class);
    ((ObjectNode) claim.dependencyRefs().getFirst()).put("target_id", "changed");
    assertEquals(
        "claim-a",
        claim.dependencyRefs().getFirst().get("target_id").textValue());
    assertThrows(
        ContractValidationException.class,
        () ->
            ContractObjectMapper.read(
                """
                {
                  "order":null,
                  "kind":"forall",
                  "variable_id":"n",
                  "display_name":"n",
                  "domain":"integers"
                }
                """,
                QuantifierSpec.class));
  }

  private static ExperimentSpec experimentSpec() {
    return ContractObjectMapper.read(
        """
        {
          "purpose":"discover_pattern",
          "target_claim":"Find a bounded pattern.",
          "reasoning_basis":"The finite domain is exact.",
          "why_computation_is_needed":"No symbolic pattern is known.",
          "decision_if_confirmed":"Create a proof obligation.",
          "decision_if_refuted":"Discard the route.",
          "noncomputational_alternative":"Continue symbolic work.",
          "method":"bounded_greedy_sequence",
          "domains":{"n":{"min":0,"max":8}},
          "broad_search":true
        }
        """,
        ExperimentSpec.class);
  }

  private static List<String> values(ObjectNode payload, String name) {
    return payload.get(name).valueStream().map(node -> node.textValue()).toList();
  }
}
