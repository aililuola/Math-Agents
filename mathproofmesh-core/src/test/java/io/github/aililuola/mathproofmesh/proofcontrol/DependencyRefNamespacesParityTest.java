package io.github.aililuola.mathproofmesh.proofcontrol;

import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class DependencyRefNamespacesParityTest {
  static Stream<String> authorityCases() {
    return Stream.of(
        "test_legacy_external_dependency_kind_restores_with_audit_metadata",
        "test_unknown_dependency_kind_still_fails_strict_validation",
        "test_legacy_alias_with_non_string_target_still_reports_validation_error",
        "test_legacy_external_kind_uses_explicit_local_namespace_context",
        "test_lemma_memory_contextually_migrates_legacy_claim_ref",
        "test_local_step_dependency_resolves_inside_delta",
        "test_local_claim_dependency_resolves_inside_attempt",
        "test_global_fact_requires_broker_admission",
        "test_ambiguous_legacy_dependency_does_not_auto_invalidate",
        "test_prefixed_dependency_migration_is_stable",
        "test_invalidated_local_step_invalidates_resolution",
        "test_local_step_dependency_cannot_cross_delta_scope",
        "test_dependency_namespace_resume_roundtrip",
        "test_legacy_external_kind_is_canonical_after_checkpoint_resume",
        "test_dependency_sidecar_does_not_change_claim_hash",
        "test_dependency_sidecar_does_not_change_step_checkpoint_payload");
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("authorityCases")
  void preservesAuthoritySemantics(String authorityFunction) {
    ProofControlParityScenarios.verify("DependencyRefNamespacesParityTest", authorityFunction);
  }
}
