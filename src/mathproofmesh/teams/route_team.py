from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable

from ..config import SystemConfig
from ..cross_route_phase import distinct_agent_exclusions
from ..schemas import (
    BrokerDecision,
    ClaimStatus,
    MessageEnvelope,
    ProofAttempt,
    ProofDelta,
    RouteRole,
    ToolAuditReport,
    VerificationReport,
    VerificationVerdict,
)
from .role_runner import RoleAssignment, RoleRunner


@dataclass(frozen=True, slots=True)
class RiskAssessment:
    score: float
    reasons: tuple[str, ...]
    needs_skeptic: bool
    needs_tool: bool
    entering_global_fact_gate: bool


@dataclass(slots=True)
class RouteTeamPlan:
    route_id: str
    prover: RoleAssignment
    skeptic: RoleAssignment | None
    tool_specialist: RoleAssignment | None
    referee: RoleAssignment
    risk: RiskAssessment
    global_share_allowed: bool
    diagnostics: list[str] = field(default_factory=list)


@dataclass(slots=True)
class RouteTeamResult:
    route_id: str
    artifact: Any
    skeptic_result: Any = None
    tool_result: Any = None
    referee_result: Any = None
    global_share_allowed: bool = False
    diagnostics: list[str] = field(default_factory=list)


RoleHandler = Callable[[RoleAssignment, Any], Awaitable[Any]]


class RouteTeam:
    """Risk-directed Prover -> Skeptic/Tool -> independent Referee workflow."""

    def __init__(self, config: SystemConfig, role_runner: RoleRunner) -> None:
        self.config = config
        self.role_runner = role_runner

    def classify_risk(
        self,
        artifact: ProofAttempt | ProofDelta | MessageEnvelope | dict[str, Any],
        *,
        structural_verdict: str | None = None,
        repeated_first_error: bool = False,
        entering_global_fact_gate: bool = False,
    ) -> RiskAssessment:
        score = 0.0
        reasons: list[str] = []
        payload = (
            artifact.model_dump(mode="json")
            if hasattr(artifact, "model_dump")
            else dict(artifact)
        )
        serialized = str(payload).casefold()
        proof_steps = [
            *payload.get("proof_steps", []),
            *payload.get("new_steps", []),
        ]
        contains_key_step = any(step.get("is_key_step") for step in proof_steps)
        if contains_key_step:
            score += 0.18
            reasons.append("contains a key proof step")
        if "external:" in serialized or "theorem" in serialized:
            score += 0.16
            reasons.append("uses an external theorem")
        if payload.get("quantifiers") or any(
            marker in serialized for marker in ("forall", "exists", "\u2200", "\u2203")
        ):
            score += 0.14
            reasons.append("contains a quantifier transformation")
        evidence = str(payload.get("evidence_type", ""))
        if evidence in {"bounded_experiment", "numerical_heuristic"}:
            score += 0.30
            reasons.append("numerical evidence may be over-generalized")
        confidence = float(
            payload.get("self_confidence", payload.get("verification_confidence", 1.0))
            or 0.0
        )
        if confidence < 0.6:
            score += 0.16
            reasons.append("low local confidence")
        if structural_verdict in {"uncertain", "fail"}:
            score += 0.30
            reasons.append("structural review did not pass")
        if repeated_first_error:
            score += 0.18
            reasons.append("first-error location repeated")
        recovered_partial = "missing_final_answer_downgraded_to_partial" in payload.get(
            "normalization_notes", []
        )
        if recovered_partial:
            score += 0.30
            reasons.append(
                "structured complete response was salvaged as a partial delta"
            )
        dependencies = payload.get("dependencies", [])
        if dependencies and isinstance(artifact, MessageEnvelope):
            if artifact.verification_status != ClaimStatus.VERIFIED:
                score += 0.12
                reasons.append("depends on an unverified claim")
        if entering_global_fact_gate:
            score += 0.20
            reasons.append("artifact is entering global FactMemory")
        score = min(1.0, score)
        threshold = self.config.topology.route_teams.skeptic_risk_threshold
        needs_tool = evidence in {"bounded_experiment", "numerical_heuristic"} or bool(
            payload.get("tool_requests")
        )
        mandatory_skeptic = bool(
            contains_key_step
            or needs_tool
            or structural_verdict in {"uncertain", "fail"}
            or repeated_first_error
            or recovered_partial
            or entering_global_fact_gate
        )
        needs_skeptic = not self.config.topology.route_teams.skeptic_on_high_risk_only
        needs_skeptic = needs_skeptic or mandatory_skeptic or score >= threshold
        return RiskAssessment(
            score=score,
            reasons=tuple(reasons),
            needs_skeptic=needs_skeptic,
            needs_tool=needs_tool,
            entering_global_fact_gate=entering_global_fact_gate,
        )

    def plan(
        self,
        route_id: str,
        prover_agent_id: str,
        artifact: ProofAttempt | ProofDelta | MessageEnvelope | dict[str, Any],
        *,
        round_index: int,
        structural_verdict: str | None = None,
        repeated_first_error: bool = False,
        entering_global_fact_gate: bool = False,
    ) -> RouteTeamPlan:
        registry = self.role_runner.registry
        registry.assign_member(route_id, prover_agent_id, RouteRole.PROVER, round_index)
        prover = RoleAssignment(
            route_id=route_id,
            role=RouteRole.PROVER,
            agent_id=prover_agent_id,
            selected_via="existing_author",
        )
        risk = self.classify_risk(
            artifact,
            structural_verdict=structural_verdict,
            repeated_first_error=repeated_first_error,
            entering_global_fact_gate=entering_global_fact_gate,
        )
        skeptic = (
            self.role_runner.select(
                route_id,
                RouteRole.SKEPTIC,
                round_index=round_index,
                exclude={prover_agent_id},
            )
            if risk.needs_skeptic
            else None
        )
        tool = (
            self.role_runner.select(
                route_id,
                RouteRole.TOOL_SPECIALIST,
                round_index=round_index,
                exclude=distinct_agent_exclusions(
                    prover_agent_id,
                    skeptic.agent_id if skeptic is not None else None,
                ),
            )
            if risk.needs_tool and self.config.topology.route_teams.tool_agent_on_demand
            else None
        )
        referee_exclude = distinct_agent_exclusions(
            prover_agent_id,
            skeptic.agent_id if skeptic is not None else None,
            tool.agent_id if tool is not None else None,
        )
        referee = self.role_runner.select(
            route_id,
            RouteRole.REFEREE,
            round_index=round_index,
            exclude=referee_exclude,
        )
        diagnostics: list[str] = []
        global_share = referee.agent_id is not None
        if referee.agent_id is None:
            diagnostics.append("no independent referee; artifact remains route-local")
        if skeptic is not None and skeptic.agent_id is None:
            diagnostics.append("requested skeptic is unavailable")
            global_share = False
        if tool is not None and tool.agent_id is None:
            diagnostics.append("requested tool specialist is unavailable")
            global_share = False
        return RouteTeamPlan(
            route_id=route_id,
            prover=prover,
            skeptic=skeptic,
            tool_specialist=tool,
            referee=referee,
            risk=risk,
            global_share_allowed=global_share,
            diagnostics=diagnostics,
        )

    async def run(
        self,
        plan: RouteTeamPlan,
        artifact: Any,
        *,
        skeptic_handler: RoleHandler | None = None,
        tool_handler: RoleHandler | None = None,
        referee_handler: RoleHandler | None = None,
    ) -> RouteTeamResult:
        result = RouteTeamResult(
            route_id=plan.route_id,
            artifact=artifact,
            global_share_allowed=plan.global_share_allowed,
            diagnostics=list(plan.diagnostics),
        )
        if plan.skeptic is not None:
            if plan.skeptic.agent_id is None or skeptic_handler is None:
                result.global_share_allowed = False
                result.diagnostics.append(
                    "required skeptic did not run; artifact remains route-local"
                )
            else:
                result.skeptic_result = await skeptic_handler(plan.skeptic, artifact)
                if not (
                    isinstance(result.skeptic_result, VerificationReport)
                    and result.skeptic_result.verdict == VerificationVerdict.PASS
                ):
                    result.global_share_allowed = False
                    result.diagnostics.append(
                        "skeptic did not pass the artifact; it remains route-local"
                    )
        if plan.tool_specialist is not None:
            if plan.tool_specialist.agent_id is None or tool_handler is None:
                result.global_share_allowed = False
                result.diagnostics.append(
                    "required tool audit did not run; artifact remains route-local"
                )
            else:
                result.tool_result = await tool_handler(plan.tool_specialist, artifact)
                tool_passed = (
                    isinstance(result.tool_result, ToolAuditReport)
                    and result.tool_result.verdict == "pass"
                    and result.tool_result.mathematical_mapping_checked
                    and result.tool_result.all_results_replayed_independently
                ) or (
                    isinstance(result.tool_result, dict)
                    and bool(
                        result.tool_result.get(
                            "all_results_replayed_independently", False
                        )
                    )
                )
                if not tool_passed:
                    result.global_share_allowed = False
                    result.diagnostics.append(
                        "tool evidence was not independently replayed; artifact remains route-local"
                    )
        if plan.referee.agent_id is None or referee_handler is None:
            result.global_share_allowed = False
            return result
        sanitized = {
            "artifact": (
                artifact.model_dump(mode="json")
                if hasattr(artifact, "model_dump")
                else artifact
            ),
            "skeptic_result": result.skeptic_result,
            "tool_result": result.tool_result,
        }
        result.referee_result = await referee_handler(plan.referee, sanitized)
        if isinstance(result.referee_result, BrokerDecision):
            result.global_share_allowed = (
                result.global_share_allowed and result.referee_result.accepted
            )
        elif isinstance(result.referee_result, VerificationReport):
            result.global_share_allowed = (
                result.global_share_allowed
                and result.referee_result.problem_integrity_ok
                and result.referee_result.verdict == VerificationVerdict.PASS
            )
        else:
            result.global_share_allowed = False
            result.diagnostics.append(
                "referee returned no recognized admissibility result"
            )
        return result
