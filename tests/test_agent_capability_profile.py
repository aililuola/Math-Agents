from __future__ import annotations

from mathproofmesh.schemas import ProofStep
from mathproofmesh.verification.capability_profile import AgentCapabilityProfile
from mathproofmesh.verification.mutation import (
    MutationKind,
    MutationResult,
    ProofMutationHarness,
)

from v07_helpers import make_v07_config


def test_capability_is_separate_by_domain_and_role_and_ignores_self_report(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs")
    config.topology.agent_capability.min_observations_before_trust_update = 1
    profile = AgentCapabilityProfile(config.topology.agent_capability)
    cell = profile.update(
        "reviewer",
        "number_theory",
        "detailed_verifier",
        kind="tool_agreement",
        success=True,
        self_reported_confidence=0.01,
    )
    assert cell.score == 1.0
    assert profile.ignored_self_reports == 1
    assert profile.score("reviewer", "geometry", "detailed_verifier") == 0.5
    assert profile.score("reviewer", "number_theory", "prover") == 0.5


def test_accepting_mutated_false_proof_lowers_verifier_capability(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    config.topology.agent_capability.min_observations_before_trust_update = 1
    profile = AgentCapabilityProfile(config.topology.agent_capability)
    harness = ProofMutationHarness()
    mutation = harness.mutate(
        ProofStep(
            step_id="s1",
            statement="For every n, a_n <= b_n.",
            justification="Established earlier.",
        ),
        MutationKind.ALTER_SIGN,
    )
    harness.record(
        MutationResult(
            mutation_id=mutation.mutation_id,
            agent_id="reviewer",
            detected=False,
            first_error_correct=False,
        ),
        domain="inequalities",
        role="detailed_verifier",
        profile=profile,
    )
    assert profile.score("reviewer", "inequalities", "detailed_verifier") == 0.0
