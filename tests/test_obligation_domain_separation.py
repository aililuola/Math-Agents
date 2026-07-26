from __future__ import annotations

from mathproofmesh.proof_control.bottleneck import BottleneckCompressor
from mathproofmesh.proof_control.common_mode import CriticalAssumptionMatrix
from mathproofmesh.proof_control.domains import (
    classify_assumption_domain,
    classify_obligation_domain,
)
from mathproofmesh.proof_control.models import (
    AssumptionDomain,
    ObligationDomain,
    ObligationDomainRecord,
)
from mathproofmesh.proof_graph.store import ProofGraphStore
from mathproofmesh.schemas import ObligationKind, ProofObligation

from v07_helpers import PROBLEM_HASH


def _obligation(identifier: str, statement: str) -> ProofObligation:
    return ProofObligation(
        obligation_id=identifier,
        problem_hash=PROBLEM_HASH,
        route_ids=["route-a"],
        kind=ObligationKind.SUBGOAL,
        statement=statement,
        normalized_statement=statement.casefold(),
    )


def test_protocol_text_not_mathematical_assumption() -> None:
    record = classify_assumption_domain(
        "The response must retain the goal hash and output the required schema.",
        source_kind="protocol",
    )

    assert record.domain == AssumptionDomain.PROTOCOL


def test_search_task_not_in_core_debt() -> None:
    task = _obligation("search-a", "Find a representation that may expose a bridge.")
    record = classify_obligation_domain(task, source_kind="search")

    assert record.domain == ObligationDomain.SEARCH
    assert not record.eligible_for_mathematical_control


def test_tool_task_not_route_target() -> None:
    task = _obligation("tool-a", "Run an exact bounded computation.")
    record = classify_obligation_domain(task, source_kind="computation")

    assert record.domain == ObligationDomain.TOOL
    assert not record.eligible_for_route_target


def test_bottleneck_ignores_nonmathematical_obligation() -> None:
    graph = ProofGraphStore(problem_hash=PROBLEM_HASH)
    mathematical = graph.add_obligation(
        _obligation(
            "math-a",
            "Every canonical decomposition satisfies the compatibility relation.",
        )
    )
    search = graph.add_obligation(
        _obligation("search-b", "Find a suitable invariant for the route.")
    )
    domains = {
        mathematical.obligation_id: ObligationDomainRecord(
            obligation_id=mathematical.obligation_id,
            domain=ObligationDomain.MATHEMATICAL,
            inferred_from="strategy_blueprint",
            confidence=1.0,
        ),
        search.obligation_id: ObligationDomainRecord(
            obligation_id=search.obligation_id,
            domain=ObligationDomain.SEARCH,
            inferred_from="search",
            confidence=1.0,
        ),
    }

    scanned = BottleneckCompressor().scan_open_obligations(
        graph,
        obligation_domains=domains,
    )

    assert [item.obligation_id for item in scanned] == [mathematical.obligation_id]


def test_common_mode_ignores_goal_hash_protocol() -> None:
    record = classify_assumption_domain(
        "Retain the goal hash across all checkpoints.",
        source_kind="protocol",
    )
    matrix = CriticalAssumptionMatrix()

    assert record.domain == AssumptionDomain.PROTOCOL
    assert matrix.semantic_family_key(
        "Every compatible decomposition has a unique canonical representative.",
        scope_signature_id="scope-a",
        mechanism_chain=["decompose", "canonicalize"],
    ) == matrix.semantic_family_key(
        "Each compatible decomposition admits one canonical representative.",
        scope_signature_id="scope-a",
        mechanism_chain=["decompose", "canonicalize"],
    )
