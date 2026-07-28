from __future__ import annotations

import json
import random
import re
import shlex
import subprocess
import sys
import tempfile
import threading
import time
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path
from typing import Any

import sympy as sp

from ..activity import ActivityImportance, ActivityStatus, ActivityStream
from ..config import SystemConfig
from ..proof_control.falsification import classify_falsification_result
from ..schemas import (
    ComputationCertificate,
    ComputationDecision,
    ComputationDecisionStatus,
    ComputationMethod,
    ComputationPlan,
    DeferredExperimentRequest,
    EvidenceRef,
    EvidenceStrength,
    ExperimentOutcome,
    ExperimentProgram,
    ExperimentResult,
    ExperimentSpec,
    ToolRequest,
    ToolResult,
    stable_hash,
    utc_now_iso,
)
from ..store import ArtifactStore
from .cache import ComputationLedger, ExperimentCache
from .contracts import (
    computation_contract_mode,
    normalize_exploratory_contract,
)
from .handlers.base import HandlerEvidence
from .handlers.geometry import run_exact_geometry
from .handlers.graph import run_graph_certificate
from .handlers.integer_search import run_bounded_integer_search
from .handlers.modular import run_modular
from .handlers.number_theory import run_number_theory
from .handlers.real_inequality import run_real_inequality
from .handlers.recurrence import run_recurrence_check
from .handlers.sequence import (
    run_bounded_greedy_sequence,
    run_candidate_period_check,
)
from .handlers.symbolic import (
    numeric_counterexample_payload,
    polynomial_factor_payload,
    run_symbolic,
    sympy_equivalent_payload,
    sympy_simplify_payload,
)
from .policy import ComputationContext, ComputationGate
from .sandbox import (
    SandboxExecutionError,
    _find_docker_executable,
    build_lean_docker_command,
    run_sandboxed_python,
)


TOOL_VERSION = "mathproofmesh-computation/0.8.2"

_LEAN_PLACEHOLDER_PATTERN = re.compile(r"\b(?:sorry|admit)\b", re.IGNORECASE)
_LEAN_AXIOM_PATTERN = re.compile(r"\baxiom\b", re.IGNORECASE)


def _lean_incomplete_marker(source: str, *compiler_outputs: str) -> str | None:
    """Return the token that disqualifies a zero exit code as a formal certificate.

    Lean exits with code 0 even when the fragment leans on ``sorry``/``admit``
    placeholders (it only warns) or when the source introduces a new ``axiom``,
    so acceptance must additionally scan the source and the compiler output.
    """
    if _LEAN_AXIOM_PATTERN.search(source):
        return "axiom"
    for text in (source, *compiler_outputs):
        match = _LEAN_PLACEHOLDER_PATTERN.search(text)
        if match:
            return match.group(0).lower()
    return None


class ComputationBroker:
    """Runs admitted experiments and keeps the legacy verifier-tool API stable."""

    def __init__(
        self,
        config: SystemConfig,
        store: ArtifactStore,
        activity: ActivityStream | None = None,
    ) -> None:
        self.config = config
        self.store = store
        self.activity = activity
        self.cache = ExperimentCache(store)
        self.ledger = ComputationLedger(store)
        self.gate = ComputationGate(config, self.cache, self.ledger)
        self._deferred_lock = threading.RLock()
        self._deferred_requests: dict[str, DeferredExperimentRequest] = {}
        self._activity_identity_lock = threading.RLock()
        self._activity_identity_by_execution: dict[str, str] = {}
        self._load_deferred_requests()

    @property
    def results(self) -> list[ExperimentResult]:
        return [
            ExperimentResult.model_validate(payload)
            for payload in self.store.list_experiment_results()
        ]

    def results_for_path(self, path_id: str) -> list[ExperimentResult]:
        """Return canonical cached results with path-local provenance restored."""
        hashes = set(self.ledger.hashes_for_path(path_id))
        results: list[ExperimentResult] = []
        for result in self.results:
            if result.request_hash not in hashes and result.path_id != path_id:
                continue
            payload = result.model_dump(mode="json")
            payload["path_id"] = path_id
            results.append(ExperimentResult.model_validate(payload))
        return results

    def _load_deferred_requests(self) -> None:
        if not self.store.has_named_json("experiments", "deferred_queue"):
            return
        payload = self.store.read_named_json("experiments", "deferred_queue")
        for item in payload.get("requests", []):
            request = DeferredExperimentRequest.model_validate(item)
            self._deferred_requests[request.spec.request_hash] = request

    def _persist_deferred_requests(self) -> None:
        self.store.write_json(
            "experiments",
            "deferred_queue",
            {
                "requests": [
                    item.model_dump(mode="json")
                    for item in sorted(
                        self._deferred_requests.values(),
                        key=lambda request: (
                            request.first_deferred_at,
                            request.spec.request_hash,
                        ),
                    )
                ]
            },
        )

    def deferred_for_path(self, path_id: str) -> list[DeferredExperimentRequest]:
        with self._deferred_lock:
            return [
                item.model_copy(deep=True)
                for item in self._deferred_requests.values()
                if item.path_id == path_id
            ]

    def _enqueue_deferred(
        self,
        spec: ExperimentSpec,
        decision: ComputationDecision,
    ) -> None:
        path_id = spec.path_id or ""
        with self._deferred_lock:
            existing = self._deferred_requests.get(spec.request_hash)
            if existing is None:
                request = DeferredExperimentRequest(
                    path_id=path_id,
                    spec=spec.model_copy(deep=True),
                    latest_decision=decision.model_copy(deep=True),
                )
            else:
                request = existing.model_copy(
                    update={
                        "path_id": path_id,
                        "spec": spec.model_copy(deep=True),
                        "latest_decision": decision.model_copy(deep=True),
                        "defer_count": existing.defer_count + 1,
                        "last_evaluated_at": utc_now_iso(),
                    },
                    deep=True,
                )
            self._deferred_requests[spec.request_hash] = request
            self._persist_deferred_requests()
        self.store.append_event(
            "computation_deferred_queued",
            {
                "path_id": path_id,
                "experiment_id": spec.experiment_id,
                "request_hash": spec.request_hash,
                "rule_id": decision.rule_id,
                "defer_count": request.defer_count,
            },
        )

    def resolve_deferred(self, request_hash: str, *, reason: str) -> None:
        with self._deferred_lock:
            request = self._deferred_requests.pop(request_hash, None)
            if request is None:
                return
            self._persist_deferred_requests()
        self.store.append_event(
            "computation_deferred_resolved",
            {
                "path_id": request.path_id,
                "experiment_id": request.spec.experiment_id,
                "request_hash": request_hash,
                "reason": reason,
                "defer_count": request.defer_count,
            },
        )

    def activity_task_id(self, spec: ExperimentSpec) -> str:
        """Return the stable timeline/topology identity for one experiment."""

        with self._activity_identity_lock:
            existing = self._activity_identity_by_execution.get(spec.execution_hash)
            if existing is not None:
                return existing
            experiment_id = spec.experiment_id
            canonical_request_hash = self.cache.canonical_request_hash(spec)
            if canonical_request_hash is not None:
                try:
                    experiment_id = self.cache.load_spec(
                        canonical_request_hash
                    ).experiment_id
                except (FileNotFoundError, OSError, ValueError):
                    pass
            task_id = f"computation:{experiment_id}"
            self._activity_identity_by_execution[spec.execution_hash] = task_id
            return task_id

    def update_experiment_activity(
        self,
        spec: ExperimentSpec,
        *,
        phase: str,
        detail: str,
        status: ActivityStatus = ActivityStatus.RUNNING,
        event_type: str = "computation_updated",
        progress: float | None = None,
        metrics: dict[str, Any] | None = None,
    ) -> None:
        """Update one experiment in place instead of creating timeline duplicates."""
        if self.activity is None:
            return
        payload = self._activity_metrics(spec, phase=phase)
        payload.update(metrics or {})
        self.activity.update_task(
            self.activity_task_id(spec),
            status=status,
            event_type=event_type,
            title=self._activity_title(spec),
            detail=f"{spec.target_claim} · {detail}",
            stage="computation",
            agent_id=spec.requested_by,
            importance=ActivityImportance.NORMAL,
            progress=progress,
            metrics=payload,
        )

    def _start_experiment_activity(self, spec: ExperimentSpec) -> None:
        if self.activity is None:
            return
        event_type = (
            "python_experiment"
            if spec.method == ComputationMethod.SANDBOXED_PYTHON
            else "computation_experiment"
        )
        self.activity.start_task(
            event_type,
            task_id=self.activity_task_id(spec),
            title=self._activity_title(spec),
            detail=(
                f"{spec.target_claim} · "
                + self.activity.text(
                    "正在检查计算请求与执行策略",
                    "Checking the computation request and execution policy",
                )
            ),
            stage="computation",
            agent_id=spec.requested_by,
            importance=ActivityImportance.NORMAL,
            metrics=self._activity_metrics(spec, phase="gate"),
        )

    def _activity_title(self, spec: ExperimentSpec) -> str:
        if spec.method == ComputationMethod.SANDBOXED_PYTHON:
            return (
                self.activity.text("Python 沙箱实验", "Sandboxed Python experiment")
                if self.activity is not None
                else "Sandboxed Python experiment"
            )
        return (
            self.activity.text("定向计算实验", "Targeted computation experiment")
            if self.activity is not None
            else "Targeted computation experiment"
        )

    @staticmethod
    def _activity_metrics(spec: ExperimentSpec, *, phase: str) -> dict[str, Any]:
        return {
            "experiment_id": spec.experiment_id,
            "request_hash": spec.request_hash,
            "execution_hash": spec.execution_hash,
            "contract_mode": computation_contract_mode(spec),
            "method": spec.method.value,
            "path_id": spec.path_id,
            "checkpoint_id": spec.parent_checkpoint_id,
            "phase": phase,
        }

    def decide(
        self, spec: ExperimentSpec, context: ComputationContext
    ) -> ComputationDecision:
        if spec.path_id is None:
            spec.path_id = context.path_id
        normalized_to_discovery = (
            not context.mandatory_calculation_gate
            and normalize_exploratory_contract(spec)
        )
        if normalized_to_discovery:
            self.store.append_event(
                "computation_contract_mode_normalized",
                {
                    "experiment_id": spec.experiment_id,
                    "path_id": spec.path_id,
                    "method": spec.method.value,
                    "contract_mode": "discovery",
                    "reason": (
                        "No claimed_values assertion was supplied; the request was "
                        "safely downgraded to scoped discovery evidence."
                    ),
                },
            )
        tool_name, tool_version = self._tool_identity(spec.method)
        spec.bind_runtime_fingerprint(
            {
                "tool_name": tool_name,
                "tool_version": tool_version,
                "sandbox_image": (
                    self.config.computation.sandbox_image
                    if spec.method == ComputationMethod.SANDBOXED_PYTHON
                    else None
                ),
                "precision": spec.arguments.get(
                    "precision", "exact" if spec.exact_arithmetic else "declared"
                ),
                "seed": spec.seed,
            }
        )
        self._start_experiment_activity(spec)
        completed_request = self.cache.has_result(spec.request_hash)
        self.cache.register_aliases(spec.request_hash, spec.experiment_id)
        if not completed_request:
            self.cache.save_spec(spec)
        decision = self.gate.decide(spec, context)
        if not completed_request:
            self.cache.save_decision(decision)
        if decision.decision == ComputationDecisionStatus.ALLOW:
            plan = ComputationPlan.from_spec(spec)
            self.store.write_experiment_artifact(
                spec.request_hash, "computation_plan.json", plan
            )
            self.store.append_event("computation_plan_created", plan)
        self.store.append_event("computation_decision", decision)
        if decision.rule_id == "fast_path.proof_control_falsification":
            self.store.append_event(
                "falsification_fast_lane_admitted",
                {
                    "experiment_id": spec.experiment_id,
                    "request_hash": spec.request_hash,
                    "path_id": context.path_id,
                    "target_obligation_id": context.target_obligation_id,
                    "target_claim_id": context.target_claim_id,
                    "method": spec.method.value,
                    "max_cases": spec.max_cases,
                },
            )
        if decision.decision == ComputationDecisionStatus.DEFER:
            self._enqueue_deferred(spec, decision)
        elif decision.decision == ComputationDecisionStatus.REJECT:
            self.resolve_deferred(
                spec.request_hash,
                reason=f"terminal gate decision: {decision.rule_id}",
            )
        if decision.decision == ComputationDecisionStatus.ALLOW:
            self.update_experiment_activity(
                spec,
                phase="cache" if decision.cache_hit else "admitted",
                detail=decision.reason,
                event_type="computation_admitted",
                progress=0.2,
                metrics={
                    "decision": decision.decision.value,
                    "decision_reason": decision.reason,
                    "rule_id": decision.rule_id,
                    "cache_hit": decision.cache_hit,
                },
            )
        else:
            self.update_experiment_activity(
                spec,
                phase=decision.decision.value,
                detail=decision.reason,
                status=ActivityStatus.WARNING,
                event_type="computation_not_executed",
                progress=1.0,
                metrics={
                    "decision": decision.decision.value,
                    "decision_reason": decision.reason,
                    "rule_id": decision.rule_id,
                    "cache_hit": decision.cache_hit,
                },
            )
        return decision

    def run_experiment(
        self,
        spec: ExperimentSpec,
        decision: ComputationDecision,
        *,
        program: ExperimentProgram | None = None,
        use_cache: bool = True,
        persist: bool = True,
        track_activity: bool = True,
    ) -> ExperimentResult:
        try:
            result = self._run_experiment(
                spec,
                decision,
                program=program,
                use_cache=use_cache,
                persist=persist,
                track_activity=track_activity,
            )
            self.resolve_deferred(
                spec.request_hash,
                reason="experiment completed",
            )
            if decision.rule_id == "fast_path.proof_control_falsification":
                disposition = classify_falsification_result(result)
                self.store.append_event(
                    disposition.event_type,
                    {
                        "experiment_id": result.experiment_id,
                        "request_hash": result.request_hash,
                        "path_id": result.path_id,
                        "outcome": result.outcome.value,
                        "memory_tier": (
                            disposition.memory_tier.value
                            if disposition.memory_tier is not None
                            else None
                        ),
                        "message_type": (
                            disposition.message_type.value
                            if disposition.message_type is not None
                            else None
                        ),
                        "claim_status_changed": disposition.claim_status_changed,
                        "reason": disposition.reason,
                    },
                )
            return result
        except Exception as exc:
            if track_activity:
                self.update_experiment_activity(
                    spec,
                    phase="failed",
                    detail=f"{type(exc).__name__}: {exc}",
                    status=ActivityStatus.FAILED,
                    event_type="computation_failed",
                    progress=1.0,
                    metrics={
                        "decision": decision.decision.value,
                        "decision_reason": decision.reason,
                        "rule_id": decision.rule_id,
                        "cache_hit": decision.cache_hit,
                        "error": f"{type(exc).__name__}: {exc}",
                    },
                )
            raise

    def _run_experiment(
        self,
        spec: ExperimentSpec,
        decision: ComputationDecision,
        *,
        program: ExperimentProgram | None = None,
        use_cache: bool = True,
        persist: bool = True,
        track_activity: bool = True,
    ) -> ExperimentResult:
        if decision.decision != ComputationDecisionStatus.ALLOW:
            raise RuntimeError(
                f"experiment cannot execute after {decision.decision.value}: {decision.reason}"
            )
        if (
            decision.request_hash != spec.request_hash
            or decision.experiment_id != spec.experiment_id
        ):
            raise ValueError(
                "computation decision does not match its experiment specification"
            )
        if use_cache and self.config.computation.cache_results:
            cache_request_hash = decision.canonical_request_hash or spec.request_hash
            cached = self.cache.get(cache_request_hash)
            if cached is not None:
                self.cache.register_aliases(
                    cached.request_hash,
                    spec.request_hash,
                    spec.experiment_id,
                )
                payload = cached.model_dump(mode="json")
                payload.update(
                    {
                        "experiment_id": spec.experiment_id,
                        "path_id": spec.path_id,
                        "parent_checkpoint_id": spec.parent_checkpoint_id,
                        "cached": True,
                    }
                )
                reused = ExperimentResult.model_validate(payload)
                self.ledger.record_cache_use(spec.path_id or "unassigned", reused)
                certificate = ComputationCertificate.from_result(reused)
                self.store.write_experiment_artifact(
                    reused.request_hash,
                    "computation_certificate.json",
                    certificate,
                )
                self.store.append_event("experiment_cache_hit", reused)
                if track_activity:
                    self.update_experiment_activity(
                        spec,
                        phase="completed",
                        detail=(
                            f"{reused.outcome.value}; "
                            f"evidence={reused.evidence_strength.value}; cache hit"
                        ),
                        status=ActivityStatus.COMPLETED,
                        event_type="computation_completed",
                        progress=1.0,
                        metrics={
                            "decision": decision.decision.value,
                            "decision_reason": decision.reason,
                            "rule_id": decision.rule_id,
                            "cache_hit": True,
                            "outcome": reused.outcome.value,
                            "evidence_strength": reused.evidence_strength.value,
                            "cases_checked": reused.cases_checked,
                            "runtime_seconds": round(reused.runtime_seconds, 6),
                            "result_hash": reused.result_hash,
                        },
                    )
                return reused
        if program is not None:
            if program.experiment_id != spec.experiment_id:
                raise ValueError(
                    "experiment program ID does not match its specification"
                )
            if persist:
                self.cache.save_program(spec.request_hash, program)

        if track_activity:
            self.update_experiment_activity(
                spec,
                phase="executing",
                detail=(
                    self.activity.text(
                        "正在隔离沙箱中执行 Python 程序",
                        "Running the Python program in the isolated sandbox",
                    )
                    if self.activity is not None
                    and spec.method == ComputationMethod.SANDBOXED_PYTHON
                    else "Executing admitted computation"
                ),
                event_type="computation_executing",
                progress=0.55,
                metrics={
                    "decision": decision.decision.value,
                    "decision_reason": decision.reason,
                    "rule_id": decision.rule_id,
                    "cache_hit": False,
                    "program_hash": self._program_identity(program),
                },
            )
        started = time.perf_counter()
        error: str | None = None
        process_record: dict[str, Any] | None = None
        try:
            evidence = self._dispatch(spec, program)
            if spec.method == ComputationMethod.SANDBOXED_PYTHON:
                process_record = {
                    "exit_code": evidence.process_exit_code,
                    "stdout": evidence.process_stdout,
                    "stderr": evidence.process_stderr,
                }
            if (
                evidence.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
                and evidence.independently_verified
                and spec.method != ComputationMethod.SANDBOXED_PYTHON
            ):
                replay = self._dispatch(spec, program)
                replay_confirmed = (
                    replay.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
                    and replay.independently_verified
                )
                if replay_confirmed:
                    evidence.verification_notes.append(
                        "The broker independently replayed the typed checker and reproduced a valid counterexample."
                    )
                else:
                    evidence.outcome = ExperimentOutcome.INCONCLUSIVE
                    evidence.evidence_strength = EvidenceStrength.HEURISTIC
                    evidence.independently_verified = False
                    evidence.verification_notes.append(
                        "Independent broker replay did not reproduce the candidate counterexample."
                    )
            if (
                spec.method == ComputationMethod.SANDBOXED_PYTHON
                and evidence.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
                and not evidence.independently_verified
            ):
                evidence.outcome = ExperimentOutcome.INCONCLUSIVE
                evidence.evidence_strength = EvidenceStrength.HEURISTIC
                evidence.verification_notes.append(
                    "The candidate was not accepted because no typed checker independently reproduced it."
                )
            self._enforce_output_limit(evidence)
        except SandboxExecutionError as exc:
            error = f"{type(exc).__name__}: {exc}"
            process_record = {
                "exit_code": exc.exit_code,
                "stdout": exc.stdout,
                "stderr": exc.stderr,
            }
            evidence = HandlerEvidence(
                outcome=ExperimentOutcome.INCONCLUSIVE,
                evidence_strength=EvidenceStrength.HEURISTIC,
                verification_notes=[
                    "Sandbox failure is inconclusive and is not a mathematical refutation."
                ],
            )
        except Exception as exc:
            error = f"{type(exc).__name__}: {exc}"
            evidence = HandlerEvidence(
                outcome=ExperimentOutcome.INCONCLUSIVE,
                evidence_strength=EvidenceStrength.HEURISTIC,
                verification_notes=[
                    "Tool failure is inconclusive and is not a mathematical refutation."
                ],
            )
        runtime_seconds = time.perf_counter() - started
        tool_name, tool_version = self._tool_identity(spec.method)
        result = ExperimentResult(
            experiment_id=spec.experiment_id,
            request_hash=spec.request_hash,
            path_id=spec.path_id,
            parent_checkpoint_id=spec.parent_checkpoint_id,
            target_claim=spec.target_claim,
            method=spec.method,
            outcome=evidence.outcome,
            evidence_strength=evidence.evidence_strength,
            scope=evidence.scope,
            counterexample=evidence.counterexample,
            certificate=evidence.certificate,
            exact_arithmetic=evidence.exact_arithmetic,
            cases_checked=evidence.cases_checked,
            runtime_seconds=runtime_seconds,
            tool_name=tool_name,
            tool_version=tool_version,
            program_hash=self._program_identity(program),
            independently_verified=evidence.independently_verified,
            verification_notes=evidence.verification_notes,
            error=error,
        )
        if persist:
            execution_input = self._execution_input(spec)
            execution_output = {
                "handler_output": (
                    evidence.raw_output
                    if evidence.raw_output is not None
                    else self._handler_evidence_payload(evidence)
                ),
                "process": process_record,
                "accepted_result": result.model_dump(
                    mode="json",
                    exclude={
                        "artifact_refs",
                        "cached",
                        "created_at",
                        "runtime_seconds",
                    },
                ),
            }
            environment = self._execution_environment(
                spec,
                program,
                tool_name=tool_name,
                tool_version=tool_version,
            )
            execution_ref = self.cache.save_execution(
                spec.request_hash,
                {
                    "request_hash": spec.request_hash,
                    "method": spec.method,
                    "runtime_seconds": runtime_seconds,
                    "outcome": result.outcome,
                    "error": error,
                    "tool_name": tool_name,
                    "tool_version": tool_version,
                    "program_hash": result.program_hash,
                    "input": execution_input,
                    "input_hash": stable_hash(execution_input),
                    "output": execution_output,
                    "output_hash": stable_hash(execution_output),
                    "result_hash": result.result_hash,
                    "environment": environment,
                    "environment_hash": stable_hash(environment),
                },
            )
            result.artifact_refs.append(
                EvidenceRef(
                    artifact_ref=execution_ref,
                    content_hash=None,
                    summary="Bounded experiment execution record.",
                )
            )
            result_ref = self.cache.save_result(result)
            result.artifact_refs.append(
                EvidenceRef(
                    artifact_ref=result_ref,
                    content_hash=result.result_hash,
                    summary="Structured experiment result.",
                )
            )
            # Rewrite once so the durable result includes both artifact references.
            self.cache.save_result(result)
            self.cache.save_evidence(result)
            certificate = ComputationCertificate.from_result(result)
            self.store.write_experiment_artifact(
                spec.request_hash,
                "computation_certificate.json",
                certificate,
            )
            self.store.append_event("computation_certificate_created", certificate)
            path_id = spec.path_id or "unassigned"
            self.ledger.record_result(path_id, result)
            self.store.append_event("experiment_completed", result)
        if track_activity:
            status = (
                ActivityStatus.FAILED
                if error is not None
                else ActivityStatus.WARNING
                if result.outcome
                in {
                    ExperimentOutcome.COUNTEREXAMPLE_FOUND,
                    ExperimentOutcome.ERROR,
                    ExperimentOutcome.INCONCLUSIVE,
                }
                else ActivityStatus.COMPLETED
            )
            self.update_experiment_activity(
                spec,
                phase="failed" if error is not None else "completed",
                detail=(
                    f"{result.outcome.value}; evidence={result.evidence_strength.value}"
                ),
                status=status,
                event_type=(
                    "computation_failed"
                    if error is not None
                    else "computation_completed"
                ),
                progress=1.0,
                metrics={
                    "decision": decision.decision.value,
                    "decision_reason": decision.reason,
                    "rule_id": decision.rule_id,
                    "cache_hit": False,
                    "outcome": result.outcome.value,
                    "evidence_strength": result.evidence_strength.value,
                    "cases_checked": result.cases_checked,
                    "runtime_seconds": round(result.runtime_seconds, 6),
                    "error": result.error,
                    "program_hash": result.program_hash,
                    "result_hash": result.result_hash,
                    "process_exit_code": (
                        process_record.get("exit_code")
                        if process_record is not None
                        else None
                    ),
                },
            )
        return result

    def _enforce_output_limit(self, evidence: HandlerEvidence) -> None:
        payload = self._handler_evidence_payload(evidence)
        if evidence.raw_output is not None:
            payload["raw_output"] = evidence.raw_output
        serialized = json.dumps(
            payload,
            ensure_ascii=False,
            sort_keys=True,
            separators=(",", ":"),
            default=str,
        )
        if len(serialized) > self.config.computation.max_output_chars:
            raise RuntimeError(
                "typed computation output exceeds computation.max_output_chars"
            )

    @staticmethod
    def _handler_evidence_payload(evidence: HandlerEvidence) -> dict[str, Any]:
        return {
            "outcome": evidence.outcome.value,
            "evidence_strength": evidence.evidence_strength.value,
            "scope": evidence.scope,
            "counterexample": evidence.counterexample,
            "certificate": evidence.certificate,
            "exact_arithmetic": evidence.exact_arithmetic,
            "cases_checked": evidence.cases_checked,
            "independently_verified": evidence.independently_verified,
            "verification_notes": evidence.verification_notes,
        }

    @staticmethod
    def _execution_input(spec: ExperimentSpec) -> dict[str, Any]:
        actual_input = (
            {**dict(spec.arguments.get("input", {})), "seed": spec.seed}
            if spec.method == ComputationMethod.SANDBOXED_PYTHON
            and isinstance(spec.arguments.get("input", {}), dict)
            else None
        )
        return {
            "domains": spec.domains,
            "arguments": spec.arguments,
            "exact_arithmetic": spec.exact_arithmetic,
            "max_cases": spec.max_cases,
            "seed": spec.seed,
            "sandbox_input": actual_input,
        }

    def _execution_environment(
        self,
        spec: ExperimentSpec,
        program: ExperimentProgram | None,
        *,
        tool_name: str,
        tool_version: str,
    ) -> dict[str, Any]:
        cfg = self.config.computation
        sandbox = None
        if spec.method == ComputationMethod.SANDBOXED_PYTHON:
            sandbox = {
                "image": cfg.sandbox_image,
                "network": "none",
                "read_only_root": True,
                "non_root_user": "65532:65532",
                "workspace_mounted": False,
                "host_environment_forwarded": False,
                "timeout_seconds": cfg.sandbox_timeout_seconds,
                "memory_mb": cfg.sandbox_memory_mb,
                "cpus": cfg.sandbox_cpus,
                "pids_limit": cfg.sandbox_pids_limit,
                "max_output_chars": cfg.max_output_chars,
            }
        return {
            "tool_name": tool_name,
            "tool_version": tool_version,
            "runtime_fingerprint": spec.runtime_fingerprint,
            "program_hash": self._program_identity(program),
            "sandbox": sandbox,
        }

    def recheck(
        self,
        spec: ExperimentSpec,
        expected: ExperimentResult,
        *,
        program: ExperimentProgram | None = None,
    ) -> tuple[bool, ExperimentResult]:
        decision = ComputationDecision(
            experiment_id=spec.experiment_id,
            request_hash=spec.request_hash,
            decision=ComputationDecisionStatus.ALLOW,
            reason="Final audit replay.",
            rule_id="audit.replay",
        )
        actual = self.run_experiment(
            spec,
            decision,
            program=program,
            use_cache=False,
            persist=False,
            track_activity=False,
        )
        return actual.result_hash == expected.result_hash, actual

    def audit_key_results(
        self,
        *,
        path_id: str | None = None,
        report_name: str = "final_experiment_audit",
    ) -> list[dict[str, Any]]:
        """Validate every result and replay proof-relevant evidence deterministically."""
        records: list[dict[str, Any]] = []
        key_strengths = {
            EvidenceStrength.COUNTEREXAMPLE,
            EvidenceStrength.EXHAUSTIVE_CERTIFICATE,
            EvidenceStrength.FORMAL_CERTIFICATE,
        }
        path_hashes = set(self.ledger.hashes_for_path(path_id)) if path_id else set()
        for payload in self.store.list_experiment_results():
            request_hash = str(payload.get("request_hash", "unknown"))
            if (
                path_id is not None
                and request_hash not in path_hashes
                and payload.get("path_id") != path_id
            ):
                continue
            expected: ExperimentResult | None = None
            try:
                expected = ExperimentResult.model_validate(payload)
                spec = self.cache.load_spec(expected.request_hash)
                program = self.cache.load_program(expected.request_hash)
                execution = self.cache.load_execution(expected.request_hash)
                evidence_record = self.cache.load_evidence(expected.request_hash)
                if (
                    spec.method == ComputationMethod.SANDBOXED_PYTHON
                    and program is None
                ):
                    raise ValueError(
                        "sandboxed Python result is missing its hashed program artifact"
                    )
                hash_valid = (
                    spec.request_hash == expected.request_hash
                    and spec.target_claim == expected.target_claim
                    and spec.method == expected.method
                )
                expected_tool = self._tool_identity(spec.method)
                tool_version_valid = (
                    expected.tool_name == expected_tool[0]
                    and expected.tool_version == expected_tool[1]
                )
                program_hash_valid = expected.program_hash == self._program_identity(
                    program
                )
                program_binding_valid = (
                    program is None or program.experiment_id == spec.experiment_id
                )
                execution_input = execution.get("input")
                execution_output = execution.get("output")
                execution_environment = execution.get("environment")
                execution_valid = (
                    execution.get("request_hash") == expected.request_hash
                    and execution.get("method") == expected.method.value
                    and execution.get("tool_name") == expected.tool_name
                    and execution.get("tool_version") == expected.tool_version
                    and execution.get("program_hash") == expected.program_hash
                    and execution.get("result_hash") == expected.result_hash
                    and isinstance(execution_input, dict)
                    and execution.get("input_hash") == stable_hash(execution_input)
                    and isinstance(execution_output, dict)
                    and execution.get("output_hash") == stable_hash(execution_output)
                    and isinstance(execution_environment, dict)
                    and execution.get("environment_hash")
                    == stable_hash(execution_environment)
                    and execution_output.get("accepted_result", {}).get("result_hash")
                    == expected.result_hash
                )
                evidence_record_valid = (
                    evidence_record.get("request_hash") == expected.request_hash
                    and evidence_record.get("result_hash") == expected.result_hash
                    and evidence_record.get("evidence_strength")
                    == expected.evidence_strength.value
                    and evidence_record.get("outcome") == expected.outcome.value
                    and evidence_record.get("independently_verified")
                    == expected.independently_verified
                )
                key_evidence = expected.evidence_strength in key_strengths
                replay: ExperimentResult | None = None
                replay_matches = True
                if key_evidence:
                    replay_matches, replay = self.recheck(
                        spec, expected, program=program
                    )
                counterexample_valid = (
                    expected.evidence_strength != EvidenceStrength.COUNTEREXAMPLE
                    or (
                        expected.independently_verified
                        and replay is not None
                        and replay.independently_verified
                        and replay.outcome == ExperimentOutcome.COUNTEREXAMPLE_FOUND
                    )
                )
                valid = (
                    hash_valid
                    and tool_version_valid
                    and program_hash_valid
                    and program_binding_valid
                    and execution_valid
                    and evidence_record_valid
                    and replay_matches
                    and counterexample_valid
                )
                records.append(
                    {
                        "request_hash": expected.request_hash,
                        "expected_result_hash": expected.result_hash,
                        "replayed_result_hash": replay.result_hash if replay else None,
                        "key_evidence": key_evidence,
                        "request_hash_valid": hash_valid,
                        "tool_version_valid": tool_version_valid,
                        "program_hash_valid": program_hash_valid,
                        "program_binding_valid": program_binding_valid,
                        "execution_record_valid": execution_valid,
                        "evidence_record_valid": evidence_record_valid,
                        "replay_matches": replay_matches,
                        "counterexample_independently_verified": counterexample_valid,
                        "valid": valid,
                    }
                )
            except Exception as exc:
                records.append(
                    {
                        "request_hash": request_hash,
                        "expected_result_hash": (
                            expected.result_hash
                            if expected is not None
                            else payload.get("result_hash")
                        ),
                        "valid": False,
                        "error": f"{type(exc).__name__}: {exc}",
                    }
                )
        self.store.write_json("reports", report_name, records)
        self.store.append_event(report_name, records)
        return records

    def _dispatch(
        self, spec: ExperimentSpec, program: ExperimentProgram | None
    ) -> HandlerEvidence:
        if spec.method in {
            ComputationMethod.SYMPY_SIMPLIFY,
            ComputationMethod.SYMPY_EQUIVALENT,
            ComputationMethod.POLYNOMIAL_FACTOR,
            ComputationMethod.NUMERIC_COUNTEREXAMPLE,
        }:
            return run_symbolic(spec, random.Random(spec.seed))
        if spec.method == ComputationMethod.MODULAR_EXHAUSTIVE:
            return run_modular(spec)
        if spec.method == ComputationMethod.BOUNDED_INTEGER_SEARCH:
            return run_bounded_integer_search(spec)
        if spec.method == ComputationMethod.GRAPH_CERTIFICATE:
            return run_graph_certificate(spec)
        if spec.method == ComputationMethod.RECURRENCE_CHECK:
            return run_recurrence_check(spec)
        if spec.method == ComputationMethod.BOUNDED_GREEDY_SEQUENCE:
            return run_bounded_greedy_sequence(spec)
        if spec.method == ComputationMethod.CANDIDATE_PERIOD_CHECK:
            return run_candidate_period_check(spec)
        if spec.method == ComputationMethod.EXACT_GEOMETRY:
            return run_exact_geometry(spec)
        if spec.method == ComputationMethod.REAL_INEQUALITY:
            return run_real_inequality(spec)
        if spec.method == ComputationMethod.NUMBER_THEORY_CHECK:
            return run_number_theory(spec)
        if spec.method == ComputationMethod.SANDBOXED_PYTHON:
            if program is None:
                raise ValueError("sandboxed_python requires an ExperimentProgram")
            return run_sandboxed_python(spec, program, self.config.computation)
        if spec.method == ComputationMethod.LEAN_CHECK:
            payload = self._lean_check(spec.arguments)
            if payload["accepted"]:
                return HandlerEvidence(
                    outcome=ExperimentOutcome.CERTIFIED,
                    evidence_strength=EvidenceStrength.FORMAL_CERTIFICATE,
                    certificate=payload,
                    exact_arithmetic=True,
                    independently_verified=True,
                    verification_notes=[
                        "Lean accepted the fragment; a reviewer must still verify the natural-language-to-Lean mapping."
                    ],
                )
            return HandlerEvidence(
                outcome=ExperimentOutcome.INCONCLUSIVE,
                evidence_strength=EvidenceStrength.HEURISTIC,
                scope={"lean_returncode": payload["returncode"]},
                raw_output={"lean_rejection": payload},
                independently_verified=False,
                verification_notes=[
                    "Lean did not accept the submitted fragment as a complete "
                    "formal proof; a formalization failure only shows that this "
                    "particular encoding did not certify and does not refute "
                    "the target claim."
                ],
            )
        raise ValueError(f"unsupported computation method: {spec.method.value}")

    @staticmethod
    def _program_identity(program: ExperimentProgram | None) -> str | None:
        if program is None:
            return None
        return stable_hash(
            {
                "source": program.source,
                "input_schema": program.input_schema,
                "output_schema": program.output_schema,
                "dependencies": program.dependencies,
                "code_hash": program.code_hash,
            }
        )

    def _tool_identity(self, method: ComputationMethod) -> tuple[str, str]:
        if method in {
            ComputationMethod.BOUNDED_GREEDY_SEQUENCE,
            ComputationMethod.CANDIDATE_PERIOD_CHECK,
        }:
            python_version = ".".join(str(item) for item in sys.version_info[:3])
            return (
                method.value,
                f"python-stdlib/{python_version};sequence-v2;{TOOL_VERSION}",
            )
        if method.value.startswith("sympy") or method in {
            ComputationMethod.POLYNOMIAL_FACTOR,
            ComputationMethod.MODULAR_EXHAUSTIVE,
            ComputationMethod.RECURRENCE_CHECK,
            ComputationMethod.NUMBER_THEORY_CHECK,
        }:
            return method.value, f"sympy/{sp.__version__};{TOOL_VERSION}"
        if method in {
            ComputationMethod.BOUNDED_INTEGER_SEARCH,
            ComputationMethod.REAL_INEQUALITY,
        }:
            try:
                dependency = version("z3-solver")
            except PackageNotFoundError:
                dependency = "missing"
            return method.value, f"z3-solver/{dependency};{TOOL_VERSION}"
        if method == ComputationMethod.GRAPH_CERTIFICATE:
            try:
                dependency = version("networkx")
            except PackageNotFoundError:
                dependency = "missing"
            return method.value, f"networkx/{dependency};{TOOL_VERSION}"
        if method == ComputationMethod.LEAN_CHECK:
            command = shlex.split(self.config.verification.lean_command)
            command_hash = stable_hash(command)[:16]
            image = self.config.verification.lean_sandbox_image
            lean_version = (
                f"sandbox-{stable_hash(image)[:16]}" if image else "unconfigured"
            )
            return (
                method.value,
                f"lean/{lean_version};command/{command_hash};{TOOL_VERSION}",
            )
        return method.value, TOOL_VERSION

    def execute_many(self, requests: list[ToolRequest]) -> list[ToolResult]:
        return [self.execute(request) for request in requests]

    def execute(self, request: ToolRequest) -> ToolResult:
        """Compatibility API for deterministic requests emitted by proof reviewers."""
        try:
            if request.kind == "sympy_simplify":
                if not self.config.verification.enable_sympy_tools:
                    raise RuntimeError("SymPy tools are disabled")
                payload = sympy_simplify_payload(request.arguments)
            elif request.kind == "sympy_equivalent":
                if not self.config.verification.enable_sympy_tools:
                    raise RuntimeError("SymPy tools are disabled")
                payload = sympy_equivalent_payload(request.arguments)
            elif request.kind == "polynomial_factor":
                if not self.config.verification.enable_sympy_tools:
                    raise RuntimeError("SymPy tools are disabled")
                payload = polynomial_factor_payload(request.arguments)
            elif request.kind == "numeric_counterexample":
                if not self.config.verification.enable_numeric_counterexamples:
                    raise RuntimeError("numeric counterexample search is disabled")
                payload = numeric_counterexample_payload(
                    request.arguments,
                    random.Random(self.config.runtime.random_seed),
                )
            elif request.kind == "lean_check":
                payload = self._lean_check(request.arguments)
            else:
                method = ComputationMethod(request.kind)
                arguments = dict(request.arguments)
                legacy_domains = arguments.pop("domains", {})
                legacy_max_cases = arguments.pop("max_cases", request.max_cases)
                spec = ExperimentSpec(
                    purpose="falsify_claim",
                    target_claim=request.purpose,
                    assumptions=[],
                    reasoning_basis="An independent verifier requested this exact deterministic check.",
                    why_computation_is_needed="The calculation is safer and faster in a typed checker.",
                    decision_if_confirmed="Keep the claim pending mathematical review.",
                    decision_if_refuted="Fail the affected inference after independent rechecking.",
                    noncomputational_alternative="Manual recomputation remains available to the reviewer.",
                    method=method,
                    domains=request.domains or legacy_domains,
                    arguments=arguments,
                    exact_arithmetic=method != ComputationMethod.NUMERIC_COUNTEREXAMPLE,
                    max_cases=min(
                        int(legacy_max_cases),
                        self.config.computation.max_cases_per_experiment,
                    ),
                    seed=self.config.runtime.random_seed,
                )
                evidence = self._dispatch(spec, None)
                payload = {
                    "outcome": evidence.outcome.value,
                    "evidence_strength": evidence.evidence_strength.value,
                    "scope": evidence.scope,
                    "counterexample": evidence.counterexample,
                    "certificate": evidence.certificate,
                    "cases_checked": evidence.cases_checked,
                    "independently_verified": evidence.independently_verified,
                }
            evidence_ref = self.store.write_content_addressed(
                "tools",
                {
                    "request": request.model_dump(mode="json"),
                    "ok": True,
                    "result": payload,
                },
                summary=f"Tool result for {request.kind}",
            )
            result = ToolResult(
                request_id=request.request_id,
                kind=request.kind,
                ok=True,
                result=payload,
                evidence_ref=evidence_ref,
            )
        except Exception as exc:
            evidence_ref = self.store.write_content_addressed(
                "tools",
                {
                    "request": request.model_dump(mode="json"),
                    "ok": False,
                    "error": str(exc),
                },
                summary=f"Failed tool request for {request.kind}",
            )
            result = ToolResult(
                request_id=request.request_id,
                kind=request.kind,
                ok=False,
                error=str(exc),
                evidence_ref=evidence_ref,
            )
        self.store.append_event("tool_result", result)
        return result

    def _lean_check(self, args: dict[str, Any]) -> dict[str, Any]:
        if not self.config.verification.enable_lean:
            raise RuntimeError("Lean execution is disabled")
        command = shlex.split(self.config.verification.lean_command)
        if not command:
            raise RuntimeError("Lean command is empty")
        source = str(args["source"])
        with tempfile.TemporaryDirectory(prefix="mathproofmesh_lean_") as tmpdir:
            path = Path(tmpdir) / "Main.lean"
            path.write_text(source, encoding="utf-8")
            if not self.config.verification.lean_sandbox_image:
                return {
                    "returncode": -1,
                    "stdout": "",
                    "stderr": "",
                    "accepted": False,
                    "reason": "lean_sandbox_unconfigured",
                }
            docker = _find_docker_executable()
            if docker is None:
                return {
                    "returncode": -1,
                    "stdout": "",
                    "stderr": "",
                    "accepted": False,
                    "reason": "lean_sandbox_unavailable",
                }
            execution_command = build_lean_docker_command(
                self.config.verification,
                Path(tmpdir),
                docker_executable=docker,
            )
            completed = subprocess.run(
                execution_command,
                cwd=None,
                capture_output=True,
                text=True,
                timeout=self.config.verification.external_tool_timeout_seconds,
                check=False,
            )
        stdout = completed.stdout.replace(tmpdir, "<sandbox>")
        stderr = completed.stderr.replace(tmpdir, "<sandbox>")
        payload = {
            "returncode": completed.returncode,
            "stdout": stdout[-20_000:],
            "stderr": stderr[-20_000:],
            "accepted": completed.returncode == 0,
        }
        if payload["accepted"]:
            marker = _lean_incomplete_marker(source, stdout, stderr)
            if marker is not None:
                payload["accepted"] = False
                payload["reason"] = "lean_incomplete_proof_marker"
                payload["marker"] = marker
        return payload


class ToolBroker(ComputationBroker):
    """Backward-compatible name for the unified computation broker."""
