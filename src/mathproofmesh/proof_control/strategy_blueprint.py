from __future__ import annotations

import re
from collections import deque
from collections.abc import Mapping, Sequence

from ..proof_identity import normalize_text, obligation_identity_text
from ..schemas import NoveltySignature, ProofObligation, StrategyCard, stable_hash
from .semantic_quality import ObligationSemanticGate
from .models import (
    BlueprintEdge,
    BlueprintNode,
    BlueprintNodeKind,
    BlueprintSemanticAssessment,
    OriginalStrategyArchiveEntry,
    RevisedStrategyResult,
    RewriteSemanticAssessment,
    RewriteSemanticVerdict,
    StrategyBlueprint,
    StrategyBlueprintCompilation,
    StrategyCandidateAssessment,
    StrategyLineageRecord,
    StrategyRevisionReason,
)


_PLACEHOLDER_PATTERNS = (
    re.compile(r"^\s*(?:find|choose|construct)\s+(?:a|an|some)\s+suitable\b", re.I),
    re.compile(r"^\s*prove\s+(?:the|this)\s+(?:theorem|claim|result)\b", re.I),
    re.compile(r"^\s*(?:complete|finish)\s+(?:the|this)\s+argument\b", re.I),
    re.compile(r"^\s*analy[sz]e\s+carefully\b", re.I),
    re.compile(r"^\s*avoid\s+circular\s+dependenc", re.I),
)
_OBJECT_TOKEN = re.compile(r"[A-Za-z][A-Za-z0-9_-]{2,}|[\u4e00-\u9fff]{2,}")
_STOPWORDS = {
    "about",
    "after",
    "argument",
    "before",
    "carefully",
    "claim",
    "complete",
    "conclusion",
    "derive",
    "every",
    "find",
    "from",
    "holds",
    "lemma",
    "prove",
    "result",
    "route",
    "show",
    "some",
    "suitable",
    "target",
    "that",
    "theorem",
    "then",
    "this",
    "using",
    "with",
}


def is_placeholder_statement(statement: str) -> bool:
    normalized = normalize_text(statement)
    if not normalized:
        return True
    return any(pattern.search(normalized) for pattern in _PLACEHOLDER_PATTERNS)


def _domain_objects(*values: str) -> list[str]:
    tokens: list[str] = []
    for value in values:
        for token in _OBJECT_TOKEN.findall(value):
            normalized = token.casefold()
            if normalized in _STOPWORDS or normalized.isdigit():
                continue
            tokens.append(normalized)
    return list(dict.fromkeys(tokens))


class StrategyBlueprintCompiler:
    """Compile a StrategyCard into a deterministic nontrivial proof DAG."""

    def compile(
        self,
        strategy: StrategyCard,
        *,
        problem_hash: str,
        main_goal: ProofObligation,
        open_obligations: Sequence[ProofObligation] = (),
        admitted_facts: Sequence[object] = (),
        negative_memory: Sequence[object] = (),
    ) -> StrategyBlueprintCompilation:
        del admitted_facts, negative_memory
        existing_by_statement = {
            obligation_identity_text(item.normalized_statement): item.obligation_id
            for item in open_obligations
            if item.status in {"open", "tentative", "blocked"}
        }
        main_identity = obligation_identity_text(main_goal.normalized_statement)
        candidates: list[tuple[str, str]] = []
        candidates.extend(("expected_lemma", item) for item in strategy.expected_lemmas)
        candidates.extend(
            ("critical_claim", item.statement) for item in strategy.critical_claims
        )
        # The bottleneck is planning metadata ("the difficulty is proving X").
        # It only becomes a mathematical node when it is itself a truth-apt
        # proposition; otherwise it stays on the StrategyCard as metadata.
        bottleneck_quality = ObligationSemanticGate().assess_statement(
            strategy.bottleneck,
            source_kind="strategy_blueprint",
        )
        if bottleneck_quality.accepted:
            candidates.append(("bottleneck", strategy.bottleneck))

        unique: list[tuple[str, str, str]] = []
        seen: set[str] = set()
        for source_field, statement in candidates:
            canonical = obligation_identity_text(statement)
            if not canonical or canonical == main_identity or canonical in seen:
                continue
            seen.add(canonical)
            unique.append((source_field, statement.strip(), canonical))

        nodes: list[BlueprintNode] = []
        for index, (source_field, statement, canonical) in enumerate(unique):
            existing_id = existing_by_statement.get(canonical)
            node_id = existing_id or (
                "blueprint_node_"
                + stable_hash(
                    {
                        "problem_hash": problem_hash,
                        "strategy_id": strategy.strategy_id,
                        "statement": canonical,
                    }
                )[:20]
            )
            placeholder = is_placeholder_statement(statement)
            quality = 0.1 if placeholder else 0.9
            first_step = None
            if index == 0:
                first_step = (
                    strategy.key_original_step.strip()
                    if strategy.key_original_step and strategy.key_original_step.strip()
                    else f"Fix the declared objects and establish: {statement}"
                )
            nodes.append(
                BlueprintNode(
                    node_id=node_id,
                    strategy_id=strategy.strategy_id,
                    kind=(
                        BlueprintNodeKind.CONSTRUCTION
                        if "construct" in statement.casefold()
                        or "decomposition" in statement.casefold()
                        else BlueprintNodeKind.LEMMA
                    ),
                    statement=statement,
                    normalized_statement=normalize_text(statement),
                    assumptions=list(strategy.prerequisites),
                    source_field=source_field,
                    executable_first_step=first_step,
                    semantic_quality_score=quality,
                )
            )

        main_node = BlueprintNode(
            node_id=main_goal.obligation_id,
            strategy_id=strategy.strategy_id,
            kind=BlueprintNodeKind.TARGET,
            statement=main_goal.statement,
            normalized_statement=main_goal.normalized_statement,
            assumptions=list(main_goal.assumptions),
            quantifiers=list(main_goal.quantifiers),
            source_field="main_goal",
            semantic_quality_score=1.0,
        )
        nodes.append(main_node)

        edges: list[BlueprintEdge] = []
        path_nodes = [item for item in nodes if item.node_id != main_node.node_id]
        path_ids = [item.node_id for item in path_nodes] + [main_node.node_id]
        for source_id, target_id in zip(path_ids, path_ids[1:]):
            source = next(item for item in nodes if item.node_id == source_id)
            target = next(item for item in nodes if item.node_id == target_id)
            edges.append(
                BlueprintEdge(
                    edge_id=(
                        "blueprint_edge_"
                        + stable_hash(
                            {
                                "strategy_id": strategy.strategy_id,
                                "source": source_id,
                                "target": target_id,
                            }
                        )[:20]
                    ),
                    source_node_id=source_id,
                    target_node_id=target_id,
                    relation="implies",
                    origin="list_order_guess",
                    implication_outline=[
                        f"Establish {source.normalized_statement}.",
                        (
                            "Use that established relation to derive "
                            f"{target.normalized_statement}."
                        ),
                    ],
                )
            )

        preserves = bool(
            path_nodes
            and (
                strategy.tags
                or _domain_objects(
                    strategy.title,
                    strategy.core_idea,
                    strategy.bottleneck,
                    *strategy.expected_lemmas,
                )
            )
        )
        review_reasons: list[str] = []
        if not path_nodes:
            review_reasons.append("blueprint has no non-main-goal node")
        if any(is_placeholder_statement(item.statement) for item in path_nodes):
            review_reasons.append("blueprint contains placeholder mathematical content")
        if not any(item.executable_first_step for item in path_nodes):
            review_reasons.append("blueprint has no executable first step")
        if not preserves:
            review_reasons.append(
                "strategy mechanism or domain objects were not preserved"
            )
        status = "compiled" if not review_reasons else "needs_review"
        blueprint = StrategyBlueprint(
            blueprint_id=(
                "strategy_blueprint_"
                + stable_hash(
                    {
                        "problem_hash": problem_hash,
                        "strategy_id": strategy.strategy_id,
                        "nodes": [item.node_id for item in nodes],
                    }
                )[:20]
            ),
            strategy_id=strategy.strategy_id,
            problem_hash=problem_hash,
            node_ids=[item.node_id for item in nodes],
            edge_ids=[item.edge_id for item in edges],
            main_goal_node_id=main_node.node_id,
            # Every non-main node is a direct-target candidate, in path
            # order. Admission iterates this list and binds the first
            # semantically admissible node, so one unparseable first lemma
            # no longer kills the whole strategy.
            direct_target_node_ids=(
                [item.node_id for item in path_nodes]
                if path_nodes
                else [main_node.node_id]
            ),
            root_entry_node_ids=([path_nodes[0].node_id] if path_nodes else []),
            open_gap_node_ids=[item.node_id for item in path_nodes],
            preserves_mechanism_signature=preserves,
            complete_path_to_main_goal=bool(path_nodes and edges),
            compilation_confidence=(0.9 if not review_reasons else 0.35),
            status=status,
        )
        return StrategyBlueprintCompilation(
            blueprint=blueprint,
            nodes=nodes,
            edges=edges,
            review_reasons=review_reasons,
        )


class BlueprintSemanticGate:
    def validate(
        self,
        blueprint: StrategyBlueprint,
        *,
        nodes: Sequence[BlueprintNode],
        edges: Sequence[BlueprintEdge],
        strategy: StrategyCard,
        main_goal: ProofObligation,
    ) -> BlueprintSemanticAssessment:
        del strategy
        node_map = {item.node_id: item for item in nodes}
        reasons: list[str] = []
        non_main = [
            item for item in nodes if item.node_id != blueprint.main_goal_node_id
        ]
        if not non_main:
            reasons.append("blueprint requires a non-main-goal node")
        if not blueprint.direct_target_node_ids or any(
            item == blueprint.main_goal_node_id
            for item in blueprint.direct_target_node_ids
        ):
            reasons.append("direct target must be a non-main-goal node")
        if blueprint.main_goal_node_id != main_goal.obligation_id:
            reasons.append("blueprint main goal does not match the frozen goal")
        if any(
            edge.source_node_id == edge.target_node_id
            or edge.source_node_id not in node_map
            or edge.target_node_id not in node_map
            for edge in edges
        ):
            reasons.append("blueprint contains an invalid or self edge")
        if any(not edge.implication_outline for edge in edges):
            reasons.append("every blueprint edge requires an implication outline")
        if any(is_placeholder_statement(item.statement) for item in non_main):
            reasons.append("blueprint contains placeholder content")
        if not any(item.executable_first_step for item in non_main):
            reasons.append("blueprint requires an executable first step")
        if not blueprint.preserves_mechanism_signature:
            reasons.append("blueprint lost the strategy mechanism")
        for direct_id in blueprint.direct_target_node_ids:
            if not self._path_exists(
                direct_id,
                blueprint.main_goal_node_id,
                edges,
            ):
                reasons.append("blueprint has no directed path to the main goal")
                break
        accepted = not reasons
        blueprint.status = "accepted" if accepted else "needs_review"
        return BlueprintSemanticAssessment(
            blueprint_id=blueprint.blueprint_id,
            accepted=accepted,
            reasons=reasons,
        )

    @staticmethod
    def _path_exists(
        source_id: str,
        target_id: str,
        edges: Sequence[BlueprintEdge],
    ) -> bool:
        outgoing: dict[str, list[str]] = {}
        for edge in edges:
            outgoing.setdefault(edge.source_node_id, []).append(edge.target_node_id)
        queue = deque([source_id])
        seen: set[str] = set()
        while queue:
            current = queue.popleft()
            if current == target_id:
                return True
            if current in seen:
                continue
            seen.add(current)
            queue.extend(outgoing.get(current, []))
        return False


class RewriteSemanticGate:
    def assess(
        self,
        *,
        rewrite_request_id: str,
        candidate_strategy: StrategyCard,
        candidate_blueprint: StrategyBlueprint,
        nodes: Sequence[BlueprintNode],
        edges: Sequence[BlueprintEdge],
        original_strategy: StrategyCard,
        main_goal: ProofObligation,
    ) -> RewriteSemanticAssessment:
        node_map = {item.node_id: item for item in nodes}
        direct_nodes = [
            node_map[item]
            for item in candidate_blueprint.direct_target_node_ids
            if item in node_map
        ]
        source_node = direct_nodes[0] if direct_nodes else None
        target_node = node_map.get(candidate_blueprint.main_goal_node_id)
        canonical_source = obligation_identity_text(
            source_node.normalized_statement if source_node is not None else ""
        )
        canonical_target = obligation_identity_text(
            target_node.normalized_statement
            if target_node is not None
            else main_goal.normalized_statement
        )
        self_edge = any(
            edge.source_node_id == edge.target_node_id
            or (
                edge.source_node_id in node_map
                and edge.target_node_id in node_map
                and obligation_identity_text(
                    node_map[edge.source_node_id].normalized_statement
                )
                == obligation_identity_text(
                    node_map[edge.target_node_id].normalized_statement
                )
            )
            for edge in edges
        )
        is_self = bool(
            self_edge
            or (
                canonical_source
                and canonical_target
                and canonical_source == canonical_target
            )
        )
        nontrivial = any(
            edge.source_node_id != edge.target_node_id
            and edge.source_node_id in node_map
            and edge.target_node_id in node_map
            and obligation_identity_text(
                node_map[edge.source_node_id].normalized_statement
            )
            != obligation_identity_text(
                node_map[edge.target_node_id].normalized_statement
            )
            for edge in edges
        )
        executable = any(item.executable_first_step for item in direct_nodes)
        original_objects = set(
            _domain_objects(
                original_strategy.title,
                original_strategy.core_idea,
                original_strategy.bottleneck,
                *original_strategy.expected_lemmas,
                *original_strategy.tags,
            )
        )
        candidate_objects = set(
            _domain_objects(
                candidate_strategy.title,
                candidate_strategy.core_idea,
                candidate_strategy.bottleneck,
                *candidate_strategy.expected_lemmas,
                *candidate_strategy.tags,
            )
        )
        mechanism_preserved = (
            bool(
                set(original_strategy.tags) & set(candidate_strategy.tags)
                or original_objects & candidate_objects
            )
            and candidate_blueprint.preserves_mechanism_signature
        )
        reasons: list[str] = []
        if not direct_nodes:
            verdict = RewriteSemanticVerdict.NO_TARGET
            reasons.append("rewrite has no direct target")
        elif is_self:
            verdict = RewriteSemanticVerdict.TAUTOLOGICAL
            reasons.append("rewrite is a self implication or main-goal copy")
        elif any(is_placeholder_statement(item.statement) for item in direct_nodes):
            verdict = RewriteSemanticVerdict.PLACEHOLDER
            reasons.append("rewrite direct target is placeholder text")
        elif not nontrivial:
            verdict = RewriteSemanticVerdict.DUPLICATE
            reasons.append("rewrite makes no nontrivial graph change")
        elif not executable:
            verdict = RewriteSemanticVerdict.NO_EXECUTABLE_STEP
            reasons.append("rewrite has no executable first step")
        elif not mechanism_preserved:
            verdict = RewriteSemanticVerdict.LOST_DOMAIN_MECHANISM
            reasons.append("rewrite lost the original domain mechanism")
        else:
            verdict = RewriteSemanticVerdict.VALID
            reasons.append(
                "rewrite has a nontrivial executable mechanism-preserving path"
            )
        return RewriteSemanticAssessment(
            rewrite_request_id=rewrite_request_id,
            candidate_strategy_id=candidate_strategy.strategy_id,
            candidate_bridge_node_ids=[
                item.node_id
                for item in direct_nodes
                if item.source_field == "generated_bridge"
                or item.kind
                in {BlueprintNodeKind.LEMMA, BlueprintNodeKind.CONSTRUCTION}
            ],
            verdict=verdict,
            canonical_source_hash=stable_hash(canonical_source),
            canonical_target_hash=stable_hash(canonical_target),
            is_self_implication=is_self,
            has_nontrivial_graph_change=nontrivial,
            has_executable_first_step=executable,
            domain_mechanism_preserved=mechanism_preserved,
            reasons=reasons,
        )

    def revise_from_claims(
        self,
        *,
        rewrite_request_id: str,
        original_strategy: StrategyCard,
        candidate_strategy: StrategyCard,
        main_goal: ProofObligation,
        retained_claim_statements: Sequence[str],
        added_claim_statements: Sequence[str],
    ) -> RevisedStrategyResult:
        statements = list(
            dict.fromkeys(
                [
                    *retained_claim_statements,
                    *added_claim_statements,
                    *candidate_strategy.expected_lemmas,
                ]
            )
        )
        revised = candidate_strategy.model_copy(
            update={
                "expected_lemmas": statements,
                "parent_strategy_ids": list(
                    dict.fromkeys(
                        [
                            *candidate_strategy.parent_strategy_ids,
                            original_strategy.strategy_id,
                        ]
                    )
                ),
            }
        )
        compilation = StrategyBlueprintCompiler().compile(
            revised,
            problem_hash=main_goal.problem_hash,
            main_goal=main_goal,
        )
        assessment = self.assess(
            rewrite_request_id=rewrite_request_id,
            candidate_strategy=revised,
            candidate_blueprint=compilation.blueprint,
            nodes=compilation.nodes,
            edges=compilation.edges,
            original_strategy=original_strategy,
            main_goal=main_goal,
        )
        retained_ids = [
            "claim_" + stable_hash(item)[:16] for item in retained_claim_statements
        ]
        added_ids = [
            "claim_" + stable_hash(item)[:16] for item in added_claim_statements
        ]
        original_claim_ids = {
            item.claim_id for item in original_strategy.critical_claims
        }
        lineage = StrategyLineageRecord(
            strategy_id=revised.strategy_id,
            parent_strategy_id=original_strategy.strategy_id,
            root_strategy_id=original_strategy.strategy_id,
            revision_number=1,
            revision_reason=StrategyRevisionReason.BRIDGE_INSERTION,
            preserved_mechanism_tags=sorted(
                set(original_strategy.tags) & set(revised.tags)
            ),
            preserved_domain_objects=sorted(
                set(
                    _domain_objects(
                        original_strategy.core_idea,
                        original_strategy.bottleneck,
                        *original_strategy.expected_lemmas,
                    )
                )
                & set(
                    _domain_objects(
                        revised.core_idea,
                        revised.bottleneck,
                        *revised.expected_lemmas,
                    )
                )
            ),
            removed_claim_ids=sorted(original_claim_ids - set(retained_ids)),
            added_claim_ids=added_ids,
            status=(
                "active"
                if assessment.verdict == RewriteSemanticVerdict.VALID
                else "needs_rewrite"
            ),
        )
        first_id = (
            compilation.blueprint.direct_target_node_ids[0]
            if compilation.blueprint.direct_target_node_ids
            else ""
        )
        return RevisedStrategyResult(
            revised_strategy=revised,
            revised_blueprint=compilation.blueprint,
            lineage=lineage,
            semantic_assessment=assessment,
            retained_claim_ids=retained_ids,
            removed_claim_ids=lineage.removed_claim_ids,
            added_claim_ids=added_ids,
            first_executable_obligation_id=first_id,
        )


class OriginalStrategyArchive:
    def __init__(
        self,
        entries: dict[str, OriginalStrategyArchiveEntry] | None = None,
        lineage: dict[str, StrategyLineageRecord] | None = None,
    ) -> None:
        self.entries = entries if entries is not None else {}
        self.lineage = lineage if lineage is not None else {}

    def archive(
        self,
        strategy: StrategyCard,
        *,
        first_seen_round: int,
        raw_artifact_ref: str,
    ) -> OriginalStrategyArchiveEntry:
        existing = self.entries.get(strategy.strategy_id)
        if existing is not None:
            return existing
        objects = _domain_objects(
            strategy.title,
            strategy.core_idea,
            strategy.bottleneck,
            *strategy.expected_lemmas,
            *strategy.prerequisites,
        )
        signature = NoveltySignature(
            mechanism_tags=list(dict.fromkeys([*strategy.tags, strategy.title])),
            core_objects=objects,
            key_transformations=(
                [strategy.key_original_step] if strategy.key_original_step else []
            ),
            proof_principles=list(strategy.expected_lemmas),
        )
        entry = OriginalStrategyArchiveEntry(
            strategy=strategy,
            mechanism_signature=signature,
            domain_objects=objects,
            critical_claims=[item.statement for item in strategy.critical_claims],
            expected_lemmas=list(strategy.expected_lemmas),
            first_seen_round=first_seen_round,
            raw_artifact_ref=raw_artifact_ref,
        )
        self.entries[strategy.strategy_id] = entry
        self.lineage[strategy.strategy_id] = StrategyLineageRecord(
            strategy_id=strategy.strategy_id,
            parent_strategy_id=None,
            root_strategy_id=strategy.strategy_id,
            revision_number=0,
            revision_reason=None,
            preserved_mechanism_tags=list(signature.mechanism_tags),
            preserved_domain_objects=list(objects),
            removed_claim_ids=[],
            added_claim_ids=[item.claim_id for item in strategy.critical_claims],
            status="original",
        )
        return entry

    def register_child(
        self,
        strategy: StrategyCard,
        *,
        parent_strategy_id: str,
        reason: StrategyRevisionReason,
        generic_fallback: bool = False,
    ) -> StrategyCandidateAssessment:
        parent = self.entries.get(parent_strategy_id)
        if parent is None:
            raise KeyError(f"unknown original strategy: {parent_strategy_id}")
        child_objects = _domain_objects(
            strategy.title,
            strategy.core_idea,
            strategy.bottleneck,
            *strategy.expected_lemmas,
        )
        preserved_objects = sorted(set(parent.domain_objects) & set(child_objects))
        preserved_tags = sorted(
            set(parent.mechanism_signature.mechanism_tags)
            & set([*strategy.tags, strategy.title])
        )
        placeholder = any(
            is_placeholder_statement(item)
            for item in [
                strategy.core_idea,
                strategy.bottleneck,
                *strategy.expected_lemmas,
            ]
        )
        reason_text = None
        selectable = True
        if generic_fallback and placeholder:
            selectable = False
            reason_text = "generic fallback contains placeholder content"
        elif not preserved_objects and not preserved_tags:
            selectable = False
            reason_text = "child strategy lost all original domain mechanisms"
        self.lineage[strategy.strategy_id] = StrategyLineageRecord(
            strategy_id=strategy.strategy_id,
            parent_strategy_id=parent_strategy_id,
            root_strategy_id=self.lineage[parent_strategy_id].root_strategy_id,
            revision_number=self.lineage[parent_strategy_id].revision_number + 1,
            revision_reason=reason,
            preserved_mechanism_tags=preserved_tags,
            preserved_domain_objects=preserved_objects,
            removed_claim_ids=[],
            added_claim_ids=[item.claim_id for item in strategy.critical_claims],
            status="active" if selectable else "rejected_with_evidence",
        )
        return StrategyCandidateAssessment(
            strategy_id=strategy.strategy_id,
            selectable=selectable,
            semantic_rejection_reason=reason_text,
        )

    def reject_child(
        self,
        strategy_id: str,
        *,
        evidence_ids: Sequence[str],
    ) -> StrategyLineageRecord:
        if not evidence_ids:
            raise ValueError("rejecting a child strategy requires evidence")
        record = self.lineage[strategy_id]
        if record.parent_strategy_id is None:
            raise ValueError("an original strategy cannot be rejected as a child")
        record.status = "rejected_with_evidence"
        return record

    @classmethod
    def from_state(
        cls,
        *,
        entries: Mapping[str, OriginalStrategyArchiveEntry],
        lineage: Mapping[str, StrategyLineageRecord],
    ) -> OriginalStrategyArchive:
        return cls(dict(entries), dict(lineage))
