from __future__ import annotations

from mathproofmesh.verification.escalation import ValidationEscalator, ValidationLevel

from v07_helpers import make_v07_config


def test_high_risk_fact_promotion_uses_full_available_ladder(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    config.topology.validation_escalation.cross_provider_review = True
    plan = ValidationEscalator(config.topology.validation_escalation).plan(
        risk_score=0.9,
        reviewer_verdicts=["pass", "fail"],
        cross_provider_available=True,
        tool_or_formal_available=True,
        before_fact_promotion=True,
    )
    assert plan.blocks_fact_promotion
    assert plan.levels == [
        ValidationLevel.DETERMINISTIC,
        ValidationLevel.BLIND_SAME_MODEL,
        ValidationLevel.ADVERSARIAL_BLIND,
        ValidationLevel.CROSS_PROVIDER,
        ValidationLevel.TOOL_OR_FORMAL,
    ]


def test_missing_heterogeneous_provider_degrades_with_diagnostic(tmp_path) -> None:
    config = make_v07_config(tmp_path / "runs")
    config.topology.validation_escalation.cross_provider_review = True
    plan = ValidationEscalator(config.topology.validation_escalation).plan(
        risk_score=0.9,
        cross_provider_available=False,
        tool_or_formal_available=False,
        final_proof=True,
    )
    assert ValidationLevel.CROSS_PROVIDER not in plan.levels
    assert ValidationLevel.ADVERSARIAL_BLIND in plan.levels
    assert any("unavailable" in item for item in plan.diagnostics)
