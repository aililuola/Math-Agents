from __future__ import annotations

import inspect
from collections.abc import Awaitable, Callable, Mapping, Sequence
from typing import Any, TypeAlias

from ..schemas import stable_hash
from .models import (
    ControlActionRecord,
    ControlActionResult,
    ControlActionStatus,
    ControlActionType,
)

ActionHandlerResult: TypeAlias = (
    ControlActionResult | Mapping[str, Any] | Sequence[str] | str | None
)
ActionHandler: TypeAlias = Callable[
    [ControlActionRecord], ActionHandlerResult | Awaitable[ActionHandlerResult]
]
ActionPostcondition: TypeAlias = Callable[
    [ControlActionRecord, ControlActionResult], bool
]
ExistenceCheck: TypeAlias = Callable[[str], bool]
BudgetAdmission: TypeAlias = Callable[[ControlActionRecord], bool | tuple[bool, str]]
CheckpointWriter: TypeAlias = Callable[[ControlActionRecord], None]


class ControlActionDispatcher:
    """Materialize proof-control decisions once through registered authorities."""

    def __init__(
        self,
        *,
        problem_hash: str,
        actions: dict[str, ControlActionRecord] | None = None,
        mode: str = "active",
        source_exists: ExistenceCheck | None = None,
        route_exists: ExistenceCheck | None = None,
        obligation_exists: ExistenceCheck | None = None,
        budget_admission: BudgetAdmission | None = None,
        checkpoint_writer: CheckpointWriter | None = None,
    ) -> None:
        if not problem_hash:
            raise ValueError("problem_hash is required for control-action idempotency")
        if mode not in {"off", "shadow", "active"}:
            raise ValueError(f"unsupported proof-control mode: {mode}")
        self.problem_hash = problem_hash
        self.actions = actions if actions is not None else {}
        self.mode = mode
        self.source_exists = source_exists
        self.route_exists = route_exists
        self.obligation_exists = obligation_exists
        self.budget_admission = budget_admission
        self.checkpoint_writer = checkpoint_writer
        self._handlers: dict[ControlActionType, ActionHandler] = {}
        self._postconditions: dict[ControlActionType, ActionPostcondition] = {}
        self._by_key = {
            action.idempotency_key: action.action_id for action in self.actions.values()
        }

    @staticmethod
    def make_idempotency_key(
        *,
        problem_hash: str,
        action_type: ControlActionType,
        source_record_ids: Sequence[str] = (),
        route_ids: Sequence[str] = (),
        target_obligation_ids: Sequence[str] = (),
        payload: Mapping[str, Any] | None = None,
    ) -> str:
        return stable_hash(
            {
                "problem_hash": problem_hash,
                "action_type": action_type.value,
                "source_record_ids": sorted(set(source_record_ids)),
                "route_ids": sorted(set(route_ids)),
                "target_obligation_ids": sorted(set(target_obligation_ids)),
                "payload": dict(payload or {}),
            }
        )

    def register_handler(
        self,
        action_type: ControlActionType,
        handler: ActionHandler,
        *,
        postcondition: ActionPostcondition,
    ) -> None:
        self._handlers[action_type] = handler
        self._postconditions[action_type] = postcondition

    def propose(
        self,
        action_type: ControlActionType,
        *,
        source_record_ids: Sequence[str] = (),
        route_ids: Sequence[str] = (),
        target_obligation_ids: Sequence[str] = (),
        payload: Mapping[str, Any] | None = None,
        current_round: int = 0,
    ) -> ControlActionRecord:
        sources = sorted(set(source_record_ids))
        routes = sorted(set(route_ids))
        targets = sorted(set(target_obligation_ids))
        stable_payload = dict(payload or {})
        key = self.make_idempotency_key(
            problem_hash=self.problem_hash,
            action_type=action_type,
            source_record_ids=sources,
            route_ids=routes,
            target_obligation_ids=targets,
            payload=stable_payload,
        )
        existing_id = self._by_key.get(key)
        if existing_id is not None:
            return self.actions[existing_id]
        action = ControlActionRecord(
            action_type=action_type,
            source_record_ids=sources,
            route_ids=routes,
            target_obligation_ids=targets,
            payload=stable_payload,
            idempotency_key=key,
            created_round=current_round,
        )
        self.actions[action.action_id] = action
        self._by_key[key] = action.action_id
        self._checkpoint(action)
        return action

    def admit(
        self,
        action_id: str,
        *,
        current_round: int | None = None,
    ) -> ControlActionRecord:
        action = self._get(action_id)
        if action.status in {
            ControlActionStatus.ADMITTED,
            ControlActionStatus.EXECUTING,
            ControlActionStatus.EXECUTED,
            ControlActionStatus.REJECTED,
            ControlActionStatus.FAILED,
        }:
            return action
        if self.mode == "off":
            return self._reject(action, "proof control is off")

        missing_sources = self._missing(action.source_record_ids, self.source_exists)
        if missing_sources:
            return self._reject(
                action,
                "unknown source record(s): " + ", ".join(missing_sources),
            )
        missing_routes = self._missing(action.route_ids, self.route_exists)
        if missing_routes:
            return self._reject(
                action,
                "unknown target route(s): " + ", ".join(missing_routes),
            )
        missing_obligations = self._missing(
            action.target_obligation_ids, self.obligation_exists
        )
        if missing_obligations:
            return self._reject(
                action,
                "unknown target obligation(s): " + ", ".join(missing_obligations),
            )

        budget_allowed, budget_reason = self._budget_decision(action)
        if not budget_allowed:
            action.status = ControlActionStatus.DEFERRED
            action.admission_reason = (
                budget_reason or "control-action budget unavailable"
            )
            action.failure_reason = ""
            self._checkpoint(action)
            return action

        action.status = ControlActionStatus.ADMITTED
        action.admission_reason = (
            budget_reason or "source, target, and budget checks passed"
        )
        action.failure_reason = ""
        if current_round is not None and action.created_round > current_round:
            action.created_round = current_round
        self._checkpoint(action)
        return action

    async def execute(
        self,
        action_id: str,
        *,
        current_round: int,
    ) -> ControlActionRecord:
        action = self._get(action_id)
        if action.status == ControlActionStatus.EXECUTED:
            return action
        if action.status in {
            ControlActionStatus.PROPOSED,
            ControlActionStatus.DEFERRED,
        }:
            action = self.admit(action_id, current_round=current_round)
        if action.status in {
            ControlActionStatus.REJECTED,
            ControlActionStatus.FAILED,
            ControlActionStatus.DEFERRED,
        }:
            return action
        if self.mode == "shadow":
            return action

        handler = self._handlers.get(action.action_type)
        postcondition = self._postconditions.get(action.action_type)
        if handler is None or postcondition is None:
            action.status = ControlActionStatus.DEFERRED
            action.admission_reason = "no authority handler is registered"
            self._checkpoint(action)
            return action

        if action.status == ControlActionStatus.EXECUTING:
            recovered = ControlActionResult(
                result_refs=list(action.result_refs),
                postcondition_met=True,
                detail="resume postcondition probe",
            )
            if action.result_refs and postcondition(action, recovered):
                action.status = ControlActionStatus.EXECUTED
                action.executed_round = current_round
                action.failure_reason = ""
                self._checkpoint(action)
                return action

        action.status = ControlActionStatus.EXECUTING
        action.executed_round = None
        self._checkpoint(action)
        try:
            raw_result = handler(action)
            if inspect.isawaitable(raw_result):
                raw_result = await raw_result
            result = self._normalize_result(raw_result)
        except Exception as exc:
            action.status = ControlActionStatus.FAILED
            action.failure_reason = f"{type(exc).__name__}: {exc}"
            self._checkpoint(action)
            raise

        action.result_refs = sorted(set(result.result_refs))
        postcondition_met = (
            result.postcondition_met
            and bool(action.result_refs)
            and postcondition(action, result)
        )
        if not postcondition_met:
            action.status = ControlActionStatus.FAILED
            action.failure_reason = result.detail or "action postcondition failed"
            self._checkpoint(action)
            return action

        action.status = ControlActionStatus.EXECUTED
        action.executed_round = current_round
        action.failure_reason = ""
        self._checkpoint(action)
        return action

    async def resume_pending(self, *, current_round: int) -> list[ControlActionRecord]:
        resumed: list[ControlActionRecord] = []
        pending = sorted(
            (
                action
                for action in self.actions.values()
                if action.status
                in {
                    ControlActionStatus.ADMITTED,
                    ControlActionStatus.EXECUTING,
                    ControlActionStatus.DEFERRED,
                }
            ),
            key=lambda action: (action.created_round, action.action_id),
        )
        for action in pending:
            resumed.append(
                await self.execute(action.action_id, current_round=current_round)
            )
        return resumed

    def export_state(self) -> dict[str, Any]:
        return {
            "control_actions": {
                action_id: self.actions[action_id].model_dump(mode="json")
                for action_id in sorted(self.actions)
            }
        }

    def _get(self, action_id: str) -> ControlActionRecord:
        try:
            return self.actions[action_id]
        except KeyError as exc:
            raise KeyError(f"unknown control action: {action_id}") from exc

    @staticmethod
    def _missing(values: Sequence[str], checker: ExistenceCheck | None) -> list[str]:
        if checker is None:
            return []
        return [value for value in values if not checker(value)]

    def _budget_decision(self, action: ControlActionRecord) -> tuple[bool, str]:
        if self.budget_admission is None:
            return True, ""
        decision = self.budget_admission(action)
        if isinstance(decision, tuple):
            return bool(decision[0]), str(decision[1])
        return bool(decision), ""

    def _reject(self, action: ControlActionRecord, reason: str) -> ControlActionRecord:
        action.status = ControlActionStatus.REJECTED
        action.admission_reason = reason
        action.failure_reason = reason
        self._checkpoint(action)
        return action

    def _checkpoint(self, action: ControlActionRecord) -> None:
        if self.checkpoint_writer is not None:
            self.checkpoint_writer(action)

    @staticmethod
    def _normalize_result(result: ActionHandlerResult) -> ControlActionResult:
        if isinstance(result, ControlActionResult):
            return result
        if isinstance(result, Mapping):
            return ControlActionResult.model_validate(result)
        if isinstance(result, str):
            return ControlActionResult(
                result_refs=[result],
                postcondition_met=True,
            )
        if isinstance(result, Sequence):
            return ControlActionResult(
                result_refs=[str(item) for item in result],
                postcondition_met=True,
            )
        return ControlActionResult(
            postcondition_met=False,
            detail="authority handler returned no execution result",
        )
