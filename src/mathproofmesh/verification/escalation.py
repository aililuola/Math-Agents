from __future__ import annotations

from enum import StrEnum
import inspect
from typing import Awaitable, Callable, Iterable, Mapping

from pydantic import Field

from ..config import ValidationEscalationConfig
from ..schemas import StrictModel


class ValidationLevel(StrEnum):
    DETERMINISTIC = "deterministic"
    BLIND_SAME_MODEL = "blind_same_model"
    ADVERSARIAL_BLIND = "adversarial_blind"
    CROSS_PROVIDER = "cross_provider"
    TOOL_OR_FORMAL = "tool_or_formal"


class EscalationPlan(StrictModel):
    risk_score: float = Field(ge=0.0, le=1.0)
    levels: list[ValidationLevel]
    diagnostics: list[str] = Field(default_factory=list)
    blocks_fact_promotion: bool = False


class ValidationStepResult(StrictModel):
    level: ValidationLevel
    executed: bool
    passed: bool
    evidence_refs: list[str] = Field(default_factory=list)
    diagnostic: str = ""


class ValidationExecution(StrictModel):
    plan: EscalationPlan
    steps: list[ValidationStepResult] = Field(default_factory=list)
    passed: bool = False
    fact_promotion_allowed: bool = False
    diagnostics: list[str] = Field(default_factory=list)


ValidationHandler = Callable[
    [], bool | ValidationStepResult | Awaitable[bool | ValidationStepResult]
]


class ValidationEscalator:
    """Deterministic-first review ladder with explicit safe degradation."""

    def __init__(self, config: ValidationEscalationConfig) -> None:
        self.config = config

    def plan(
        self,
        *,
        risk_score: float,
        reviewer_verdicts: Iterable[str] = (),
        cross_provider_available: bool = False,
        tool_or_formal_available: bool = False,
        before_fact_promotion: bool = False,
        final_proof: bool = False,
    ) -> EscalationPlan:
        if not self.config.enabled:
            return EscalationPlan(risk_score=risk_score, levels=[])
        levels: list[ValidationLevel] = []
        diagnostics: list[str] = []
        if self.config.deterministic_checks_first:
            levels.append(ValidationLevel.DETERMINISTIC)
        verdicts = {item for item in reviewer_verdicts if item}
        disagreement = len(verdicts) > 1
        high_risk = risk_score >= self.config.high_risk_threshold
        should_escalate = (
            high_risk
            or (disagreement and self.config.escalate_on_reviewer_disagreement)
            or (before_fact_promotion and self.config.escalate_before_fact_promotion)
            or (final_proof and self.config.escalate_final_proof)
        )
        if should_escalate and self.config.blind_same_model_review:
            levels.append(ValidationLevel.BLIND_SAME_MODEL)
        if should_escalate and self.config.adversarial_prompt_review:
            levels.append(ValidationLevel.ADVERSARIAL_BLIND)
        if should_escalate and self.config.cross_provider_review:
            if cross_provider_available:
                levels.append(ValidationLevel.CROSS_PROVIDER)
            else:
                diagnostics.append(
                    "cross-provider reviewer unavailable; using adversarial/tool fallback"
                )
        if (
            high_risk
            and self.config.tool_or_formal_check_on_high_risk
            and tool_or_formal_available
        ):
            levels.append(ValidationLevel.TOOL_OR_FORMAL)
        elif high_risk and self.config.tool_or_formal_check_on_high_risk:
            diagnostics.append(
                "tool/formal backend unavailable; result remains pending"
            )
        return EscalationPlan(
            risk_score=risk_score,
            levels=list(dict.fromkeys(levels)),
            diagnostics=diagnostics,
            blocks_fact_promotion=(before_fact_promotion and should_escalate),
        )


class ValidationEscalationExecutor:
    """Execute every admitted validation level and fail closed on missing evidence."""

    async def execute(
        self,
        plan: EscalationPlan,
        handlers: Mapping[ValidationLevel, ValidationHandler],
    ) -> ValidationExecution:
        steps: list[ValidationStepResult] = []
        diagnostics = list(plan.diagnostics)
        for level in plan.levels:
            handler = handlers.get(level)
            if handler is None:
                result = ValidationStepResult(
                    level=level,
                    executed=False,
                    passed=False,
                    diagnostic=f"required {level.value} validation was not executed",
                )
            else:
                try:
                    value = handler()
                    if inspect.isawaitable(value):
                        value = await value
                    result = (
                        value
                        if isinstance(value, ValidationStepResult)
                        else ValidationStepResult(
                            level=level,
                            executed=True,
                            passed=bool(value),
                        )
                    )
                    if result.level != level:
                        result = result.model_copy(update={"level": level})
                except Exception as exc:  # validation failure is evidence, not a crash
                    result = ValidationStepResult(
                        level=level,
                        executed=True,
                        passed=False,
                        diagnostic=f"{type(exc).__name__}: {exc}",
                    )
            steps.append(result)
            if result.diagnostic:
                diagnostics.append(result.diagnostic)
        required_backend_missing = any(
            "remains pending" in diagnostic for diagnostic in diagnostics
        )
        passed = all(step.executed and step.passed for step in steps)
        passed = passed and not required_backend_missing
        return ValidationExecution(
            plan=plan,
            steps=steps,
            passed=passed,
            fact_promotion_allowed=(passed if plan.blocks_fact_promotion else True),
            diagnostics=diagnostics,
        )
