from __future__ import annotations

from ._orchestrator_attempt_verification import AttemptVerificationOrchestratorMixin
from ._orchestrator_attempt_verification_b import AttemptVerificationBOrchestratorMixin
from ._orchestrator_core import CoreOrchestratorMixin
from ._orchestrator_exploration import ExplorationOrchestratorMixin
from ._orchestrator_final_verification import FinalVerificationOrchestratorMixin
from ._orchestrator_helpers_a import OrchestratorHelpersAMixin
from ._orchestrator_helpers_b import OrchestratorHelpersBMixin
from ._orchestrator_helpers_c import OrchestratorHelpersCMixin
from ._orchestrator_helpers_d import OrchestratorHelpersDMixin
from ._orchestrator_runtime import RuntimeOrchestratorMixin
from ._orchestrator_types import SolveState, VerificationBundle


class ProofMeshOrchestrator(
    CoreOrchestratorMixin,
    ExplorationOrchestratorMixin,
    RuntimeOrchestratorMixin,
    AttemptVerificationOrchestratorMixin,
    AttemptVerificationBOrchestratorMixin,
    FinalVerificationOrchestratorMixin,
    OrchestratorHelpersAMixin,
    OrchestratorHelpersBMixin,
    OrchestratorHelpersCMixin,
    OrchestratorHelpersDMixin,
):
    """
    Sparse, verification-first multi-agent orchestrator for difficult mathematics.

    The implementation is split into focused mixins so the orchestration stages,
    verification gates, and local integrity guards remain reviewable in GitHub.
    """


__all__ = ["ProofMeshOrchestrator", "SolveState", "VerificationBundle"]
