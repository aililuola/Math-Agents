from __future__ import annotations

import json
from types import SimpleNamespace

from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.proof_control.semantic_view import build_problem_semantic_view
from mathproofmesh.prompts import PromptFactory
from mathproofmesh.schemas import (
    ProblemContract,
    ProblemSemanticView,
    ProblemSemanticViewCandidate,
)
from mathproofmesh.store import ArtifactStore


def _candidate(english_statement: str) -> ProblemSemanticViewCandidate:
    return ProblemSemanticViewCandidate(
        english_statement=english_statement,
        preserves_hypotheses=True,
        preserves_quantifiers=True,
        preserves_domains=True,
        preserves_conclusion=True,
        confidence=0.96,
        notes=["Formula tokens were copied without alteration."],
    )


def test_usable_english_view_is_sidecar_and_does_not_change_goal_hash() -> None:
    source = r"证明对每个 $n \in \mathbb{N}$，都有 a_n \ge 0。"
    problem = ProblemContract(
        exact_statement=source,
        normalized_statement=source,
    )
    original_hash = problem.integrity_hash

    view = build_problem_semantic_view(
        source,
        _candidate(r"Prove that for every $n \in \mathbb{N}$, one has a_n \ge 0."),
    )
    problem.semantic_view = view

    assert view.status == "usable"
    assert view.authoritative is False
    assert view.deterministic_audit_passed is True
    assert all(item.status != "fail" for item in view.audit_findings)
    assert view.missing_protected_fragments == []
    assert problem.exact_statement == source
    assert problem.goal_hash == original_hash
    assert problem.integrity_hash == original_hash
    assert problem.model_dump(mode="json")["semantic_view"]["english_statement"]


def test_translation_that_changes_formula_or_domain_is_rejected() -> None:
    source = r"证明对每个 $n \in \mathbb{N}$，都有 a_n \ge 0。"

    view = build_problem_semantic_view(
        source,
        _candidate(r"Prove that for every $m \in \mathbb{R}$, one has a_m \le 1."),
    )

    assert view.status == "rejected"
    assert view.missing_protected_fragments
    assert view.deterministic_audit_passed is False
    assert view.authoritative is False


def test_translation_cannot_reverse_the_requested_task() -> None:
    source = r"证明每个正整数 $n$ 都满足 $n \ge 1$。"

    view = build_problem_semantic_view(
        source,
        _candidate(r"Disprove that every positive integer $n$ satisfies $n \ge 1$."),
    )

    assert view.status == "rejected"
    assert any("task_intent" in note for note in view.notes)


def test_translation_cannot_reverse_quantifier_or_unmarked_domain() -> None:
    existential = r"证明存在正整数 $n$ 使得 $P(n)$ 成立。"
    changed_quantifier = build_problem_semantic_view(
        existential,
        _candidate(r"Prove that every positive integer $n$ satisfies $P(n)$."),
    )
    natural_domain = r"证明每个正整数 $n$ 都满足 $P(n)$。"
    changed_domain = build_problem_semantic_view(
        natural_domain,
        _candidate(r"Prove that every real number $n$ satisfies $P(n)$."),
    )

    assert changed_quantifier.status == "rejected"
    assert any("quantifier" in note for note in changed_quantifier.notes)
    assert changed_domain.status == "rejected"
    assert any("domain" in note for note in changed_domain.notes)


def test_translation_cannot_reverse_implication_order() -> None:
    source = r"若 $P$ 成立，则 $Q$ 成立。"

    view = build_problem_semantic_view(
        source,
        _candidate(r"If $Q$ holds, then $P$ holds."),
    )

    assert view.status == "rejected"
    assert any("logical_relation" in note for note in view.notes)


def test_legacy_usable_view_without_deterministic_audit_is_quarantined() -> None:
    legacy = ProblemSemanticView.model_validate(
        {
            "source_statement_hash": "legacy-source",
            "source_language": "zh",
            "english_statement": "Prove the translated assertion.",
            "candidate_confidence": 0.99,
            "protected_fragments": [],
            "missing_protected_fragments": [],
            "status": "usable",
            "notes": [],
        }
    )

    assert legacy.status == "rejected"
    assert legacy.deterministic_audit_passed is False
    assert any("legacy" in note for note in legacy.notes)


def test_triage_prompt_requests_a_non_authoritative_english_view() -> None:
    source = r"证明对每个 $n \in \mathbb{N}$，都有 a_n \ge 0。"
    problem = ProblemContract(
        exact_statement=source,
        normalized_statement=source,
    )

    bundle = PromptFactory().triage(problem)

    assert "semantic_view_candidate" in bundle.user
    assert "non-authoritative" in bundle.user
    assert "preserve every formula" in bundle.user
    assert "task intent, polarity, implication direction" in bundle.user


def test_orchestrator_attaches_only_an_audited_view(tmp_path) -> None:
    source = r"证明对每个 $n \in \mathbb{N}$，都有 a_n \ge 0。"
    problem = ProblemContract(
        exact_statement=source,
        normalized_statement=source,
    )
    original_hash = problem.goal_hash
    store = ArtifactStore(tmp_path / "runs", "semantic-view")

    ProofMeshOrchestrator._attach_problem_semantic_view(
        problem,
        SimpleNamespace(
            semantic_view_candidate=_candidate(
                r"Prove that for every $n \in \mathbb{N}$, one has a_n \ge 0."
            )
        ),
        store,
    )

    assert problem.semantic_view is not None
    assert problem.semantic_view.status == "usable"
    assert problem.goal_hash == original_hash
    persisted = json.loads(
        (store.root / "structured" / "problem_semantic_view.json").read_text(
            encoding="utf-8"
        )
    )
    assert persisted["authoritative"] is False
    assert (
        persisted["source_statement_hash"]
        == problem.semantic_view.source_statement_hash
    )
