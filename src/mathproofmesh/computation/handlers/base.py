from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any

from ...schemas import EvidenceStrength, ExperimentOutcome


@dataclass(slots=True)
class HandlerEvidence:
    outcome: ExperimentOutcome
    evidence_strength: EvidenceStrength
    scope: dict[str, Any] = field(default_factory=dict)
    counterexample: dict[str, Any] | None = None
    certificate: dict[str, Any] | None = None
    exact_arithmetic: bool = False
    cases_checked: int = 0
    independently_verified: bool = False
    verification_notes: list[str] = field(default_factory=list)
    raw_output: dict[str, Any] | None = None
