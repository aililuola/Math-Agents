from __future__ import annotations

import json
import math
import re
from collections import Counter
from pathlib import Path
from typing import Any, Iterable


def _tokens(value: str) -> list[str]:
    value = value.casefold()
    latin = re.findall(r"[a-z0-9_]+", value)
    cjk = "".join(re.findall(r"[\u3400-\u9fff]", value))
    return latin + [cjk[i : i + 2] for i in range(max(0, len(cjk) - 1))]


class LocalAnalogyLibrary:
    """Verified-only local JSONL retrieval with a deterministic BM25 fallback."""

    def __init__(self, path: str | Path, *, enabled: bool = True) -> None:
        self.path = Path(path)
        self.enabled = enabled
        self.records: list[dict[str, Any]] = []
        self.negative_records: list[dict[str, Any]] = []
        self.diagnostics: list[str] = []
        self.reload()

    def reload(self) -> None:
        self.records = []
        self.negative_records = []
        self.diagnostics = []
        if not self.enabled:
            return
        if not self.path.exists():
            self.diagnostics.append(f"analogy library not found: {self.path}")
            return
        try:
            with self.path.open("r", encoding="utf-8") as handle:
                for line_number, line in enumerate(handle, 1):
                    if not line.strip():
                        continue
                    payload = json.loads(line)
                    if not isinstance(payload, dict):
                        continue
                    if payload.get("negative") is True:
                        payload.setdefault("record_id", f"negative_local_{line_number}")
                        self.negative_records.append(payload)
                        continue
                    if payload.get("verified") is not True:
                        continue
                    payload.setdefault("record_id", f"local_{line_number}")
                    self.records.append(payload)
        except (OSError, json.JSONDecodeError) as exc:
            self.records = []
            self.negative_records = []
            self.diagnostics.append(f"analogy library unavailable: {exc}")

    def add_verified_record(self, record: dict[str, Any]) -> bool:
        if record.get("verified") is not True:
            raise ValueError("only verified experiences may enter the analogy library")
        record_id = str(record.get("record_id", ""))
        if not record_id or any(
            str(item.get("record_id")) == record_id for item in self.records
        ):
            return False
        self.records.append(dict(record))
        return True

    def add_negative_record(self, record: dict[str, Any]) -> bool:
        if record.get("negative") is not True:
            raise ValueError("negative analogy records require negative=true")
        record_id = str(record.get("record_id", ""))
        if not record_id or any(
            str(item.get("record_id")) == record_id for item in self.negative_records
        ):
            return False
        self.negative_records.append(dict(record))
        return True

    def search(
        self,
        *,
        query_text: str,
        object_tags: Iterable[str] = (),
        operation_tags: Iterable[str] = (),
        mechanism_tags: Iterable[str] = (),
        graph_tags: Iterable[str] = (),
        obligation_kinds: Iterable[str] = (),
        mechanism_chain: Iterable[str] = (),
        graph_motif_tags: Iterable[str] = (),
        problem_hash: str | None = None,
        top_k: int = 6,
    ) -> list[dict[str, Any]]:
        if not self.records:
            return []
        query = list(
            _tokens(
                " ".join(
                    [
                        query_text,
                        *object_tags,
                        *operation_tags,
                        *mechanism_tags,
                        *graph_tags,
                        *obligation_kinds,
                        *mechanism_chain,
                        *graph_motif_tags,
                    ]
                )
            )
        )
        document_tokens = [self._record_tokens(record) for record in self.records]
        document_frequency: Counter[str] = Counter()
        for tokens in document_tokens:
            document_frequency.update(set(tokens))
        avg_length = sum(map(len, document_tokens)) / max(1, len(document_tokens))
        ranked: list[tuple[float, dict[str, Any]]] = []
        blocked_source_ids = {
            str(item.get("source_record_id"))
            for item in self.negative_records
            if item.get("source_record_id")
            and (problem_hash is None or item.get("problem_hash") == problem_hash)
        }
        for record, tokens in zip(self.records, document_tokens, strict=True):
            if str(record.get("record_id")) in blocked_source_ids:
                continue
            counts = Counter(tokens)
            score = 0.0
            for term in set(query):
                frequency = counts[term]
                if not frequency:
                    continue
                inverse = math.log(
                    1
                    + (len(self.records) - document_frequency[term] + 0.5)
                    / (document_frequency[term] + 0.5)
                )
                denominator = frequency + 1.2 * (
                    0.25 + 0.75 * len(tokens) / max(1.0, avg_length)
                )
                score += inverse * frequency * 2.2 / denominator
            # Structural tags outrank raw keyword overlap.
            score += 2.0 * len(set(object_tags) & set(record.get("object_tags", [])))
            score += 2.0 * len(
                set(operation_tags) & set(record.get("operation_tags", []))
            )
            score += 1.5 * len(
                set(mechanism_tags) & set(record.get("mechanism_tags", []))
            )
            score += 1.5 * len(set(graph_tags) & set(record.get("graph_tags", [])))
            score += 3.0 * len(
                set(obligation_kinds) & set(record.get("obligation_kinds", []))
            )
            score += 2.5 * len(
                set(mechanism_chain) & set(record.get("mechanism_chain", []))
            )
            score += 3.0 * len(
                set(graph_motif_tags) & set(record.get("obligation_graph_motif", []))
            )
            if score > 0:
                ranked.append((score, record))
        ranked.sort(key=lambda item: (-item[0], str(item[1].get("record_id", ""))))
        return [dict(record, retrieval_score=score) for score, record in ranked[:top_k]]

    @staticmethod
    def _record_tokens(record: dict[str, Any]) -> list[str]:
        values = [
            str(record.get("problem_summary", "")),
            str(record.get("proof_summary", "")),
            " ".join(map(str, record.get("object_tags", []))),
            " ".join(map(str, record.get("operation_tags", []))),
            " ".join(map(str, record.get("mechanism_tags", []))),
            " ".join(map(str, record.get("graph_tags", []))),
            " ".join(map(str, record.get("obligation_kinds", []))),
            " ".join(map(str, record.get("mechanism_chain", []))),
            " ".join(map(str, record.get("obligation_graph_motif", []))),
        ]
        return _tokens(" ".join(values))
