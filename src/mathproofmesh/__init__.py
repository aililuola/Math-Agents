"""MathProofMesh: sparse, verification-first collaboration for hard mathematics."""

from .config import SystemConfig, load_config
from .orchestrator import ProofMeshOrchestrator
from .schemas import RunResult

__all__ = ["ProofMeshOrchestrator", "RunResult", "SystemConfig", "load_config"]
__version__ = "0.8.0"
