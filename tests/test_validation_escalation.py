from __future__ import annotations

from mathproofmesh.verification.escalation import (
    ValidationEscalationExecutor,
    ValidationEscalator,
    ValidationLevel,
)

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


async def test_executor_runs_every_level_and_fails_closed_when_one_is_missing(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs")
    plan = ValidationEscalator(config.topology.validation_escalation).plan(
        risk_score=0.8,
        tool_or_formal_available=True,
        before_fact_promotion=True,
    )
    calls: list[ValidationLevel] = []

    def pass_level(level: ValidationLevel) -> bool:
        calls.append(level)
        return True

    handlers = {
        level: (lambda current=level: pass_level(current))
        for level in plan.levels
        if level != ValidationLevel.ADVERSARIAL_BLIND
    }
    execution = await ValidationEscalationExecutor().execute(plan, handlers)
    assert not execution.passed
    assert not execution.fact_promotion_allowed
    assert ValidationLevel.ADVERSARIAL_BLIND not in calls
    assert any(
        step.level == ValidationLevel.ADVERSARIAL_BLIND and not step.executed
        for step in execution.steps
    )
