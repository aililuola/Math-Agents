from __future__ import annotations

from typing import Any, Iterable

from ..schemas import AnalogyMapping, NoveltySignature, ProblemContract, stable_hash
from .local_library import LocalAnalogyLibrary


class AnalogyAgent:
    """Map verified local structures; return empty rather than invent a precedent."""

    def __init__(self, library: LocalAnalogyLibrary, *, top_k: int = 6) -> None:
        self.library = library
        self.top_k = top_k

    def search(
        self,
        problem: ProblemContract,
        *,
        target_obligation_ids: Iterable[str],
        object_tags: Iterable[str] = (),
        operation_tags: Iterable[str] = (),
        mechanism_tags: Iterable[str] = (),
        graph_tags: Iterable[str] = (),
    ) -> list[AnalogyMapping]:
        records = self.library.search(
            query_text=problem.normalized_statement,
            object_tags=object_tags,
            operation_tags=operation_tags,
            mechanism_tags=mechanism_tags,
            graph_tags=graph_tags,
            top_k=self.top_k,
        )
        results: list[AnalogyMapping] = []
        for record in records:
            mapping = self._mapping_from_record(
                record,
                problem_hash=problem.integrity_hash,
                target_obligation_ids=list(target_obligation_ids),
            )
            if mapping is not None:
                results.append(mapping)
        return results

    @staticmethod
    def _mapping_from_record(
        record: dict[str, Any],
        *,
        problem_hash: str,
        target_obligation_ids: list[str],
    ) -> AnalogyMapping | None:
        objects = record.get("object_correspondence") or {}
        operations = record.get("operation_correspondence") or {}
        transferable = record.get("transferable_lemmas") or []
        non_transferable = record.get("non_transferable_conditions") or []
        risks = record.get("transfer_risks") or []
        if not all((objects, operations, transferable, non_transferable, risks)):
            return None
        signature = NoveltySignature(
            representation_tags=list(record.get("representation_tags", [])),
            mechanism_tags=[
                "structural_analogy",
                *list(record.get("mechanism_tags", [])),
            ],
            core_objects=list(objects.values()),
            key_transformations=list(operations.values()),
            proof_principles=list(record.get("proof_principles", [])),
            targeted_obligation_ids=target_obligation_ids,
        )
        digest = stable_hash(
            (record.get("record_id"), problem_hash, signature.normalized_hash)
        )
        return AnalogyMapping(
            analogy_id=f"analogy_{digest[:12]}",
            source_record_id=str(record["record_id"]),
            source_problem_summary=str(record.get("problem_summary", "")),
            target_problem_hash=problem_hash,
            object_correspondence={str(k): str(v) for k, v in objects.items()},
            operation_correspondence={str(k): str(v) for k, v in operations.items()},
            transferable_lemmas=[str(item) for item in transferable],
            non_transferable_conditions=[str(item) for item in non_transferable],
            transfer_risks=[str(item) for item in risks],
            required_bridge_lemmas=[
                str(item) for item in record.get("required_bridge_lemmas", [])
            ],
            novelty_signature=signature,
        )
