from __future__ import annotations

from enum import StrEnum

from pydantic import Field

from ..schemas import ProofStep, StrictModel, new_id
from .capability_profile import AgentCapabilityProfile


class MutationKind(StrEnum):
    DROP_ASSUMPTION = "drop_assumption"
    REVERSE_QUANTIFIER = "reverse_quantifier"
    ALTER_SIGN = "alter_sign"
    BREAK_DEPENDENCY = "break_dependency"
    INSERT_CIRCULAR_STEP = "insert_circular_step"


class ProofMutation(StrictModel):
    mutation_id: str = Field(default_factory=lambda: new_id("mutation"))
    kind: MutationKind
    original_step_id: str
    mutated_statement: str
    expected_verdict: str = "fail"
    fault_description: str


class MutationResult(StrictModel):
    mutation_id: str
    agent_id: str
    detected: bool
    first_error_correct: bool


class ProofMutationHarness:
    def mutate(self, step: ProofStep, kind: MutationKind) -> ProofMutation:
        statement = step.statement
        if kind == MutationKind.ALTER_SIGN:
            statement = statement.replace("<=", ">=").replace("≤", "≥")
        elif kind == MutationKind.REVERSE_QUANTIFIER:
            statement = statement.replace("for every", "there exists", 1)
        elif kind == MutationKind.DROP_ASSUMPTION:
            statement = f"Without the stated hypotheses, {statement}"
        elif kind == MutationKind.BREAK_DEPENDENCY:
            statement = f"Independently of its dependencies, {statement}"
        else:
            statement = f"Using this statement itself, conclude {statement}"
        return ProofMutation(
            kind=kind,
            original_step_id=step.step_id,
            mutated_statement=statement,
            fault_description=f"synthetic {kind.value} proof fault",
        )

    @staticmethod
    def record(
        result: MutationResult,
        *,
        domain: str,
        role: str,
        profile: AgentCapabilityProfile,
    ) -> None:
        profile.update(
            result.agent_id,
            domain,
            role,
            kind="mutation_benchmark",
            success=result.detected,
        )
        profile.update(
            result.agent_id,
            domain,
            role,
            kind="first_error_accuracy",
            success=result.first_error_correct,
        )
