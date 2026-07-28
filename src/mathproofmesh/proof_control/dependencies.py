from __future__ import annotations

from collections.abc import Iterable, Mapping, Sequence
from typing import Any

from ..schemas import ClaimCard, ProofStep, stable_hash
from .models import (
    DependencyKind,
    DependencyMigrationResult,
    DependencyNormalizationTask,
    DependencyRef,
    DependencyResolutionResult,
)


_PREFIXES = {
    "step": DependencyKind.LOCAL_STEP,
    "claim": DependencyKind.LOCAL_CLAIM,
    "fact": DependencyKind.GLOBAL_FACT,
    "msg": DependencyKind.MESSAGE,
    "obl": DependencyKind.OBLIGATION,
    "tool": DependencyKind.TOOL_CERTIFICATE,
    "formal": DependencyKind.FORMAL_CERTIFICATE,
    "external": DependencyKind.EXTERNAL_RESULT,
}


def migrate_legacy_dependencies(
    dependencies: Sequence[str],
    *,
    source_attempt_id: str | None = None,
    source_delta_id: str | None = None,
    source_route_id: str | None = None,
    local_step_ids: Iterable[str] = (),
    local_claim_ids: Iterable[str] = (),
    broker_fact_ids: Iterable[str] = (),
) -> DependencyMigrationResult:
    steps = set(local_step_ids)
    claims = set(local_claim_ids)
    facts = set(broker_fact_ids)
    refs: list[DependencyRef] = []
    ambiguous: list[str] = []
    for raw_value in dependencies:
        value = str(raw_value).strip()
        if not value:
            continue
        prefix, separator, target = value.partition(":")
        kind = _PREFIXES.get(prefix.casefold()) if separator else None
        if kind is None:
            target = value
            if value in steps:
                kind = DependencyKind.LOCAL_STEP
            elif value in claims:
                kind = DependencyKind.LOCAL_CLAIM
            elif value in facts:
                kind = DependencyKind.GLOBAL_FACT
            else:
                ambiguous.append(value)
                continue
        if not target:
            ambiguous.append(value)
            continue
        refs.append(
            DependencyRef(
                kind=kind,
                target_id=target,
                source_attempt_id=(
                    source_attempt_id
                    if kind in {DependencyKind.LOCAL_STEP, DependencyKind.LOCAL_CLAIM}
                    else None
                ),
                source_delta_id=(
                    source_delta_id if kind == DependencyKind.LOCAL_STEP else None
                ),
                source_route_id=source_route_id,
            )
        )
    normalization_task = None
    if ambiguous:
        normalization_task = DependencyNormalizationTask(
            task_id=(
                "dependency_normalization_"
                + stable_hash(
                    {
                        "source_attempt_id": source_attempt_id,
                        "source_delta_id": source_delta_id,
                        "ambiguous_ids": sorted(set(ambiguous)),
                    }
                )[:20]
            ),
            source_attempt_id=source_attempt_id,
            source_delta_id=source_delta_id,
            ambiguous_ids=sorted(set(ambiguous)),
        )
    return DependencyMigrationResult(
        dependency_refs=refs,
        migration_status="ambiguous" if ambiguous else "complete",
        normalization_task=normalization_task,
    )


class DependencyResolver:
    """Resolve each dependency only against its declared namespace and scope."""

    def __init__(
        self,
        *,
        local_steps: Mapping[str, ProofStep] | None = None,
        structurally_verified_step_ids: Iterable[str] = (),
        invalidated_local_step_ids: Iterable[str] = (),
        local_claims: Mapping[str, ClaimCard] | None = None,
        verified_local_claim_ids: Iterable[str] = (),
        broker_fact_ids: Iterable[str] = (),
        broker_fact_hashes: Iterable[str] = (),
        message_ids: Iterable[str] = (),
        obligation_ids: Iterable[str] = (),
        tool_certificate_ids: Iterable[str] = (),
        formal_certificate_ids: Iterable[str] = (),
        external_result_ids: Iterable[str] = (),
        source_attempt_id: str | None = None,
        source_delta_id: str | None = None,
        source_route_id: str | None = None,
    ) -> None:
        self.local_steps = dict(local_steps or {})
        self.verified_steps = set(structurally_verified_step_ids)
        self.invalidated_steps = set(invalidated_local_step_ids)
        self.local_claims = dict(local_claims or {})
        self.verified_claims = set(verified_local_claim_ids)
        self.broker_fact_ids = set(broker_fact_ids)
        self.broker_fact_hashes = set(broker_fact_hashes)
        self.message_ids = set(message_ids)
        self.obligation_ids = set(obligation_ids)
        self.tool_certificate_ids = set(tool_certificate_ids)
        self.formal_certificate_ids = set(formal_certificate_ids)
        self.external_result_ids = set(external_result_ids)
        self.source_attempt_id = source_attempt_id
        self.source_delta_id = source_delta_id
        self.source_route_id = source_route_id

    def resolve_local_step(self, ref: DependencyRef) -> bool:
        return (
            ref.target_id in self.local_steps
            and ref.target_id in self.verified_steps
            and ref.target_id not in self.invalidated_steps
            and (
                self.source_delta_id is None
                or ref.source_delta_id in {None, self.source_delta_id}
            )
        )

    def resolve_local_claim(self, ref: DependencyRef) -> bool:
        claim = self.local_claims.get(ref.target_id)
        return bool(
            claim is not None
            and ref.target_id in self.verified_claims
            and (
                ref.source_attempt_id is None
                or claim.source_attempt_id in {None, ref.source_attempt_id}
            )
            and (
                self.source_attempt_id is None
                or ref.source_attempt_id in {None, self.source_attempt_id}
            )
        )

    def resolve_global_fact(self, ref: DependencyRef) -> bool:
        return bool(
            ref.target_id in self.broker_fact_ids
            or (
                ref.content_hash is not None
                and ref.content_hash in self.broker_fact_hashes
            )
        )

    def resolve_message(self, ref: DependencyRef) -> bool:
        return ref.target_id in self.message_ids

    def resolve_obligation(self, ref: DependencyRef) -> bool:
        return ref.target_id in self.obligation_ids

    def resolve_certificate(self, ref: DependencyRef) -> bool:
        if ref.kind == DependencyKind.TOOL_CERTIFICATE:
            return ref.target_id in self.tool_certificate_ids
        return ref.target_id in self.formal_certificate_ids

    def resolve_external(self, ref: DependencyRef) -> bool:
        return ref.target_id in self.external_result_ids

    def resolve_all(
        self,
        refs: Sequence[DependencyRef | Mapping[str, Any]],
    ) -> DependencyResolutionResult:
        resolved: list[DependencyRef] = []
        missing: list[DependencyRef] = []
        invalid: list[DependencyRef] = []
        for value in refs:
            ref = (
                value
                if isinstance(value, DependencyRef)
                else DependencyRef.model_validate(value)
            )
            if (
                ref.kind == DependencyKind.LOCAL_STEP
                and ref.target_id in self.invalidated_steps
            ):
                invalid.append(ref)
                continue
            checks = {
                DependencyKind.LOCAL_STEP: self.resolve_local_step,
                DependencyKind.LOCAL_CLAIM: self.resolve_local_claim,
                DependencyKind.GLOBAL_FACT: self.resolve_global_fact,
                DependencyKind.MESSAGE: self.resolve_message,
                DependencyKind.OBLIGATION: self.resolve_obligation,
                DependencyKind.TOOL_CERTIFICATE: self.resolve_certificate,
                DependencyKind.FORMAL_CERTIFICATE: self.resolve_certificate,
                DependencyKind.EXTERNAL_RESULT: self.resolve_external,
            }
            if checks[ref.kind](ref):
                resolved.append(ref)
            else:
                missing.append(ref)
        return DependencyResolutionResult(
            resolved=not missing and not invalid,
            resolved_refs=resolved,
            missing_refs=missing,
            invalid_refs=invalid,
        )
