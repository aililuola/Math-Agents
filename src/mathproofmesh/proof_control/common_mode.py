from __future__ import annotations

import re
import unicodedata
from collections.abc import Mapping, Sequence
from typing import Any

from ..config import CommonModeControlConfig
from ..proof_identity import canonical_obligation_statement, normalize_text
from ..schemas import (
    ClaimStatus,
    MemoryTier,
    MessageEnvelope,
    ObligationKind,
    ProofAttempt,
    ProofObligation,
    RouteDescriptor,
    RouteStatus,
    Severity,
    StrategyCard,
    VerificationReport,
    VerificationVerdict,
    stable_hash,
)
from .domains import classify_assumption_domain
from .models import (
    AssumptionChallengerTask,
    AssumptionDomain,
    AssumptionDomainRecord,
    AssumptionFamily,
    CriticalAssumption,
    DependencyAtom,
    DependencyKind,
    DependencyRef,
    ScopeSignature,
)
from .semantic_profile import conservatively_matches_across_languages
from .semantic_quality import ObligationSemanticGate


class CriticalAssumptionMatrix:
    """Measure route dependence without treating agreement as verification."""

    _SCHEDULABLE_ROUTE_STATUSES = {
        RouteStatus.ACTIVE,
        RouteStatus.WAITING,
        RouteStatus.REPAIR_ONCE,
        RouteStatus.COOLING,
    }
    _STRIP_PREFIX = re.compile(
        r"^(?:we\s+)?(?:assume|suppose|using|use|hypothesis)\s*(?:that)?\s*[:,-]?\s*",
        re.IGNORECASE,
    )
    _STRIP_PREFIX_CJK = re.compile(r"^(?:假设|假定|设)\s*[:：，,]?\s*")
    _CJK_RUN = re.compile(r"[\u3400-\u9fff]+")
    _CJK_PARTICLES = str.maketrans("", "", "的地得之是为均都")
    _JUSTIFICATION_ASSUMPTION = re.compile(
        r"(?:^|[.;]\s*)(?:assume|suppose|requires?|provided\s+that|using)\s+"
        r"(?:that\s+)?(?P<statement>[^.;]+)",
        re.IGNORECASE,
    )

    def __init__(self, config: CommonModeControlConfig | None = None) -> None:
        self.config = config or CommonModeControlConfig()
        self.usage: dict[str, dict[str, float]] = {}
        self.assumptions: dict[str, CriticalAssumption] = {}
        self.families: dict[str, AssumptionFamily] = {}
        self.atoms: dict[str, DependencyAtom] = {}
        self.domain_records: dict[str, AssumptionDomainRecord] = {}
        self.semantic_gate = ObligationSemanticGate()

    def build(
        self,
        routes: Sequence[RouteDescriptor],
        strategies: Sequence[StrategyCard],
        messages: Sequence[MessageEnvelope] = (),
        obligations: Sequence[ProofObligation] = (),
        *,
        attempts: Sequence[ProofAttempt] = (),
        reports: Sequence[VerificationReport] = (),
        scope_signatures: Mapping[str, ScopeSignature] | None = None,
        prior_atoms: Sequence[DependencyAtom] = (),
    ) -> dict[str, CriticalAssumption]:
        active_route_ids = {
            route.route_id
            for route in routes
            if route.status in self._SCHEDULABLE_ROUTE_STATUSES
        }
        strategy_routes = {route.strategy_id: route.route_id for route in routes}
        route_by_id = {route.route_id: route for route in routes}
        obligation_by_id = {
            obligation.obligation_id: obligation for obligation in obligations
        }
        message_by_id = {message.message_id: message for message in messages}
        scope_signatures = dict(scope_signatures or {})
        raw: dict[tuple[str, tuple[str, ...], str], dict[str, Any]] = {}
        atoms: dict[str, DependencyAtom] = {}

        def add(
            statement: str,
            *,
            route_id: str,
            source_subject_id: str,
            weight: float,
            verified: bool = False,
            require_truth_apt: bool = False,
            source_kind: str,
            dependency_refs: Sequence[DependencyRef] = (),
            typed_dependency_ids: Sequence[str] = (),
            scope_signature_id: str | None = None,
            proof_graph_neighborhood: Sequence[str] = (),
            mechanism_signature: Sequence[str] = (),
            atom_id: str | None = None,
        ) -> None:
            normalized = self._normalize_assumption(statement)
            if not normalized or not route_id:
                return
            domain_record = classify_assumption_domain(statement)
            self.domain_records[
                f"{source_subject_id}:{stable_hash(normalized)[:12]}"
            ] = domain_record
            if domain_record.domain != AssumptionDomain.MATHEMATICAL:
                return
            if (
                require_truth_apt
                and not self.semantic_gate.assess_statement(
                    statement,
                    source_kind="mathematical",
                ).truth_apt
            ):
                return
            refs = list(dependency_refs)
            typed_ids = sorted(set(typed_dependency_ids))
            neighborhood = sorted(set(proof_graph_neighborhood))
            stable_atom_id = atom_id or (
                "dependency_atom_"
                + stable_hash(
                    {
                        "normalized_statement": normalized,
                        "source_kind": source_kind,
                        "source_id": source_subject_id,
                        "route_id": route_id,
                        "typed_dependency_ids": typed_ids,
                    }
                )[:20]
            )
            atom = DependencyAtom(
                atom_id=stable_atom_id,
                statement=statement,
                normalized_statement=normalized,
                statement_hash=stable_hash(normalized),
                source_kind=source_kind,
                source_id=source_subject_id,
                route_id=route_id,
                verification_status=(
                    ClaimStatus.VERIFIED if verified else ClaimStatus.PROPOSED
                ),
                dependency_refs=refs,
                typed_dependency_ids=typed_ids,
                scope_signature_id=scope_signature_id,
                proof_graph_neighborhood=neighborhood,
                mechanism_signature=list(dict.fromkeys(mechanism_signature)),
                load_bearing_score=max(0.0, min(1.0, float(weight))),
                domain=AssumptionDomain.MATHEMATICAL,
            )
            atoms[stable_atom_id] = atom
            identity = (
                normalized,
                tuple(typed_ids),
                scope_signature_id or "",
            )
            record = raw.setdefault(
                identity,
                {
                    "normalized_statement": normalized,
                    "sources": set(),
                    "routes": {},
                    "verified": False,
                    "authoritative_verified": False,
                    "semantic_tags": self._semantic_tags(normalized),
                    "atom_ids": set(),
                    "typed_dependency_ids": set(),
                    "scope_signature_ids": set(),
                    "proof_graph_neighborhood": set(),
                    "load_bearing_score": 0.0,
                },
            )
            record["sources"].add(source_subject_id)
            record["routes"][route_id] = max(
                weight, record["routes"].get(route_id, 0.0)
            )
            record["verified"] = bool(record["verified"] or verified)
            record["authoritative_verified"] = bool(
                record["authoritative_verified"]
                or (
                    verified
                    and source_kind in {"verified_fact", "obligation_assumption"}
                )
            )
            record["atom_ids"].add(stable_atom_id)
            record["typed_dependency_ids"].update(typed_ids)
            if scope_signature_id:
                record["scope_signature_ids"].add(scope_signature_id)
            record["proof_graph_neighborhood"].update(neighborhood)
            record["load_bearing_score"] = max(
                float(record["load_bearing_score"]),
                float(weight),
            )

        def mechanism_for(route_id: str) -> list[str]:
            route = route_by_id.get(route_id)
            return list(route.mechanism_signature) if route is not None else []

        if self.config.include_strategy_prerequisites:
            for strategy in strategies:
                route_id = strategy_routes.get(strategy.strategy_id, "")
                for prerequisite in strategy.prerequisites:
                    add(
                        prerequisite,
                        route_id=route_id,
                        source_subject_id=strategy.strategy_id,
                        weight=0.9,
                        source_kind="strategy_prerequisite",
                        mechanism_signature=mechanism_for(route_id),
                    )
        if self.config.include_critical_claims:
            for strategy in strategies:
                route_id = strategy_routes.get(strategy.strategy_id, "")
                for claim in strategy.critical_claims:
                    add(
                        claim.statement,
                        route_id=route_id,
                        source_subject_id=claim.claim_id,
                        weight=1.0 if claim.necessity == "required" else 0.5,
                        verified=claim.status == "verified",
                        require_truth_apt=True,
                        source_kind="critical_claim",
                        mechanism_signature=mechanism_for(route_id),
                    )
        for message in messages:
            for assumption in message.assumptions:
                add(
                    assumption,
                    route_id=message.source_route_id,
                    source_subject_id=message.message_id,
                    weight=0.75,
                    source_kind="message_assumption",
                    typed_dependency_ids=[f"message:{message.message_id}"],
                    scope_signature_id=(
                        message.message_id
                        if message.message_id in scope_signatures
                        else None
                    ),
                    proof_graph_neighborhood=message.dependencies,
                    mechanism_signature=mechanism_for(message.source_route_id),
                )
            if (
                message.memory_tier == MemoryTier.FACT
                and message.verification_status == ClaimStatus.VERIFIED
            ):
                add(
                    message.normalized_statement,
                    route_id=message.source_route_id,
                    source_subject_id=message.message_id,
                    weight=0.0,
                    verified=True,
                    source_kind="verified_fact",
                    typed_dependency_ids=[f"message:{message.message_id}"],
                    scope_signature_id=(
                        message.message_id
                        if message.message_id in scope_signatures
                        else None
                    ),
                    proof_graph_neighborhood=message.dependencies,
                    mechanism_signature=mechanism_for(message.source_route_id),
                )
        if self.config.include_unverified_dependencies:
            for obligation in obligations:
                for route_id in obligation.route_ids:
                    for assumption in obligation.assumptions:
                        add(
                            assumption,
                            route_id=route_id,
                            source_subject_id=obligation.obligation_id,
                            weight=0.8,
                            verified=obligation.kind == ObligationKind.MAIN_GOAL,
                            source_kind="obligation_assumption",
                            typed_dependency_ids=[
                                f"obligation:{obligation.obligation_id}"
                            ],
                            scope_signature_id=(
                                obligation.obligation_id
                                if obligation.obligation_id in scope_signatures
                                else None
                            ),
                            proof_graph_neighborhood=[
                                obligation.obligation_id,
                                *obligation.dependency_ids,
                            ],
                            mechanism_signature=mechanism_for(route_id),
                        )

        attempt_by_id = {attempt.attempt_id: attempt for attempt in attempts}
        subject_route_ids: dict[str, str] = {}
        for attempt in attempts:
            route_id = strategy_routes.get(attempt.strategy_id, "")
            if not route_id:
                continue
            subject_route_ids[attempt.attempt_id] = route_id
            local_steps = {step.step_id: step for step in attempt.proof_steps}
            local_claims = {claim.claim_id: claim for claim in attempt.proposed_lemmas}
            subject_route_ids.update({step_id: route_id for step_id in local_steps})
            subject_route_ids.update({claim_id: route_id for claim_id in local_claims})

            def add_dependency(
                dependency: DependencyRef,
                *,
                source_id: str,
                weight: float,
                visited: set[tuple[DependencyKind, str]] | None = None,
            ) -> None:
                visited = visited if visited is not None else set()
                dependency_key = (dependency.kind, dependency.target_id)
                if dependency_key in visited:
                    return
                visited.add(dependency_key)
                statement = ""
                verified = False
                neighborhood: list[str] = [dependency.target_id]
                nested_dependencies: list[DependencyRef] = []

                def append_typed_refs(values: Sequence[Any]) -> None:
                    for raw_ref in values:
                        try:
                            nested_dependencies.append(
                                raw_ref
                                if isinstance(raw_ref, DependencyRef)
                                else DependencyRef.model_validate(raw_ref)
                            )
                        except (TypeError, ValueError):
                            continue

                def append_legacy_refs(values: Sequence[str]) -> None:
                    existing_targets = {item.target_id for item in nested_dependencies}
                    for raw_id in values:
                        nested_id = str(raw_id).strip()
                        if not nested_id or nested_id in existing_targets:
                            continue
                        if nested_id in obligation_by_id:
                            kind = DependencyKind.OBLIGATION
                        elif nested_id in message_by_id:
                            kind = DependencyKind.MESSAGE
                        elif nested_id in local_steps:
                            kind = DependencyKind.LOCAL_STEP
                        elif nested_id in local_claims:
                            kind = DependencyKind.LOCAL_CLAIM
                        else:
                            continue
                        nested_dependencies.append(
                            DependencyRef(
                                kind=kind,
                                target_id=nested_id,
                                source_attempt_id=attempt.attempt_id,
                                source_route_id=route_id,
                            )
                        )
                        existing_targets.add(nested_id)

                if dependency.kind == DependencyKind.OBLIGATION:
                    target = obligation_by_id.get(dependency.target_id)
                    if target is not None:
                        statement = target.statement
                        verified = target.status == "closed"
                        neighborhood.extend(target.dependency_ids)
                        append_typed_refs(target.dependency_refs)
                        append_legacy_refs(target.dependency_ids)
                elif dependency.kind in {
                    DependencyKind.MESSAGE,
                    DependencyKind.GLOBAL_FACT,
                }:
                    target_message = message_by_id.get(dependency.target_id)
                    if target_message is not None:
                        statement = target_message.statement
                        verified = bool(
                            target_message.memory_tier == MemoryTier.FACT
                            and target_message.verification_status
                            == ClaimStatus.VERIFIED
                        )
                        neighborhood.extend(target_message.dependencies)
                        append_legacy_refs(target_message.dependencies)
                elif dependency.kind == DependencyKind.LOCAL_STEP:
                    target_step = local_steps.get(dependency.target_id)
                    if target_step is not None:
                        statement = target_step.statement
                        append_typed_refs(target_step.dependency_refs)
                        append_legacy_refs(target_step.dependencies)
                elif dependency.kind == DependencyKind.LOCAL_CLAIM:
                    target_claim = local_claims.get(dependency.target_id)
                    if target_claim is not None:
                        statement = target_claim.statement
                        verified = target_claim.status == ClaimStatus.VERIFIED
                        neighborhood.extend(target_claim.dependencies)
                        append_typed_refs(target_claim.dependency_refs)
                        append_legacy_refs(target_claim.dependencies)
                if statement:
                    scoped_local_id = (
                        f"{dependency.kind.value}:{attempt.attempt_id}:"
                        f"{dependency.target_id}"
                        if dependency.kind
                        in {DependencyKind.LOCAL_STEP, DependencyKind.LOCAL_CLAIM}
                        else f"{dependency.kind.value}:{dependency.target_id}"
                    )
                    add(
                        statement,
                        route_id=route_id,
                        source_subject_id=source_id,
                        weight=weight,
                        verified=verified,
                        require_truth_apt=True,
                        source_kind="typed_dependency",
                        dependency_refs=[dependency],
                        typed_dependency_ids=[scoped_local_id],
                        scope_signature_id=(
                            dependency.target_id
                            if dependency.target_id in scope_signatures
                            else None
                        ),
                        proof_graph_neighborhood=neighborhood,
                        mechanism_signature=mechanism_for(route_id),
                    )
                for nested_dependency in nested_dependencies:
                    add_dependency(
                        nested_dependency,
                        source_id=source_id,
                        weight=max(0.0, min(1.0, weight * 0.95)),
                        visited=visited,
                    )

            for step in attempt.proof_steps:
                weight = 1.0 if step.is_key_step else 0.7
                refs: list[DependencyRef] = []
                for raw_ref in step.dependency_refs:
                    try:
                        refs.append(
                            raw_ref
                            if isinstance(raw_ref, DependencyRef)
                            else DependencyRef.model_validate(raw_ref)
                        )
                    except (TypeError, ValueError):
                        continue
                known_targets = {ref.target_id for ref in refs}
                for legacy_id in step.dependencies:
                    value = str(legacy_id).strip()
                    if not value or value in known_targets:
                        continue
                    if value in obligation_by_id:
                        refs.append(
                            DependencyRef(
                                kind=DependencyKind.OBLIGATION,
                                target_id=value,
                                source_attempt_id=attempt.attempt_id,
                                source_route_id=route_id,
                            )
                        )
                    elif value in message_by_id:
                        refs.append(
                            DependencyRef(
                                kind=DependencyKind.MESSAGE,
                                target_id=value,
                                source_attempt_id=attempt.attempt_id,
                                source_route_id=route_id,
                            )
                        )
                    elif value in local_steps:
                        refs.append(
                            DependencyRef(
                                kind=DependencyKind.LOCAL_STEP,
                                target_id=value,
                                source_attempt_id=attempt.attempt_id,
                                source_route_id=route_id,
                            )
                        )
                    elif value in local_claims:
                        refs.append(
                            DependencyRef(
                                kind=DependencyKind.LOCAL_CLAIM,
                                target_id=value,
                                source_attempt_id=attempt.attempt_id,
                                source_route_id=route_id,
                            )
                        )
                    else:
                        add(
                            value,
                            route_id=route_id,
                            source_subject_id=step.step_id,
                            weight=weight,
                            require_truth_apt=True,
                            source_kind="typed_dependency",
                            mechanism_signature=mechanism_for(route_id),
                        )
                for dependency in refs:
                    add_dependency(
                        dependency,
                        source_id=step.step_id,
                        weight=weight,
                    )
                if step.is_key_step:
                    hidden = self._assumption_from_justification(step.justification)
                    if hidden:
                        add(
                            hidden,
                            route_id=route_id,
                            source_subject_id=step.step_id,
                            weight=0.9,
                            require_truth_apt=True,
                            source_kind="key_step_justification",
                            mechanism_signature=mechanism_for(route_id),
                        )
            for gap in attempt.unresolved_gaps:
                add(
                    gap,
                    route_id=route_id,
                    source_subject_id=attempt.attempt_id,
                    weight=1.0,
                    require_truth_apt=True,
                    source_kind="unresolved_gap",
                    mechanism_signature=mechanism_for(route_id),
                )

        if self.config.include_unverified_dependencies:
            for report in reports:
                if report.verdict == VerificationVerdict.PASS:
                    continue
                route_id = subject_route_ids.get(report.target_id, "")
                if not route_id:
                    attempt = attempt_by_id.get(report.target_id)
                    if attempt is not None:
                        route_id = strategy_routes.get(attempt.strategy_id, "")
                if not route_id:
                    continue
                for issue in report.issues:
                    if issue.severity not in {Severity.ERROR, Severity.CRITICAL}:
                        continue
                    premise = issue.premise_summary.strip()
                    if not premise:
                        premise = self._assumption_from_justification(issue.description)
                    if not premise:
                        continue
                    add(
                        premise,
                        route_id=route_id,
                        source_subject_id=issue.issue_id,
                        weight=1.0,
                        require_truth_apt=True,
                        source_kind="verifier_critical_issue",
                        proof_graph_neighborhood=[
                            value for value in (issue.step_id, issue.claim_id) if value
                        ],
                        mechanism_signature=mechanism_for(route_id),
                    )

        for atom in prior_atoms:
            add(
                atom.statement,
                route_id=atom.route_id,
                source_subject_id=atom.source_id,
                weight=atom.load_bearing_score,
                verified=atom.verification_status == ClaimStatus.VERIFIED,
                source_kind=atom.source_kind,
                dependency_refs=atom.dependency_refs,
                typed_dependency_ids=atom.typed_dependency_ids,
                scope_signature_id=atom.scope_signature_id,
                proof_graph_neighborhood=atom.proof_graph_neighborhood,
                mechanism_signature=atom.mechanism_signature,
                atom_id=atom.atom_id,
            )

        authoritative_statements = {
            str(record["normalized_statement"])
            for record in raw.values()
            if record["authoritative_verified"]
        }
        for record in raw.values():
            if str(record["normalized_statement"]) in authoritative_statements:
                record["verified"] = True

        denominator = max(1.0, float(len(active_route_ids)))
        assumptions: dict[str, CriticalAssumption] = {}
        usage: dict[str, dict[str, float]] = {
            route_id: {} for route_id in sorted(active_route_ids)
        }
        for identity, record in sorted(raw.items()):
            normalized = str(record["normalized_statement"])
            assumption_id = f"assumption_{stable_hash(identity)[:12]}"
            necessity_by_route = {
                route_id: float(weight)
                for route_id, weight in sorted(record["routes"].items())
            }
            risk = min(
                1.0,
                sum(
                    weight
                    for route_id, weight in necessity_by_route.items()
                    if route_id in active_route_ids
                )
                / denominator,
            )
            assumption = CriticalAssumption(
                assumption_id=assumption_id,
                normalized_statement=normalized,
                source_subject_ids=sorted(record["sources"]),
                route_ids=sorted(necessity_by_route),
                verification_status=(
                    ClaimStatus.VERIFIED if record["verified"] else ClaimStatus.PROPOSED
                ),
                necessity_by_route=necessity_by_route,
                common_mode_risk=risk,
                domain=AssumptionDomain.MATHEMATICAL,
                semantic_tags=sorted(record["semantic_tags"]),
                dependency_atom_ids=sorted(record["atom_ids"]),
                typed_dependency_ids=sorted(record["typed_dependency_ids"]),
                scope_signature_ids=sorted(record["scope_signature_ids"]),
                proof_graph_neighborhood=sorted(record["proof_graph_neighborhood"]),
                load_bearing_score=float(record["load_bearing_score"]),
            )
            assumptions[assumption_id] = assumption
            for route_id, weight in necessity_by_route.items():
                usage.setdefault(route_id, {})[assumption_id] = weight
        self.assumptions = assumptions
        self.usage = usage
        self.atoms = atoms
        self.families = self._build_families(
            assumptions,
            active_route_ids=active_route_ids,
        )
        for family in self.families.values():
            for assumption_id in family.member_assumption_ids:
                assumption = assumptions[assumption_id]
                assumption.family_id = family.family_id
                assumption.common_mode_risk = family.common_mode_risk
        return dict(assumptions)

    def risks(
        self,
        assumptions: Sequence[CriticalAssumption] | None = None,
    ) -> list[CriticalAssumption]:
        values = assumptions or list(self.assumptions.values())
        return sorted(
            (
                item
                for item in values
                if item.verification_status != ClaimStatus.VERIFIED
                and len(item.route_ids) >= self.config.min_routes
                and item.common_mode_risk >= self.config.risk_threshold
            ),
            key=lambda item: (
                -item.common_mode_risk,
                -len(item.route_ids),
                item.assumption_id,
            ),
        )

    def challenger_task(self, assumption: CriticalAssumption) -> dict[str, Any]:
        task_id = f"challenger_{stable_hash(assumption.assumption_id)[:12]}"
        assumption.challenger_task_id = task_id
        return {
            "task_id": task_id,
            "assumption_id": assumption.assumption_id,
            "target_statement": assumption.normalized_statement,
            "route_ids": list(assumption.route_ids),
            "roles": [
                "counterexample_hunter",
                "meta_strategist",
                "reverse_goal_analyzer",
            ],
            "required_actions": [
                "seek an exact counterexample",
                "find a route that does not depend on the assumption",
                "find a weaker sufficient condition",
                "determine whether the assumption is actually necessary",
            ],
            "premise_eligible": False,
        }

    def semantic_family_key(
        self,
        statement: str,
        *,
        scope_signature_id: str | None,
        mechanism_chain: Sequence[str] = (),
        proof_graph_neighborhood: Sequence[str] = (),
    ) -> str:
        identity = {
            "semantic_tags": sorted(
                self._semantic_tags(self._normalize_assumption(statement))
            ),
            "scope_signature_id": scope_signature_id,
            "mechanism_chain": [
                normalize_text(item).casefold() for item in mechanism_chain
            ],
            "proof_graph_neighborhood": sorted(set(proof_graph_neighborhood)),
        }
        return f"assumption_family_key_{stable_hash(identity)[:20]}"

    def risk_families(self) -> list[AssumptionFamily]:
        return sorted(
            (
                family
                for family in self.families.values()
                if (
                    len(family.route_ids) >= self.config.min_routes
                    or (family.is_dependency_cutset and len(family.route_ids) >= 2)
                )
                and family.common_mode_risk >= self.config.risk_threshold
                and any(
                    self.assumptions[assumption_id].verification_status
                    != ClaimStatus.VERIFIED
                    for assumption_id in family.member_assumption_ids
                )
            ),
            key=lambda item: (
                -item.common_mode_risk,
                -len(item.route_ids),
                item.family_id,
            ),
        )

    @staticmethod
    def challenger_for_family(
        family: AssumptionFamily,
    ) -> AssumptionChallengerTask:
        return AssumptionChallengerTask(
            task_id=f"challenger_{stable_hash(family.family_id)[:12]}",
            family_id=family.family_id,
            assumption_ids=list(family.member_assumption_ids),
            target_statement=family.canonical_statement,
            route_ids=list(family.route_ids),
            required_actions=[
                "seek an exact counterexample",
                "show that the assumption is not necessary",
                "find a weaker sufficient condition",
                "construct a route independent of the assumption family",
            ],
            premise_eligible=False,
        )

    def _build_families(
        self,
        assumptions: dict[str, CriticalAssumption],
        *,
        active_route_ids: set[str],
    ) -> dict[str, AssumptionFamily]:
        groups: list[list[CriticalAssumption]] = []
        for assumption in sorted(
            assumptions.values(), key=lambda item: item.assumption_id
        ):
            group = next(
                (
                    candidate
                    for candidate in groups
                    if any(
                        self._same_assumption_family(assumption, member)
                        for member in candidate
                    )
                ),
                None,
            )
            if group is None:
                groups.append([assumption])
            else:
                group.append(assumption)

        denominator = max(1.0, float(len(active_route_ids)))
        families: dict[str, AssumptionFamily] = {}
        for group in groups:
            member_ids = sorted(item.assumption_id for item in group)
            route_weights: dict[str, float] = {}
            route_dependency_closures: dict[str, set[str]] = {}
            for assumption in group:
                for route_id, weight in assumption.necessity_by_route.items():
                    route_weights[route_id] = max(
                        route_weights.get(route_id, 0.0),
                        float(weight),
                    )
                    route_dependency_closures.setdefault(route_id, set()).update(
                        assumption.typed_dependency_ids or [assumption.assumption_id]
                    )
            semantic_tags = sorted(
                {tag for item in group for tag in item.semantic_tags}
            )
            typed_dependency_ids = sorted(
                {
                    dependency_id
                    for item in group
                    for dependency_id in item.typed_dependency_ids
                }
            )
            dependency_atom_ids = sorted(
                {atom_id for item in group for atom_id in item.dependency_atom_ids}
            )
            scope_signature_ids = sorted(
                {scope_id for item in group for scope_id in item.scope_signature_ids}
            )
            neighborhood = sorted(
                {
                    subject_id
                    for item in group
                    for subject_id in item.proof_graph_neighborhood
                }
            )
            family_id = (
                "assumption_family_"
                + stable_hash(
                    {
                        "members": member_ids,
                        "semantic_tags": semantic_tags,
                        "typed_dependency_ids": typed_dependency_ids,
                        "scope_signature_ids": scope_signature_ids,
                    }
                )[:16]
            )
            canonical = min(
                group,
                key=lambda item: (
                    len(item.normalized_statement),
                    item.normalized_statement,
                ),
            )
            active_family_routes = sorted(set(route_weights) & active_route_ids)
            load_bearing_score = max(
                (item.load_bearing_score for item in group),
                default=0.0,
            )
            coverage_risk = min(
                1.0,
                sum(route_weights[route_id] for route_id in active_family_routes)
                / denominator,
            )
            family = AssumptionFamily(
                family_id=family_id,
                canonical_statement=canonical.normalized_statement,
                member_assumption_ids=member_ids,
                route_ids=active_family_routes,
                semantic_tags=semantic_tags,
                common_mode_risk=min(
                    1.0,
                    coverage_risk * (0.5 + 0.5 * load_bearing_score),
                ),
                normalization_confidence=(
                    1.0
                    if typed_dependency_ids
                    and len(
                        {
                            dependency_id
                            for item in group
                            for dependency_id in item.typed_dependency_ids
                        }
                    )
                    == 1
                    else 0.95
                    if len(group) == 1
                    else 0.85
                ),
                dependency_atom_ids=dependency_atom_ids,
                typed_dependency_ids=typed_dependency_ids,
                scope_signature_ids=scope_signature_ids,
                proof_graph_neighborhood=neighborhood,
                route_dependency_closures={
                    route_id: sorted(route_dependency_closures[route_id])
                    for route_id in active_family_routes
                },
                load_bearing_score=load_bearing_score,
                is_dependency_cutset=bool(active_route_ids)
                and set(active_family_routes) == active_route_ids,
            )
            families[family.family_id] = family
        return families

    def matching_family_ids(
        self,
        strategy: StrategyCard,
        families: Sequence[AssumptionFamily] | None = None,
    ) -> list[str]:
        """Return unresolved dependency families used by a candidate strategy."""

        candidates = [
            *strategy.prerequisites,
            *(claim.statement for claim in strategy.critical_claims),
        ]
        result: list[str] = []
        for family in families or list(self.families.values()):
            members = [
                self.assumptions[assumption_id]
                for assumption_id in family.member_assumption_ids
                if assumption_id in self.assumptions
            ]
            if any(
                self._statement_matches_assumption(statement, assumption)
                for statement in candidates
                for assumption in members
            ):
                result.append(family.family_id)
        return sorted(set(result))

    def strategy_is_independent(
        self,
        strategy: StrategyCard,
        family: AssumptionFamily,
    ) -> bool:
        return family.family_id not in self.matching_family_ids(strategy, [family])

    @classmethod
    def statements_semantically_match(
        cls,
        left: str,
        right: str,
        *,
        threshold: float = 0.78,
    ) -> bool:
        left_normalized = cls._normalize_assumption(left)
        right_normalized = cls._normalize_assumption(right)
        return (
            left_normalized == right_normalized
            or conservatively_matches_across_languages(
                left_normalized,
                right_normalized,
            )
            or (
                cls._tag_similarity(
                    cls._semantic_tags(left_normalized),
                    cls._semantic_tags(right_normalized),
                )
                >= threshold
            )
        )

    def _statement_matches_assumption(
        self,
        statement: str,
        assumption: CriticalAssumption,
    ) -> bool:
        return self.statements_semantically_match(
            statement,
            assumption.normalized_statement,
        )

    @classmethod
    def _same_assumption_family(
        cls,
        left: CriticalAssumption,
        right: CriticalAssumption,
    ) -> bool:
        left_dependencies = set(left.typed_dependency_ids)
        right_dependencies = set(right.typed_dependency_ids)
        if left_dependencies & right_dependencies:
            return True
        if left_dependencies and right_dependencies:
            return False
        left_scopes = set(left.scope_signature_ids)
        right_scopes = set(right.scope_signature_ids)
        if left_scopes and right_scopes and not left_scopes.intersection(right_scopes):
            return False
        if left.normalized_statement == right.normalized_statement:
            return True
        if conservatively_matches_across_languages(
            left.normalized_statement,
            right.normalized_statement,
        ):
            return True
        neighborhood_overlap = bool(
            set(left.proof_graph_neighborhood) & set(right.proof_graph_neighborhood)
        )
        similarity = cls._tag_similarity(
            set(left.semantic_tags),
            set(right.semantic_tags),
        )
        if neighborhood_overlap and similarity >= 0.45:
            return True
        return similarity >= 0.78

    @staticmethod
    def _tag_similarity(left: set[str], right: set[str]) -> float:
        union = left | right
        return len(left & right) / len(union) if union else 0.0

    @staticmethod
    def _semantic_tags(statement: str) -> set[str]:
        aliases = {
            "admits": "admission",
            "each": "universal",
            "every": "universal",
            "has": "admission",
            "preserve": "preservation",
            "preserves": "preservation",
            "preserved": "preservation",
            "invariant": "preservation",
            "invariance": "preservation",
            "one": "unique",
            "transform": "transformation",
            "transforms": "transformation",
        }
        stop = {
            "a",
            "an",
            "and",
            "are",
            "is",
            "of",
            "remains",
            "the",
            "under",
        }
        text = unicodedata.normalize("NFKC", statement).casefold()
        tags = {
            aliases.get(token, token)
            for token in re.findall(r"[a-z][a-z0-9_]*", text)
            if token not in stop
        }
        for run in CriticalAssumptionMatrix._CJK_RUN.findall(text):
            compact = run.translate(CriticalAssumptionMatrix._CJK_PARTICLES)
            if not compact:
                continue
            if len(compact) == 1:
                tags.add(f"cjk:{compact}")
                continue
            tags.update(
                f"cjk2:{compact[index : index + 2]}"
                for index in range(len(compact) - 1)
            )
            if len(compact) >= 3:
                tags.update(
                    f"cjk3:{compact[index : index + 3]}"
                    for index in range(len(compact) - 2)
                )
        tags.update(
            f"math:{symbol}" for symbol in re.findall(r"[∀∃=≤≥≠∈∉⊂⊆→↔∣+\-*/^]", text)
        )
        return tags

    @classmethod
    def _assumption_from_justification(cls, text: str) -> str:
        match = cls._JUSTIFICATION_ASSUMPTION.search(normalize_text(text))
        return match.group("statement").strip() if match is not None else ""

    @classmethod
    def _normalize_assumption(cls, statement: str) -> str:
        value = unicodedata.normalize(
            "NFKC",
            canonical_obligation_statement(statement),
        )
        value = normalize_text(value).casefold().strip(" .;:：。；")
        previous = ""
        while value != previous:
            previous = value
            value = cls._STRIP_PREFIX.sub("", value).strip(" .;:")
            value = cls._STRIP_PREFIX_CJK.sub("", value).strip(" .;:：。；")
        return value
