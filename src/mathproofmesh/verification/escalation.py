from __future__ import annotations

from enum import StrEnum
from typing import Iterable

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
