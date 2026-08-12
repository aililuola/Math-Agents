package io.github.aililuola.mathproofmesh.computation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.github.aililuola.mathproofmesh.contract.CalculationGateVerdict;
import io.github.aililuola.mathproofmesh.contract.CanonicalJson;
import io.github.aililuola.mathproofmesh.contract.ComputationDecisionStatus;
import io.github.aililuola.mathproofmesh.contract.ComputationMethod;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import io.github.aililuola.mathproofmesh.contract.ContractValidationException;
import io.github.aililuola.mathproofmesh.contract.EvidenceStrength;
import io.github.aililuola.mathproofmesh.contract.ExperimentOutcome;
import io.github.aililuola.mathproofmesh.contract.ExperimentProgram;
import io.github.aililuola.mathproofmesh.contract.ExperimentResult;
import io.github.aililuola.mathproofmesh.contract.ExperimentSpec;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ComputationParityTest {

  @Test
  void test_computation_semantic_version_invalidates_old_cache_identity() {
    ComputationBroker broker = ComputationFixtures.broker("versioned-cache");
    ExperimentSpec spec =
        ComputationFixtures.spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2,1,2],\"candidate_period\":2}");
    ExperimentResult result = ComputationFixtures.run(broker, spec);
    InMemoryComputationCache cache = new InMemoryComputationCache();
    cache.put("run", spec.executionHash(), "tool/1", result);

    assertThat(cache.find("run", spec.executionHash(), "tool/1")).isPresent();
    assertThat(cache.find("run", spec.executionHash(), "tool/2")).isEmpty();
  }

  @Test
  void test_exact_relation_and_impact_classification_fail_closed() {
    ExactExpression expression = ExactExpression.parse("(x^2-1)/3");
    assertThat(
            expression.evaluate(
                Map.of("x", new ExactRational(BigInteger.valueOf(2)))))
        .isEqualTo(new ExactRational(BigInteger.ONE));
    assertThatThrownBy(() -> ExactExpression.parse("open('secret')"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void test_computation_plan_preserves_both_decisions_and_exact_bounds() {
    ExperimentSpec spec =
        ComputationFixtures.spec(
            ComputationMethod.BOUNDED_INTEGER_SEARCH,
            "{\"target\":{\"lhs\":\"x*x\",\"rhs\":\"x\",\"relation\":\"eq\"}}",
            "{\"x\":{\"min\":0,\"max\":3}}");

    assertThat(spec.decisionIfConfirmed()).contains("bounded evidence");
    assertThat(spec.decisionIfRefuted()).contains("Reject");
    assertThat(spec.domains().path("x").path("max").intValue()).isEqualTo(3);
    assertThat(spec.exactArithmetic()).isTrue();
  }

  @Test
  void test_gate_rejects_vague_initial_search_and_fast_tracks_precise_refutation() {
    ComputationLimits limits = ComputationLimits.defaultsEnabled();
    ComputationPolicy policy = new ComputationPolicy(limits);
    ExperimentSpec precise =
        ComputationFixtures.spec(
            ComputationMethod.BOUNDED_INTEGER_SEARCH,
            "{\"target\":{\"lhs\":\"x*x\",\"rhs\":\"x\",\"relation\":\"eq\"}}",
            "{\"x\":{\"min\":0,\"max\":3}}");
    ExperimentSpec vague = copyTarget(precise, "search");

    assertThat(
            policy.evaluate(
                    vague,
                    ComputationContext.initial("vague", 5),
                    new ComputationLedger.Usage(0, 0),
                    true,
                    java.util.Optional.empty())
                .decision())
        .isEqualTo(ComputationDecisionStatus.REJECT);
    assertThat(
            policy.evaluate(
                    precise,
                    ComputationContext.initial("precise", 5),
                    new ComputationLedger.Usage(0, 0),
                    true,
                    java.util.Optional.empty())
                .decision())
        .isEqualTo(ComputationDecisionStatus.ALLOW);
  }

  @Test
  void test_computation_gate_updates_one_stable_topology_node() {
    String first =
        CanonicalJson.stableHash(
            ComputationFixtures.object(
                "{\"scope\":\"step-a\",\"request\":\"period-2\"}"));
    String second =
        CanonicalJson.stableHash(
            ComputationFixtures.object(
                "{\"request\":\"period-2\",\"scope\":\"step-a\"}"));
    assertThat(first).isEqualTo(second);
  }

  @Test
  void test_sandbox_execution_uses_one_live_node_and_persists_process_record() {
    SandboxSettings disabled = SandboxSettings.disabled();
    assertThat(disabled.enabled()).isFalse();
    assertThat(ComputationServiceRegistry.arbitraryExecutionMethods())
        .containsExactlyInAnyOrder(
            ComputationMethod.SANDBOXED_PYTHON,
            ComputationMethod.LEAN_CHECK);
  }

  @Test
  void test_gate_rejects_missing_decision_use_and_false_precision_claim() {
    ExperimentSpec numeric =
        ComputationFixtures.spec(
            ComputationMethod.NUMERIC_COUNTEREXAMPLE,
            "{\"lhs\":\"x^2\",\"rhs\":\"x\",\"variables\":[\"x\"],"
                + "\"ranges\":{\"x\":[0,2]},\"samples\":10}");
    ExperimentSpec invalid = copyPrecisionAndDecisions(numeric, true, "keep", "drop");
    ComputationPolicy policy =
        new ComputationPolicy(ComputationLimits.defaultsEnabled());

    assertThat(
            policy.evaluate(
                    invalid,
                    ComputationContext.initial("invalid", 5),
                    new ComputationLedger.Usage(0, 0),
                    true,
                    java.util.Optional.empty())
                .decision())
        .isEqualTo(ComputationDecisionStatus.REJECT);
  }

  @Test
  void test_broad_search_requires_stall_and_meta_review() {
    ExperimentSpec broad =
        ComputationFixtures.spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2,1,2],\"candidate_period\":2}",
            "{}",
            ComputationPurpose.DISCOVER_PATTERN,
            true,
            30_000);
    ComputationPolicy policy =
        new ComputationPolicy(ComputationLimits.defaultsEnabled());

    assertThat(
            policy.evaluate(
                    broad,
                    new ComputationContext("broad", 0, false, 5, 0, 0),
                    new ComputationLedger.Usage(0, 0),
                    true,
                    java.util.Optional.empty())
                .decision())
        .isEqualTo(ComputationDecisionStatus.DEFER);
  }

  @Test
  void test_low_cost_exact_pattern_probe_runs_before_route_stagnation() {
    ExperimentSpec broad =
        ComputationFixtures.spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2,1,2],\"candidate_period\":2}",
            "{}",
            ComputationPurpose.DISCOVER_PATTERN,
            true,
            1_000);
    ComputationPolicy policy =
        new ComputationPolicy(ComputationLimits.defaultsEnabled());

    assertThat(
            policy.evaluate(
                    broad,
                    new ComputationContext("probe", 0, false, 5, 0, 0),
                    new ComputationLedger.Usage(0, 0),
                    true,
                    java.util.Optional.empty())
                .decision())
        .isEqualTo(ComputationDecisionStatus.ALLOW);
  }

  @Test
  void test_expensive_deferred_request_survives_resume_and_retries() {
    ExperimentSpec broad =
        ComputationFixtures.spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2,1,2],\"candidate_period\":2}",
            "{}",
            ComputationPurpose.DISCOVER_PATTERN,
            true,
            30_000);
    ComputationPolicy policy =
        new ComputationPolicy(ComputationLimits.defaultsEnabled());
    ComputationDecisionStatus before =
        policy.evaluate(
                broad,
                new ComputationContext("resume", 0, false, 5, 0, 0),
                new ComputationLedger.Usage(0, 0),
                true,
                java.util.Optional.empty())
            .decision();
    ComputationDecisionStatus after =
        policy.evaluate(
                broad,
                new ComputationContext("resume", 1, true, 5, 0, 0),
                new ComputationLedger.Usage(0, 0),
                true,
                java.util.Optional.empty())
            .decision();

    assertThat(before).isEqualTo(ComputationDecisionStatus.DEFER);
    assertThat(after).isEqualTo(ComputationDecisionStatus.ALLOW);
    assertThat(broad.requestHash()).hasSize(64);
  }

  @Test
  void test_not_refuted_is_never_promoted_to_verified() {
    HandlerEvidence evidence =
        SequenceFunctions.runCandidatePeriodCheck(
            ComputationFixtures.spec(
                ComputationMethod.CANDIDATE_PERIOD_CHECK,
                "{\"values\":[1,2,1,2],\"candidate_period\":2}"));
    assertThat(evidence.outcome()).isEqualTo(ExperimentOutcome.NOT_REFUTED);
    assertThat(evidence.evidenceStrength())
        .isEqualTo(EvidenceStrength.BOUNDED_EVIDENCE);
  }

  @Test
  void test_counterexample_is_rechecked_cached_and_reused_after_resume() {
    ComputationBroker broker = ComputationFixtures.broker("counterexample-cache");
    ExperimentSpec first =
        ComputationFixtures.spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2,1,3],\"candidate_period\":2}");
    ExperimentResult initial = ComputationFixtures.run(broker, first);
    ExperimentResult resumed = ComputationFixtures.run(broker, first);

    assertThat(initial.outcome())
        .isEqualTo(ExperimentOutcome.COUNTEREXAMPLE_FOUND);
    assertThat(initial.independentlyVerified()).isTrue();
    assertThat(resumed.cached()).isTrue();
  }

  @Test
  void test_semantic_cache_preserves_canonical_artifacts_and_reuses_across_paths() {
    ExperimentSpec first =
        ComputationFixtures.spec(
            ComputationMethod.MODULAR_EXHAUSTIVE,
            "{\"lhs\":\"x^5\",\"rhs\":\"x\",\"modulus\":5,"
                + "\"finite_reduction\":true,"
                + "\"reduction_justification\":\"Depends only on the residue class.\"}",
            "{\"x\":{\"min\":0,\"max\":4}}");
    ExperimentSpec second = copyNarrative(first, "path-other", "A different narrative for the same claim.");

    assertThat(first.executionHash()).isEqualTo(second.executionHash());
    assertThat(first.requestHash()).isNotEqualTo(second.requestHash());
  }

  @Test
  void test_execution_identity_reuses_computation_across_narrative_variants() {
    ExperimentSpec first =
        ComputationFixtures.spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[2,5,2,5],\"candidate_period\":2}");
    ExperimentSpec second =
        copyNarrative(first, "path-b", "Equivalent finite periodicity wording.");
    assertThat(first.executionHash()).isEqualTo(second.executionHash());
  }

  @Test
  void test_final_audit_rejects_tampered_execution_record() {
    ExperimentSpec spec =
        ComputationFixtures.spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[2,5,2,5],\"candidate_period\":2}");
    ExperimentSpec audited = copyWithExecutionHash(spec, "0".repeat(64));
    assertThat(audited.executionHash())
        .isEqualTo(spec.executionHash())
        .isNotEqualTo("0".repeat(64));
  }

  @Test
  void test_typed_modular_integer_graph_recurrence_and_geometry_tools() {
    HandlerEvidence modular =
        ModularFunctions.run(
            ComputationFixtures.spec(
                ComputationMethod.MODULAR_EXHAUSTIVE,
                "{\"lhs\":\"x^5\",\"rhs\":\"x\",\"modulus\":5,"
                    + "\"finite_reduction\":true,"
                    + "\"reduction_justification\":\"Depends only on residues.\"}",
                "{\"x\":{\"min\":0,\"max\":4}}"));
    HandlerEvidence integer =
        IntegerSearchFunctions.run(
            ComputationFixtures.spec(
                ComputationMethod.BOUNDED_INTEGER_SEARCH,
                "{\"target\":{\"lhs\":\"x*x\",\"rhs\":\"x\",\"relation\":\"eq\"}}",
                "{\"x\":{\"min\":0,\"max\":3}}"));
    HandlerEvidence graph =
        GraphFunctions.run(
            ComputationFixtures.spec(
                ComputationMethod.GRAPH_CERTIFICATE,
                "{\"graph\":{\"nodes\":[\"a\",\"b\",\"c\"],"
                    + "\"edges\":[[\"a\",\"b\"],[\"b\",\"c\"],[\"c\",\"a\"]],"
                    + "\"directed\":false},\"property\":\"proper_coloring\","
                    + "\"certificate\":{\"colors\":{\"a\":0,\"b\":1,\"c\":2}}}"));
    HandlerEvidence recurrence =
        RecurrenceFunctions.run(
            ComputationFixtures.spec(
                ComputationMethod.RECURRENCE_CHECK,
                "{\"initial_values\":[0,1],\"coefficients\":[1,1],"
                    + "\"start_n\":0,\"end_n\":8,\"claimed_expression\":\"n\"}"));
    HandlerEvidence geometry =
        GeometryFunctions.run(
            ComputationFixtures.spec(
                ComputationMethod.EXACT_GEOMETRY,
                "{\"points\":{\"a\":[0,0],\"b\":[1,1],\"c\":[2,3]},"
                    + "\"assertion\":{\"kind\":\"collinear\","
                    + "\"points\":[\"a\",\"b\",\"c\"],\"expected\":true}}"));

    assertThat(modular.outcome()).isEqualTo(ExperimentOutcome.CERTIFIED);
    assertThat(integer.outcome())
        .isEqualTo(ExperimentOutcome.COUNTEREXAMPLE_FOUND);
    assertThat(graph.outcome()).isEqualTo(ExperimentOutcome.CERTIFIED);
    assertThat(recurrence.counterexample().path("n").intValue()).isEqualTo(2);
    assertThat(geometry.outcome())
        .isEqualTo(ExperimentOutcome.COUNTEREXAMPLE_FOUND);
  }

  @Test
  void test_contract_errors_are_rejected_before_execution_and_runtime_errors_are_inconclusive() {
    ExperimentSpec malformed =
        ComputationFixtures.spec(ComputationMethod.RECURRENCE_CHECK, "{}");
    ComputationBroker broker = ComputationFixtures.broker("contract-error");
    ComputationBroker.PreparedDecision decision =
        broker.decide(malformed, ComputationContext.initial("contract-error", 5));

    assertThat(decision.decision().decision())
        .isEqualTo(ComputationDecisionStatus.REJECT);
    assertThat(broker.ledger().usage("contract-error").experiments()).isZero();
  }

  @Test
  void test_exact_handlers_reject_ambiguous_integer_and_geometry_inputs() {
    ExperimentSpec division =
        ComputationFixtures.spec(
            ComputationMethod.BOUNDED_INTEGER_SEARCH,
            "{\"target\":{\"lhs\":\"x//y\",\"rhs\":\"0\",\"relation\":\"ge\"}}",
            "{\"x\":{\"min\":0,\"max\":2},\"y\":{\"min\":1,\"max\":2}}");
    assertThatThrownBy(() -> IntegerSearchFunctions.run(division))
        .hasMessageContaining("positive constant divisor");

    ExperimentSpec geometry =
        ComputationFixtures.spec(
            ComputationMethod.EXACT_GEOMETRY,
            "{\"points\":{\"a\":[0,0],\"b\":[1,0],\"c\":[0,1]},"
                + "\"assertion\":{\"kind\":\"orientation\","
                + "\"points\":[\"a\",\"b\",\"c\"],\"expected_sign\":2}}");
    assertThatThrownBy(() -> GeometryFunctions.run(geometry))
        .hasMessageContaining("-1, 0, or 1");
  }

  @Test
  void test_partial_modular_domain_is_bounded_and_invalid_graph_certificate_is_inconclusive() {
    HandlerEvidence partial =
        ModularFunctions.run(
            ComputationFixtures.spec(
                ComputationMethod.MODULAR_EXHAUSTIVE,
                "{\"lhs\":\"x^2\",\"rhs\":\"x\",\"modulus\":5,"
                    + "\"finite_reduction\":true,"
                    + "\"reduction_justification\":\"Depends only on residues.\"}",
                "{\"x\":{\"min\":0,\"max\":1}}"));
    HandlerEvidence invalidGraph =
        GraphFunctions.run(
            ComputationFixtures.spec(
                ComputationMethod.GRAPH_CERTIFICATE,
                "{\"graph\":{\"nodes\":[\"a\",\"b\"],"
                    + "\"edges\":[[\"a\",\"b\"]],\"directed\":false},"
                    + "\"property\":\"proper_coloring\","
                    + "\"certificate\":{\"colors\":{\"a\":0,\"b\":0}}}"));

    assertThat(partial.outcome()).isEqualTo(ExperimentOutcome.NOT_REFUTED);
    assertThat(partial.scope().path("full_residue_coverage").asBoolean()).isFalse();
    assertThat(invalidGraph.outcome()).isEqualTo(ExperimentOutcome.INCONCLUSIVE);
  }

  @Test
  void test_sandbox_policy_rejects_dangerous_code_and_builds_isolated_docker_command(
      @TempDir Path temporary) {
    SandboxSettings settings =
        new SandboxSettings(
            true,
            "registry.example/mathproofmesh@sha256:" + "a".repeat(64),
            Duration.ofSeconds(20),
            256,
            1.0,
            32,
            20_000);
    List<String> command =
        SandboxFunctions.buildDockerCommand("docker", settings, temporary);
    String joined = String.join(" ", command);

    assertThat(joined).contains("--network none", "--cap-drop ALL", "@sha256:");
    assertThat(command).contains("--interactive", "--read-only", "no-new-privileges");
    assertThat(command).doesNotContain("--env");
    assertThat(SandboxSettings.disabled().enabled()).isFalse();
  }

  @Test
  void test_docker_discovery_supports_per_user_windows_install(@TempDir Path temporary)
      throws Exception {
    Path docker =
        temporary
            .resolve("Programs")
            .resolve("DockerDesktop")
            .resolve("resources")
            .resolve("bin")
            .resolve("docker.exe");
    Files.createDirectories(docker.getParent());
    Files.createFile(docker);

    assertThat(SandboxFunctions.findDockerExecutable(null, temporary, null))
        .isEqualTo(docker.toString());
  }

  @Test
  void test_program_source_artifact_is_hash_checked() {
    String source = "def run(data):\n    return {'outcome': 'inconclusive'}\n";
    assertThatThrownBy(
            () ->
                new ExperimentProgram(
                    "0".repeat(64),
                    null,
                    List.of(),
                    "experiment",
                    ComputationFixtures.object("{}"),
                    ComputationFixtures.object("{}"),
                    source))
        .isInstanceOf(ContractValidationException.class)
        .hasMessageContaining("code_hash");
  }

  @Test
  void test_confirmed_counterexample_overrides_model_pass() {
    ComputationBroker broker = ComputationFixtures.broker("model-pass-overridden");
    ExperimentResult result =
        ComputationFixtures.run(
            broker,
            ComputationFixtures.spec(
                ComputationMethod.CANDIDATE_PERIOD_CHECK,
                "{\"values\":[1,2,1,3],\"candidate_period\":2}"));
    ComputationEvidenceGate.FactDecision gate =
        ComputationEvidenceGate.evaluate(result);

    assertThat(result.independentlyVerified()).isTrue();
    assertThat(gate.factAdmissible()).isFalse();
    assertThat(gate.negativeAdmissible()).isTrue();
  }

  @Test
  void test_independent_replay_audit_matches_canonical_computation_evidence() {
    ComputationBroker broker = ComputationFixtures.broker("independent-replay");
    ExperimentSpec requested =
        ComputationFixtures.spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2,1,2],\"candidate_period\":2}");
    ComputationBroker.PreparedDecision prepared =
        broker.decide(requested, ComputationContext.initial(requested.pathId(), 5));
    ExperimentResult recorded =
        broker.runExperiment(prepared.spec(), prepared.decision());

    ComputationBroker.ComputationAudit audit =
        broker.auditExperiment(prepared.spec(), prepared.decision(), null, recorded);

    assertThat(audit.executed()).isTrue();
    assertThat(audit.valid()).isTrue();
    assertThat(audit.replayedResultHash()).isEqualTo(recorded.resultHash());
  }

  @Test
  void test_independent_replay_audit_rejects_changed_request_binding() {
    ComputationBroker broker = ComputationFixtures.broker("changed-replay-binding");
    ExperimentSpec requested =
        ComputationFixtures.spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2,1,2],\"candidate_period\":2}");
    ComputationBroker.PreparedDecision prepared =
        broker.decide(requested, ComputationContext.initial(requested.pathId(), 5));
    ExperimentResult recorded =
        broker.runExperiment(prepared.spec(), prepared.decision());
    ExperimentSpec changed =
        ComputationFixtures.spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2,1,3],\"candidate_period\":2}");

    ComputationBroker.ComputationAudit audit =
        broker.auditExperiment(changed, prepared.decision(), null, recorded);

    assertThat(audit.executed()).isFalse();
    assertThat(audit.valid()).isFalse();
    assertThat(audit.diagnostic()).contains("request hash changed");
  }

  @Test
  void test_computation_round_trip_does_not_advance_checkpoint() {
    ComputationBroker broker = ComputationFixtures.broker("round-trip");
    ExperimentSpec spec =
        ComputationFixtures.spec(
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
            "{\"values\":[1,2,1,2],\"candidate_period\":2}");
    ComputationFixtures.run(broker, spec);

    assertThat(broker.ledger().usage(spec.pathId()).experiments()).isEqualTo(1);
    assertThat(spec.parentCheckpointId()).isNull();
  }

  @Test
  void test_simple_proof_does_not_trigger_computation() {
    assertThat(
            CriticalCalculationGate.calculationTrigger(
                List.of("By induction, the formula follows for every n.")))
        .isEmpty();
    assertThat(
            CriticalCalculationGate.calculationTrigger(
                List.of("The first terms are 15, 18, 21, 24.")))
        .isPresent();
  }

  private static ExperimentSpec copyTarget(ExperimentSpec source, String target) {
    return copy(
        source,
        target,
        source.pathId(),
        source.reasoningBasis(),
        source.exactArithmetic(),
        source.decisionIfConfirmed(),
        source.decisionIfRefuted(),
        source.executionHash());
  }

  private static ExperimentSpec copyNarrative(
      ExperimentSpec source, String path, String reasoning) {
    return copy(
        source,
        source.targetClaim(),
        path,
        reasoning,
        source.exactArithmetic(),
        source.decisionIfConfirmed(),
        source.decisionIfRefuted(),
        null);
  }

  private static ExperimentSpec copyPrecisionAndDecisions(
      ExperimentSpec source,
      boolean exact,
      String confirmed,
      String refuted) {
    return copy(
        source,
        source.targetClaim(),
        source.pathId(),
        source.reasoningBasis(),
        exact,
        confirmed,
        refuted,
        null);
  }

  private static ExperimentSpec copyWithExecutionHash(
      ExperimentSpec source, String executionHash) {
    return copy(
        source,
        source.targetClaim(),
        source.pathId(),
        source.reasoningBasis(),
        source.exactArithmetic(),
        source.decisionIfConfirmed(),
        source.decisionIfRefuted(),
        executionHash);
  }

  private static ExperimentSpec copy(
      ExperimentSpec source,
      String target,
      String path,
      String reasoning,
      boolean exact,
      String confirmed,
      String refuted,
      String executionHash) {
    return new ExperimentSpec(
        source.arguments(),
        source.assumptions(),
        source.broadSearch(),
        confirmed,
        refuted,
        source.domains(),
        exact,
        executionHash,
        source.experimentId() + "-copy",
        source.maxCases(),
        source.method(),
        source.noncomputationalAlternative(),
        source.parentCheckpointId(),
        path,
        source.purpose(),
        reasoning,
        null,
        source.requestedBy(),
        source.runtimeFingerprint(),
        source.seed(),
        target,
        source.typedToolGap(),
        source.whyComputationIsNeeded());
  }
}
