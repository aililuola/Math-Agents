from __future__ import annotations

import re
import unicodedata
from collections.abc import Iterable
from typing import Any

from .schemas import stable_hash

_DIRECTIVE_RE = re.compile(
    r"^\[(?P<kind>[a-z_]+)\]"
    r"\[STATUS:[^\]]+\]"
    r"\[SOURCE:[^\]]+\]"
    r"\[PREMISE_ELIGIBLE:(?:true|false)\]\s*",
    re.IGNORECASE,
)
_OBLIGATION_PREFIX_RE = re.compile(
    r"^(?:unresolved\s+gap|open\s+proof\s+obligation|proof\s+obligation)"
    r"\s*[:：]\s*",
    re.IGNORECASE,
)
_NON_MATHEMATICAL_DIRECTIVES = {
    "verification_feedback",
    "verification_issue",
    "required_action",
    "unresolved_conflict",
}


def normalize_text(value: str) -> str:
    """Normalize presentation noise without changing mathematical notation."""

    return " ".join(unicodedata.normalize("NFC", str(value)).split())


def unwrap_feedback_directives(value: str) -> tuple[str, list[str]]:
    """Remove all nested transport wrappers and return their outer-to-inner kinds."""

    text = normalize_text(value)
    kinds: list[str] = []
    while match := _DIRECTIVE_RE.match(text):
        kinds.append(match.group("kind").casefold())
        text = text[match.end() :].strip()
    return text, kinds


def canonical_obligation_statement(value: str) -> str:
    """Return the mathematical obligation, excluding run/attempt provenance."""

    text = normalize_text(value)
    while True:
        unwrapped, _ = unwrap_feedback_directives(text)
        stripped = _OBLIGATION_PREFIX_RE.sub("", unwrapped, count=1).strip()
        if stripped == text:
            break
        text = stripped
    return normalize_text(text)


def obligation_identity_text(value: str) -> str:
    return canonical_obligation_statement(value).casefold()


def is_feedback_only_statement(value: str) -> bool:
    """True when an item is reviewer/control feedback, not a proof obligation."""

    text = normalize_text(value)
    kinds: list[str] = []
    while True:
        unwrapped, found = unwrap_feedback_directives(text)
        kinds.extend(found)
        stripped = _OBLIGATION_PREFIX_RE.sub("", unwrapped, count=1).strip()
        if stripped == text:
            break
        text = stripped
    return any(kind in _NON_MATHEMATICAL_DIRECTIVES for kind in kinds)


def canonical_strings(values: Iterable[str]) -> list[str]:
    return sorted(
        {
            obligation_identity_text(value)
            for value in values
            if canonical_obligation_statement(value)
        }
    )


def proof_step_payload(step: Any) -> dict[str, Any]:
    return {
        "statement": normalize_text(getattr(step, "statement", "")).casefold(),
        "justification": normalize_text(getattr(step, "justification", "")).casefold(),
        "calculations": sorted(
            normalize_text(item).casefold()
            for item in getattr(step, "calculations", [])
            if normalize_text(item)
        ),
        "citations": sorted(
            stable_hash(
                {
                    "title": normalize_text(getattr(item, "title", "")).casefold(),
                    "authors": sorted(
                        normalize_text(author).casefold()
                        for author in getattr(item, "authors", [])
                    ),
                    "statement": normalize_text(
                        getattr(item, "exact_statement", "") or ""
                    ).casefold(),
                }
            )
            for item in getattr(step, "citations", [])
        ),
        "is_key_step": bool(getattr(step, "is_key_step", False)),
    }


def claim_payload(claim: Any) -> dict[str, Any]:
    return {
        "statement": normalize_text(getattr(claim, "statement", "")).casefold(),
        "assumptions": canonical_strings(getattr(claim, "assumptions", [])),
        "conclusion": normalize_text(getattr(claim, "conclusion", "")).casefold(),
        "proof_steps": [
            proof_step_payload(step) for step in getattr(claim, "proof_steps", [])
        ],
        "scope_limitations": canonical_strings(getattr(claim, "scope_limitations", [])),
    }


def checkpoint_math_fingerprint(checkpoint: Any) -> str:
    """Hash only durable mathematical state, never checkpoint/path UUIDs."""

    return stable_hash(
        {
            "problem_hash": getattr(checkpoint, "problem_hash", ""),
            "verified_steps": [
                proof_step_payload(step)
                for step in getattr(checkpoint, "verified_steps", [])
            ],
            "active_assumptions": canonical_strings(
                getattr(checkpoint, "active_assumptions", [])
            ),
            "remaining_subgoals": canonical_strings(
                getattr(checkpoint, "remaining_subgoals", [])
            ),
            "current_goal": obligation_identity_text(
                getattr(checkpoint, "current_goal", "") or ""
            ),
            "known_risks": canonical_strings(getattr(checkpoint, "known_risks", [])),
            "final_answer": normalize_text(
                getattr(checkpoint, "final_answer", "") or ""
            ).casefold(),
            "proof_complete": bool(getattr(checkpoint, "proof_complete", False)),
        }
    )


def attempt_content_fingerprint(attempt: Any) -> str:
    """Hash an attempt's public mathematics while excluding telemetry and IDs."""

    status = getattr(attempt, "status", "")
    status_value = getattr(status, "value", status)
    return stable_hash(
        {
            "problem_hash": getattr(attempt, "problem_hash", ""),
            "status": str(status_value),
            "final_answer": normalize_text(
                getattr(attempt, "final_answer", "") or ""
            ).casefold(),
            "proof_steps": [
                proof_step_payload(step) for step in getattr(attempt, "proof_steps", [])
            ],
            "proposed_lemmas": sorted(
                (
                    claim_payload(claim)
                    for claim in getattr(attempt, "proposed_lemmas", [])
                ),
                key=stable_hash,
            ),
            "candidate_conjectures": sorted(
                (
                    {
                        "statement": normalize_text(
                            getattr(candidate, "statement", "")
                        ).casefold(),
                        "supporting_experiment_ids": sorted(
                            getattr(candidate, "supporting_experiment_ids", [])
                        ),
                        "scope_limitations": canonical_strings(
                            getattr(candidate, "scope_limitations", [])
                        ),
                        "proof_obligations": canonical_strings(
                            getattr(candidate, "proof_obligations", [])
                        ),
                    }
                    for candidate in getattr(attempt, "candidate_conjectures", [])
                ),
                key=stable_hash,
            ),
            "dead_ends": canonical_strings(getattr(attempt, "dead_ends", [])),
            "unresolved_gaps": canonical_strings(
                getattr(attempt, "unresolved_gaps", [])
            ),
            "falsification_checks": canonical_strings(
                getattr(attempt, "falsification_checks", [])
            ),
        }
    )


def progress_signature(
    *,
    attempts: Iterable[Any],
    facts: Iterable[Any] = (),
    obligations: Iterable[Any] = (),
    negatives: Iterable[Any] = (),
    final_proof: Any | None = None,
) -> str:
    """Certificate digest for externally checkable mathematical progress only."""

    checkpoint_math = {
        stable_hash(
            {
                "problem_hash": getattr(attempt, "problem_hash", ""),
                "steps": [
                    proof_step_payload(step)
                    for step in getattr(attempt, "proof_steps", [])
                ],
                "answer": normalize_text(
                    getattr(attempt, "final_answer", "") or ""
                ).casefold(),
            }
        )
        for attempt in attempts
        if getattr(attempt, "proof_steps", None)
        or getattr(attempt, "final_answer", None)
    }
    verified_facts = {
        str(getattr(item, "content_hash", "") or stable_hash(claim_payload(item)))
        for item in facts
    }
    resolved_obligations = {
        f"{getattr(item, 'status', '')}:{getattr(item, 'content_hash', '')}"
        for item in obligations
        if getattr(item, "status", "") == "closed"
        or (
            getattr(item, "status", "") == "refuted"
            and bool(getattr(item, "evidence_message_ids", []))
        )
    }
    counterexamples = {
        str(getattr(item, "content_hash", "") or stable_hash(claim_payload(item)))
        for item in negatives
        if str(getattr(getattr(item, "evidence_type", ""), "value", "")).casefold()
        == "counterexample"
    }
    final_payload = None
    if final_proof is not None:
        final_payload = {
            "problem_hash": getattr(final_proof, "problem_hash", ""),
            "answer": normalize_text(getattr(final_proof, "answer", "")).casefold(),
            "steps": [
                proof_step_payload(step)
                for step in getattr(final_proof, "proof_steps", [])
            ],
        }
    return stable_hash(
        {
            "checkpoint_math": sorted(checkpoint_math),
            "verified_facts": sorted(verified_facts),
            "resolved_obligations": sorted(resolved_obligations),
            "counterexamples": sorted(counterexamples),
            "final_proof": final_payload,
        }
    )
