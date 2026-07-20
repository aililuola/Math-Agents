from __future__ import annotations

from typing import Any, Literal

from pydantic import Field

from ..config import AgentCapabilityConfig
from ..schemas import StrictModel


CAPABILITY_DOMAINS = frozenset(
    {
        "number_theory",
        "combinatorics",
        "algebra",
        "inequalities",
        "geometry",
        "logic",
        "computation",
    }
)
CAPABILITY_ROLES = frozenset(
    {
        "prover",
        "skeptic",
        "route_referee",
        "structural_verifier",
        "detailed_verifier",
        "analogy_agent",
        "construction_inventor",
        "tool_agent",
    }
)
ObservationKind = Literal[
    "mutation_benchmark",
    "tool_agreement",
    "first_error_accuracy",
    "overturn",
    "recent_task",
]


class CapabilityCell(StrictModel):
    agent_id: str
    domain: str
    role: str
    observations: int = Field(default=0, ge=0)
    weighted_success: float = Field(default=0.0, ge=0.0)
    weighted_total: float = Field(default=0.0, ge=0.0)
    overturns: int = Field(default=0, ge=0)
    score: float = Field(default=0.5, ge=0.0, le=1.0)


class AgentCapabilityProfile:
    """Trust indexed by agent, mathematical domain, and role."""

    def __init__(self, config: AgentCapabilityConfig) -> None:
        self.config = config
        self._cells: dict[tuple[str, str, str], CapabilityCell] = {}
        self.ignored_self_reports = 0

    def get(self, agent_id: str, domain: str, role: str) -> CapabilityCell:
        self._validate_dimension(domain, role)
        key = (agent_id, domain, role)
        if key not in self._cells:
            self._cells[key] = CapabilityCell(
                agent_id=agent_id, domain=domain, role=role
            )
        return self._cells[key]

    def update(
        self,
        agent_id: str,
        domain: str,
        role: str,
        *,
        kind: ObservationKind,
        success: bool,
        self_reported_confidence: float | None = None,
    ) -> CapabilityCell:
        if self_reported_confidence is not None:
            self.ignored_self_reports += 1
        cell = self.get(agent_id, domain, role)
        decay = self.config.recency_decay
        cell.weighted_success *= decay
        cell.weighted_total *= decay
        weights = {
            "mutation_benchmark": self.config.mutation_benchmark_weight,
            "tool_agreement": self.config.tool_agreement_weight,
            "first_error_accuracy": self.config.first_error_accuracy_weight,
            "overturn": self.config.overturn_rate_penalty,
            "recent_task": max(
                0.01,
                1.0
                - self.config.mutation_benchmark_weight
                - self.config.tool_agreement_weight
                - self.config.first_error_accuracy_weight,
            ),
        }
        weight = weights[kind]
        cell.observations += 1
        cell.weighted_total += weight
        if success:
            cell.weighted_success += weight
        if kind == "overturn" and not success:
            cell.overturns += 1
        if cell.observations >= self.config.min_observations_before_trust_update:
            empirical = cell.weighted_success / max(cell.weighted_total, 1e-9)
            penalty = min(
                0.8,
                self.config.overturn_rate_penalty
                * cell.overturns
                / max(1, cell.observations),
            )
            cell.score = max(0.0, min(1.0, empirical - penalty))
        return cell

    @staticmethod
    def _validate_dimension(domain: str, role: str) -> None:
        if domain not in CAPABILITY_DOMAINS:
            raise ValueError(f"unsupported capability domain: {domain}")
        if role not in CAPABILITY_ROLES:
            raise ValueError(f"unsupported capability role: {role}")

    def score(self, agent_id: str, domain: str, role: str) -> float:
        return self.get(agent_id, domain, role).score

    def export_state(self) -> dict[str, Any]:
        return {
            "cells": [item.model_dump(mode="json") for item in self._cells.values()],
            "ignored_self_reports": self.ignored_self_reports,
        }

    @classmethod
    def from_state(
        cls, state: dict[str, Any], *, config: AgentCapabilityConfig
    ) -> "AgentCapabilityProfile":
        profile = cls(config)
        for payload in state.get("cells", []):
            cell = CapabilityCell.model_validate(payload)
            profile._cells[(cell.agent_id, cell.domain, cell.role)] = cell
        profile.ignored_self_reports = int(state.get("ignored_self_reports", 0))
        return profile
