package io.github.aililuola.mathproofmesh.contract;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class StructuredPayloadNormalizationParityTest {
  @Test
  void canonicalizesExplicitQuantifierAliasesWithoutChangingTheirScope() {
    ObjectNode payload =
        object(
            """
            {
              "claim_id":"claim-quantifiers",
              "local_assumption_node_ids":[],
              "local_assumptions":[],
              "quantifiers":[
                {
                  "display_name":"n",
                  "domain":"positive integer",
                  "kind":"universal",
                  "order":0,
                  "restrictions":[],
                  "variable_id":"n"
                },
                {
                  "display_name":"d",
                  "domain":"positive integer",
                  "kind":"unique",
                  "order":1,
                  "restrictions":[],
                  "variable_id":"d"
                },
                {
                  "display_name":"k",
                  "domain":"integer",
                  "kind":"existential",
                  "order":2,
                  "restrictions":[],
                  "variable_id":"k"
                }
              ],
              "variable_bindings":[],
              "scope_limitations":[],
              "polarity":"positive"
            }
            """);

    List<String> actions =
        StructuredPayloadNormalizer.normalize(payload, CriticalClaimContextBinding.class);
    CriticalClaimContextBinding binding =
        ContractObjectMapper.read(payload, CriticalClaimContextBinding.class);

    assertEquals(
        List.of("forall", "exists_unique", "exists"),
        binding.quantifiers().stream().map(QuantifierSpec::kind).toList());
    assertEquals(List.of(0, 1, 2), binding.quantifiers().stream().map(QuantifierSpec::order).toList());
    assertEquals(3, actions.stream().filter(action -> action.contains("quantifier kind")).count());
  }

  @Test
  void leavesUnknownQuantifierSemanticsForStrictValidationToReject() {
    ObjectNode payload =
        object(
            """
            {
              "claim_id":"claim-unknown-quantifier",
              "local_assumption_node_ids":[],
              "local_assumptions":[],
              "quantifiers":[
                {
                  "display_name":"n",
                  "domain":"positive integer",
                  "kind":"at_most_one",
                  "order":0,
                  "restrictions":[],
                  "variable_id":"n"
                }
              ],
              "variable_bindings":[],
              "scope_limitations":[],
              "polarity":"positive"
            }
            """);

    List<String> actions =
        StructuredPayloadNormalizer.normalize(payload, CriticalClaimContextBinding.class);

    assertTrue(actions.stream().noneMatch(action -> action.contains("quantifier kind")));
    assertThrows(
        ContractValidationException.class,
        () -> ContractObjectMapper.read(payload, CriticalClaimContextBinding.class));
  }

  @Test
  void removesOnlyBoundLocalLetDeclarationsFromNestedStrategyContexts() {
    ObjectNode payload =
        object(
            """
            {
              "coverage_notes":"One auditable mechanism is represented.",
              "strategies":[
                {
                  "strategy_id":"S-let",
                  "title":"Local-definition route",
                  "core_idea":"Introduce m=a+b before reducing the target.",
                  "bottleneck":"Justify the reduction after defining m.",
                  "estimated_success":0.7,
                  "falsification_test":"Search for a bounded counterexample.",
                  "independence_basis":"Uses a local algebraic definition.",
                  "critical_claim_context_bindings":[
                    {
                      "claim_id":"claim-local-definition",
                      "claim_blueprint_node_id":"@claim",
                      "local_assumption_node_ids":[],
                      "local_assumptions":["m=a+b"],
                      "quantifiers":[
                        {
                          "display_name":"m",
                          "domain":"positive integer",
                          "kind":"let",
                          "order":0,
                          "restrictions":["m=a+b","m>0"],
                          "variable_id":"m"
                        }
                      ],
                      "variable_bindings":[
                        {
                          "aliases":["m"],
                          "display_name":"m",
                          "domain":"positive integer",
                          "owner_scope":"claim_local",
                          "variable_id":"m"
                        }
                      ],
                      "scope_limitations":[],
                      "polarity":"positive"
                    }
                  ]
                }
              ]
            }
            """);

    List<String> actions = StructuredPayloadNormalizer.normalize(payload, StrategySet.class);
    CriticalClaimContextBinding binding =
        ContractObjectMapper.read(payload, StrategySet.class)
            .strategies()
            .getFirst()
            .criticalClaimContextBindings()
            .getFirst();

    assertTrue(binding.quantifiers().isEmpty());
    assertEquals(List.of("m=a+b", "m>0"), binding.localAssumptions());
    assertTrue(
        actions.stream()
            .anyMatch(
                action ->
                    action.contains("critical_claim_context_bindings[0]")
                        && action.contains("local let declaration for m")));
  }

  @Test
  void leavesUnboundLocalLetDeclarationsForStrictValidationToReject() {
    ObjectNode payload =
        object(
            """
            {
              "claim_id":"claim-unbound-let",
              "local_assumption_node_ids":[],
              "local_assumptions":["m=a+b"],
              "quantifiers":[
                {
                  "display_name":"m",
                  "domain":"positive integer",
                  "kind":"let",
                  "order":0,
                  "restrictions":["m=a+b"],
                  "variable_id":"m"
                }
              ],
              "variable_bindings":[],
              "scope_limitations":[],
              "polarity":"positive"
            }
            """);

    List<String> actions =
        StructuredPayloadNormalizer.normalize(payload, CriticalClaimContextBinding.class);

    assertTrue(actions.stream().noneMatch(action -> action.contains("local let declaration")));
    assertThrows(
        ContractValidationException.class,
        () -> ContractObjectMapper.read(payload, CriticalClaimContextBinding.class));
  }

  @Test
  void leavesEveryAmbiguousLocalLetShapeUntouched() {
    List<ObjectNode> ambiguous = new ArrayList<>();

    ObjectNode missingBindings = localLetBindingPayload();
    missingBindings.remove("variable_bindings");
    ambiguous.add(missingBindings);

    ObjectNode missingAssumptions = localLetBindingPayload();
    missingAssumptions.remove("local_assumptions");
    ambiguous.add(missingAssumptions);

    ObjectNode nonObjectQuantifier = localLetBindingPayload();
    nonObjectQuantifier.putArray("quantifiers").add("let m=a+b");
    ambiguous.add(nonObjectQuantifier);

    ObjectNode missingRestrictions = localLetBindingPayload();
    quantifier(missingRestrictions).remove("restrictions");
    ambiguous.add(missingRestrictions);

    ObjectNode emptyRestrictions = localLetBindingPayload();
    quantifier(emptyRestrictions).putArray("restrictions");
    ambiguous.add(emptyRestrictions);

    ObjectNode blankRestriction = localLetBindingPayload();
    quantifier(blankRestriction).putArray("restrictions").add(" ");
    ambiguous.add(blankRestriction);

    ObjectNode nonTextRestriction = localLetBindingPayload();
    quantifier(nonTextRestriction).putArray("restrictions").add(1);
    ambiguous.add(nonTextRestriction);

    for (String missingIdentityField : List.of("variable_id", "display_name", "domain")) {
      ObjectNode missingIdentity = localLetBindingPayload();
      quantifier(missingIdentity).remove(missingIdentityField);
      ambiguous.add(missingIdentity);
    }

    ObjectNode nonObjectBinding = localLetBindingPayload();
    nonObjectBinding.putArray("variable_bindings").add("m");
    ambiguous.add(nonObjectBinding);

    ObjectNode missingBindingVariableId = localLetBindingPayload();
    binding(missingBindingVariableId).remove("variable_id");
    ambiguous.add(missingBindingVariableId);

    for (Map.Entry<String, String> mismatch :
        Map.of(
                "variable_id", "other",
                "owner_scope", "root",
                "display_name", "other",
                "domain", "real")
            .entrySet()) {
      ObjectNode mismatchedBinding = localLetBindingPayload();
      binding(mismatchedBinding).put(mismatch.getKey(), mismatch.getValue());
      ambiguous.add(mismatchedBinding);
    }

    ObjectNode duplicateBinding = localLetBindingPayload();
    ((ArrayNode) duplicateBinding.get("variable_bindings"))
        .add(binding(duplicateBinding).deepCopy());
    ambiguous.add(duplicateBinding);

    for (ObjectNode candidate : ambiguous) {
      List<String> actions =
          StructuredPayloadNormalizer.normalize(
              candidate, CriticalClaimContextBinding.class);

      assertTrue(
          actions.stream().noneMatch(action -> action.contains("local let declaration")),
          candidate.toString());
      assertFalse(candidate.path("quantifiers").isEmpty(), candidate.toString());
    }
  }

  @Test
  void canonicalizesOnlyClosedAffirmativeStrategyPolarityLabels() {
    List<String> aliases =
        List.of(
            "identity",
            "nonnegative_inequality",
            "equivalence",
            "iff",
            "derived_inequality",
            "global_maximum",
            "unique_solution",
            "恒等式",
            "非负不等式",
            "等价性",
            "充分必要条件",
            "全局最大值",
            "唯一解");

    for (String alias : aliases) {
      ObjectNode payload = strategyPolarityPayload(alias);

      List<String> actions = StructuredPayloadNormalizer.normalize(payload, StrategySet.class);
      CriticalClaimContextBinding binding =
          ContractObjectMapper.read(payload, StrategySet.class)
              .strategies()
              .getFirst()
              .criticalClaimContextBindings()
              .getFirst();

      assertEquals("positive", binding.polarity(), alias);
      assertTrue(
          actions.stream()
              .anyMatch(
                  action ->
                      action.contains("critical_claim_context_bindings[0].polarity")
                          && action.contains(alias)
                          && action.endsWith("to positive")),
          alias);
    }
  }

  @Test
  void preservesBinaryStrategyPolarityAndRejectsUnknownNarrative() {
    for (String polarity : List.of("positive", "negative")) {
      ObjectNode payload = strategyPolarityPayload(polarity);

      List<String> actions = StructuredPayloadNormalizer.normalize(payload, StrategySet.class);
      CriticalClaimContextBinding binding =
          ContractObjectMapper.read(payload, StrategySet.class)
              .strategies()
              .getFirst()
              .criticalClaimContextBindings()
              .getFirst();

      assertEquals(polarity, binding.polarity());
      assertTrue(actions.stream().noneMatch(action -> action.contains(".polarity")));
    }

    ObjectNode ambiguous = strategyPolarityPayload("probably an equality");
    List<String> actions = StructuredPayloadNormalizer.normalize(ambiguous, StrategySet.class);

    assertTrue(actions.stream().noneMatch(action -> action.contains(".polarity")));
    assertThrows(
        ContractValidationException.class,
        () -> ContractObjectMapper.read(ambiguous, StrategySet.class));
  }

  @Test
  void isolatesInvalidStrategyQuantifierWithoutGuessingItsMeaning() {
    ObjectNode payload = strategySetWithQuantifierKinds("forall", "positive");

    List<String> actions = StructuredPayloadNormalizer.normalize(payload, StrategySet.class);
    StrategySet strategies = ContractObjectMapper.read(payload, StrategySet.class);

    assertEquals(
        List.of("S-valid"),
        strategies.strategies().stream().map(StrategyCard::strategyId).toList());
    assertTrue(
        actions.stream()
            .anyMatch(
                action ->
                    action.equals(
                        "isolated invalid StrategySet.strategies[1] candidate S-invalid")));
  }

  @Test
  void leavesAnEntirelyInvalidStrategySetForStrictRepairOrRejection() {
    ObjectNode payload = strategySetWithQuantifierKinds("positive", "negative");

    List<String> actions = StructuredPayloadNormalizer.normalize(payload, StrategySet.class);

    assertTrue(
        actions.stream()
            .noneMatch(action -> action.startsWith("isolated invalid StrategySet")));
    assertThrows(
        ContractValidationException.class,
        () -> ContractObjectMapper.read(payload, StrategySet.class));
  }

  @Test
  void dropsInvalidOptionalStrategyChecksButPreservesSandboxComputationHints() {
    ObjectNode payload =
        object(
            """
            {
              "coverage_notes":"Two independent mechanisms are represented.",
              "strategies":[
                {
                  "strategy_id":"S001",
                  "title":"Invariant route",
                  "core_idea":"Prove an invariant before deriving the target.",
                  "bottleneck":"Establish the invariant.",
                  "estimated_success":0.6,
                  "falsification_test":"Search for a bounded counterexample.",
                  "independence_basis":"Uses an invariant rather than direct induction.",
                  "computation_hints":[
                    {
                      "purpose":"falsify_claim",
                      "target_claim":"The invariant holds on the bounded prefix.",
                      "decision_use":"Reject the route if a counterexample is found.",
                      "suggested_method":"sandboxed_python",
                      "broad_search":false
                    }
                  ],
                  "calculation_checks":[
                    {
                      "kind":"bounded_integer_search",
                      "arguments":{"expression":"x"},
                      "domains":{"x":{"min":0,"max":4}},
                      "max_cases":5,
                      "purpose":"Try to falsify the bounded claim."
                    },
                    {
                      "kind":"sandboxed_python",
                      "arguments":{"code":"print(1)"},
                      "domains":{},
                      "max_cases":1,
                      "purpose":"This belongs to an ExperimentSpec, not ToolRequest."
                    }
                  ]
                }
              ]
            }
            """);

    List<String> actions = StructuredPayloadNormalizer.normalize(payload, StrategySet.class);
    StrategySet strategies = ContractObjectMapper.read(payload, StrategySet.class);

    assertEquals(1, strategies.strategies().getFirst().calculationChecks().size());
    assertEquals(
        "bounded_integer_search",
        strategies.strategies().getFirst().calculationChecks().getFirst().kind());
    assertEquals(
        ComputationMethod.SANDBOXED_PYTHON,
        strategies.strategies().getFirst().computationHints().getFirst().suggestedMethod());
    assertTrue(actions.stream().anyMatch(action -> action.contains("valid typed ToolRequest")));
  }

  private static ObjectNode strategySetWithQuantifierKinds(String firstKind, String secondKind) {
    return object(
        """
        {
          "coverage_notes":"Two complete candidate strategies are represented.",
          "strategies":[
            {
              "strategy_id":"S-valid",
              "title":"First route",
              "core_idea":"Prove the first route.",
              "bottleneck":"Establish its critical claim.",
              "estimated_success":0.7,
              "falsification_test":"Search for a bounded counterexample.",
              "independence_basis":"Uses the first exact mechanism.",
              "critical_claim_context_bindings":[
                {
                  "claim_id":"S-valid-C1",
                  "claim_blueprint_node_id":"@claim",
                  "local_assumption_node_ids":[],
                  "local_assumptions":[],
                  "quantifiers":[{
                    "display_name":"p",
                    "domain":"primes",
                    "kind":"%s",
                    "order":1,
                    "restrictions":[],
                    "variable_id":"S-valid-p"
                  }],
                  "variable_bindings":[],
                  "scope_limitations":[],
                  "polarity":"positive"
                }
              ]
            },
            {
              "strategy_id":"S-invalid",
              "title":"Second route",
              "core_idea":"Prove the second route.",
              "bottleneck":"Establish its critical claim.",
              "estimated_success":0.6,
              "falsification_test":"Search for a bounded counterexample.",
              "independence_basis":"Uses the second exact mechanism.",
              "critical_claim_context_bindings":[
                {
                  "claim_id":"S-invalid-C1",
                  "claim_blueprint_node_id":"@claim",
                  "local_assumption_node_ids":[],
                  "local_assumptions":[],
                  "quantifiers":[{
                    "display_name":"q",
                    "domain":"primes",
                    "kind":"%s",
                    "order":1,
                    "restrictions":[],
                    "variable_id":"S-invalid-q"
                  }],
                  "variable_bindings":[],
                  "scope_limitations":[],
                  "polarity":"positive"
                }
              ]
            }
          ]
        }
        """.formatted(firstKind, secondKind));
  }

  @Test
  void dropsUnsupportedOptionalConjecturesAndDowngradesSupportedOnes() {
    ObjectNode payload =
        (ObjectNode)
            ContractObjectMapper.parseTree(
                """
                {
                  "action":"submit_attempt",
                  "reason":"honest partial route",
                  "attempt":{
                    "agent_id":"explorer",
                    "problem_hash":"problem-hash",
                    "round_index":1,
                    "status":"partial",
                    "strategy_id":"S3",
                    "candidate_conjectures":[
                      {
                        "statement":"an unsupported guess",
                        "rationale":"pure speculation",
                        "supporting_experiment_ids":[],
                        "scope_limitations":["not proved"],
                        "proof_obligations":[],
                        "status":"conjecture"
                      },
                      {
                        "statement":"a bounded result suggests a pattern",
                        "rationale":"the exact prefix supports it",
                        "supporting_experiment_ids":["experiment-1"],
                        "scope_limitations":["bounded evidence is not a proof"],
                        "proof_obligations":["prove the pattern symbolically"],
                        "status":"conjecture"
                      }
                    ]
                  }
                }
                """);

    List<String> actions =
        StructuredPayloadNormalizer.normalize(payload, InitialExplorationTurn.class);
    InitialExplorationTurn turn =
        ContractObjectMapper.read(payload, InitialExplorationTurn.class);

    assertEquals(1, turn.attempt().candidateConjectures().size());
    assertEquals("candidate", turn.attempt().candidateConjectures().getFirst().status());
    assertTrue(actions.stream().anyMatch(action -> action.startsWith("dropped unsupported")));
    assertTrue(actions.stream().anyMatch(action -> action.startsWith("downgraded")));
  }

  @Test
  void dropsAttemptBindingsThatTargetPreexistingStrategyClaims() {
    ObjectNode payload =
        object(
            """
            {
              "action":"submit_attempt",
              "reason":"complete route",
              "attempt":{
                "agent_id":"explorer",
                "problem_hash":"problem-hash",
                "round_index":1,
                "status":"complete",
                "strategy_id":"S3",
                "final_answer":"The exact reduction proves the target.",
                "proposed_lemmas":[],
                "claim_semantic_context_manifest_version":1,
                "claim_semantic_context_bindings":[
                  {
                    "claim_id":"existing-strategy-critical-claim",
                    "claim_blueprint_node_id":"@claim",
                    "local_assumptions":[],
                    "quantifiers":[],
                    "variable_bindings":[],
                    "scope_limitations":[],
                    "polarity":"positive"
                  }
                ]
              }
            }
            """);

    List<String> actions =
        StructuredPayloadNormalizer.normalize(payload, InitialExplorationTurn.class);
    InitialExplorationTurn turn =
        ContractObjectMapper.read(payload, InitialExplorationTurn.class);

    assertTrue(turn.attempt().proposedLemmas().isEmpty());
    assertTrue(turn.attempt().claimSemanticContextBindings().isEmpty());
    assertTrue(
        actions.stream()
            .anyMatch(
                action ->
                    action.contains("existing-strategy-critical-claim")
                        && action.contains("not an attempt-local proposed lemma")));
  }

  @Test
  void requestComputationRepairsOnlyServerOwnedPolicyFields() {
    ObjectNode payload = computationTurnPayload();
    List<String> actions =
        StructuredPayloadNormalizer.normalize(payload, ContinuationTurn.class);
    ContinuationTurn turn = ContractObjectMapper.read(payload, ContinuationTurn.class);

    assertNull(turn.experimentImpact());
    assertTrue(turn.experimentSpec().broadSearch());
    assertEquals(
        List.of(
            "cleared premature experiment_impact for request_computation",
            "marked discover_pattern computation as broad_search"),
        actions);
  }

  @Test
  void initialComputationTurnUsesSameDeterministicNormalization() {
    ObjectNode payload = computationTurnPayload();
    StructuredPayloadNormalizer.normalize(payload, InitialExplorationTurn.class);
    InitialExplorationTurn turn =
        ContractObjectMapper.read(payload, InitialExplorationTurn.class);
    assertNull(turn.experimentImpact());
    assertTrue(turn.experimentSpec().broadSearch());
  }

  @Test
  void requestComputationDropsTurnEnvelopeMetadataAndRepairsAnEmptyCaseBudget() {
    ObjectNode payload = computationTurnPayload();
    payload.put("agent_id", "explorer-a");
    payload.put("path_id", "route-1");
    payload.put("problem_hash", "server-owned");
    payload.put("strategy_id", "S1");
    payload.set("usage", object("{\"output_tokens\":12}"));
    ((ObjectNode) payload.get("experiment_spec")).put("max_cases", 0);

    List<String> actions =
        StructuredPayloadNormalizer.normalize(payload, InitialExplorationTurn.class);
    InitialExplorationTurn turn =
        ContractObjectMapper.read(payload, InitialExplorationTurn.class);

    assertFalse(payload.has("agent_id"));
    assertFalse(payload.has("path_id"));
    assertFalse(payload.has("problem_hash"));
    assertFalse(payload.has("strategy_id"));
    assertFalse(payload.has("usage"));
    assertEquals(1, turn.experimentSpec().maxCases());
    assertTrue(actions.contains("removed turn envelope metadata field usage"));
    assertTrue(actions.contains("raised computation max_cases to the minimum bound of 1"));
  }

  @Test
  void metaStrategyActionAliasIsCanonicalizedWithoutGuessing() {
    ObjectNode payload =
        object(
            """
            {
              "round_index":3,
              "action":"invent_auxiliary_construction",
              "affected_route_ids":["route-a"],
              "selected_mechanism":"invent_auxiliary_construction",
              "reason":"A new object may expose the missing bridge."
            }
            """);
    List<String> actions =
        StructuredPayloadNormalizer.normalize(payload, MetaStrategyDecision.class);
    MetaStrategyDecision decision =
        ContractObjectMapper.read(payload, MetaStrategyDecision.class);
    assertEquals(InspirationMechanism.AUXILIARY_CONSTRUCTION, decision.selectedMechanism());
    assertEquals(
        List.of(
            "canonicalized selected_mechanism invent_auxiliary_construction to auxiliary_construction"),
        actions);
  }

  @Test
  void canonicalizesUnambiguousToolAuditVerdictAliases() {
    for (Map.Entry<String, String> alias :
        Map.of(
                "reject", "fail",
                "rejected", "fail",
                "accept", "pass",
                "accepted", "pass",
                "deterministic_replay_success", "pass",
                "unverifiable", "inconclusive")
            .entrySet()) {
      ObjectNode payload =
          object(
              """
              {
                "agent_id":"tool-specialist",
                "all_results_replayed_independently":false,
                "confidence":0.95,
                "experiment_ids":["experiment-1"],
                "issues":["The bounded evidence cannot establish the theorem."],
                "mathematical_mapping_checked":true,
                "replay_artifact_refs":[],
                "route_id":"route-3",
                "verdict":"%s"
              }
              """.formatted(alias.getKey()));

      List<String> actions =
          StructuredPayloadNormalizer.normalize(payload, ToolAuditReport.class);
      ToolAuditReport report = ContractObjectMapper.read(payload, ToolAuditReport.class);

      assertEquals(alias.getValue(), report.verdict());
      assertEquals(
          List.of(
              "canonicalized tool-audit verdict "
                  + alias.getKey()
                  + " to "
                  + alias.getValue()),
          actions);
    }
  }

  @Test
  void doesNotInferAToolAuditVerdictFromNarrativeOrConfidence() {
    ObjectNode payload =
        object(
            """
            {
              "agent_id":"tool-specialist",
              "all_results_replayed_independently":true,
              "confidence":0.99,
              "experiment_ids":[],
              "issues":[],
              "mathematical_mapping_checked":true,
              "replay_artifact_refs":[],
              "route_id":"route-4",
              "verdict":"The bounded calculation was reproduced, but it is not a proof."
            }
            """);

    assertTrue(
        StructuredPayloadNormalizer.normalize(payload, ToolAuditReport.class).isEmpty());
    assertThrows(
        ContractValidationException.class,
        () -> ContractObjectMapper.read(payload, ToolAuditReport.class));
  }

  @Test
  void unknownMetaMechanismIsNotReplacedByGenericFallback() {
    ObjectNode payload =
        object(
            """
            {
              "round_index":3,
              "action":"invent_auxiliary_construction",
              "affected_route_ids":["route-a"],
              "selected_mechanism":"invent_untyped_object",
              "reason":"An untyped suggestion."
            }
            """);
    assertTrue(
        StructuredPayloadNormalizer.normalize(payload, MetaStrategyDecision.class).isEmpty());
    assertThrows(
        ContractValidationException.class,
        () -> ContractObjectMapper.read(payload, MetaStrategyDecision.class));
  }

  @Test
  void dependencyRefAliasesUsePayloadNamespacesAndAreAudited() {
    ObjectNode payload =
        object(
            """
            {
              "attempt_id":"attempt-a",
              "claims":[
                {
                  "claim_id":"claim-base",
                  "statement":"The base relation holds.",
                  "conclusion":"The base relation holds.",
                  "proof_steps":[
                    {
                      "step_id":"step-base",
                      "statement":"Establish the base relation.",
                      "justification":"By the stated assumptions."
                    }
                  ]
                },
                {
                  "claim_id":"claim-derived",
                  "statement":"The derived relation holds.",
                  "conclusion":"The derived relation holds.",
                  "dependencies":["claim-base"],
                  "dependency_refs":[
                    {"kind":"external","target_id":"claim-base"},
                    {"kind":"external","target_id":"step-base"},
                    {"kind":"external","target_id":"artifact://result/certificate.json"}
                  ]
                }
              ],
              "summary":"Two reusable relations were extracted."
            }
            """);
    List<String> actions = StructuredPayloadNormalizer.normalize(payload, ClaimBatch.class);
    ClaimBatch batch = ContractObjectMapper.read(payload, ClaimBatch.class);
    List<JsonNode> refs = batch.claims().get(1).dependencyRefs();
    assertEquals(
        List.of("local_claim", "local_step", "external_result"),
        refs.stream().map(item -> item.get("kind").textValue()).toList());
    assertEquals(
        List.of(
            "legacy_external_to_local_claim",
            "legacy_external_to_local_step",
            "legacy_external_to_external_result"),
        refs.stream().map(item -> item.get("kind_migration").textValue()).toList());
    assertEquals(3, actions.size());
    assertTrue(actions.stream().allMatch(action -> action.contains("external to ")));
  }

  @Test
  void modelCannotSupplyServerOwnedDependencyMigrationAudit() {
    ObjectNode payload =
        object(
            """
            {
              "dependency_refs":[
                {
                  "kind":"local_claim",
                  "target_id":"claim-base",
                  "kind_migration":"legacy_external_to_local_claim"
                }
              ]
            }
            """);
    StructuredPayloadNormalizer.stripServerOwnedHashes(payload);
    assertFalse(payload.get("dependency_refs").get(0).has("kind_migration"));
  }

  private static ObjectNode computationTurnPayload() {
    return object(
        """
        {
          "action":"request_computation",
          "experiment_spec":{
            "purpose":"discover_pattern",
            "target_claim":"Identify a candidate relation in a finite sample.",
            "reasoning_basis":"A bounded sample may suggest a route.",
            "why_computation_is_needed":"No candidate relation is known yet.",
            "decision_if_confirmed":"Submit the candidate for proof.",
            "decision_if_refuted":"Discard the candidate.",
            "noncomputational_alternative":"Continue symbolic exploration.",
            "method":"bounded_integer_search",
            "domains":{"x":{"min":0,"max":4}},
            "arguments":{"expression":"x"},
            "exact_arithmetic":true,
            "broad_search":false,
            "max_cases":5
          },
          "experiment_impact":"execution",
          "reason":"Use a bounded exploratory sample."
        }
        """);
  }

  private static ObjectNode localLetBindingPayload() {
    return object(
        """
        {
          "claim_id":"claim-local-let",
          "local_assumption_node_ids":[],
          "local_assumptions":["m=a+b"],
          "quantifiers":[
            {
              "display_name":"m",
              "domain":"positive integer",
              "kind":"let",
              "order":0,
              "restrictions":["m=a+b"],
              "variable_id":"m"
            }
          ],
          "variable_bindings":[
            {
              "aliases":["m"],
              "display_name":"m",
              "domain":"positive integer",
              "owner_scope":"claim_local",
              "variable_id":"m"
            }
          ],
          "scope_limitations":[],
          "polarity":"positive"
        }
        """);
  }

  private static ObjectNode strategyPolarityPayload(String polarity) {
    ObjectNode payload =
        object(
            """
            {
              "coverage_notes":"One auditable mechanism is represented.",
              "strategies":[
                {
                  "strategy_id":"S-polarity",
                  "title":"Exact relation route",
                  "core_idea":"Prove one exact local relation.",
                  "bottleneck":"Establish the required relation.",
                  "estimated_success":0.7,
                  "falsification_test":"Search for a bounded counterexample.",
                  "independence_basis":"Uses a distinct exact relation.",
                  "critical_claim_context_bindings":[
                    {
                      "claim_id":"claim-relation",
                      "claim_blueprint_node_id":"@claim",
                      "local_assumption_node_ids":[],
                      "local_assumptions":[],
                      "quantifiers":[],
                      "variable_bindings":[],
                      "scope_limitations":[],
                      "polarity":"positive"
                    }
                  ]
                }
              ]
            }
            """);
    ((ObjectNode)
            payload
                .path("strategies")
                .get(0)
                .path("critical_claim_context_bindings")
                .get(0))
        .put("polarity", polarity);
    return payload;
  }

  private static ObjectNode quantifier(ObjectNode payload) {
    return (ObjectNode) payload.path("quantifiers").get(0);
  }

  private static ObjectNode binding(ObjectNode payload) {
    return (ObjectNode) payload.path("variable_bindings").get(0);
  }

  private static ObjectNode object(String json) {
    return (ObjectNode) ContractObjectMapper.parseTree(json);
  }
}
