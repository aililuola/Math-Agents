from __future__ import annotations

from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.proof_control.models import (
    GateVerdict,
    ObligationSemanticVerdict,
    RouteAdmissionRecord,
)
from mathproofmesh.proof_control.semantic_quality import ObligationSemanticGate
from mathproofmesh.proof_control.strategy_blueprint import StrategyBlueprintCompiler
from mathproofmesh.schemas import ObligationKind, ProofObligation, StrategyCard

from v07_helpers import PROBLEM_HASH
from v082_helpers import make_control_runtime, make_domain_strategy


def _chinese_strategy(strategy_id: str = "strategy-zh") -> StrategyCard:
    return StrategyCard(
        strategy_id=strategy_id,
        title="剩余类稳定化路线",
        core_idea=(
            "证明合格数集合是模某个固定 M 的剩余类并集，"
            "再证明该集合有限步后稳定，从而用鸽巢原理得到周期。"
        ),
        independence_basis="使用剩余类结构而非直接搜索。",
        expected_lemmas=[
            "序列中出现的全体素因子构成有限集。",
            "存在正整数 N 和 C，使得对所有 n≥N，a_{n+1}-a_n≤C。",
        ],
        bottleneck="对于任意n，合格数集合 S_n 是模某个 M_n 的剩余类的并集。",
        prerequisites=["数列由大于 1 的正整数组成且严格递增。"],
        key_original_step="固定前若干项，写出合格集合的同余刻画。",
        falsification_test="check x in [0, 4]: x + 0 == x",
        estimated_success=0.7,
        tags=["residue-class-stabilization", "pigeonhole-period"],
    )


def test_multilingual_statements_are_accepted() -> None:
    gate = ObligationSemanticGate()
    statements = [
        "序列中出现的全体素因子构成有限集。",
        "d_n ≥ 1且序列严格递增。",
        "存在正整数 N 和 C，使得对所有 n≥N，a_{n+1}-a_n≤C。",
        "引理1：对于任意n，S_n是模某个M_n的剩余类的并集。",
        "\\forall n \\ge 1, a_{n+1} \\le a_n + C",
    ]
    for statement in statements:
        quality = gate.assess_statement(statement, source_kind="strategy_blueprint")
        assert quality.verdict == ObligationSemanticVerdict.ACCEPT, statement
        assert quality.truth_apt, statement
        assert not quality.semantic_quarantine, statement


def test_fullwidth_math_symbols_are_semantically_parsed() -> None:
    gate = ObligationSemanticGate()
    statement = (
        "\u5bf9\u4efb\u610f\uff4e\uff0c\u82e5\uff4e\u2267\uff11\uff0c"
        "\u5219\uff4e\uff0b\uff11\uff1e\uff4e\u3002"
    )

    quality = gate.assess_statement(statement, source_kind="strategy_blueprint")

    assert quality.verdict == ObligationSemanticVerdict.ACCEPT
    assert quality.truth_apt
    assert quality.has_explicit_relation


def test_incomplete_math_statement_needs_normalization_not_reject() -> None:
    quality = ObligationSemanticGate().assess_statement(
        "素数因子的分布结构",
        source_kind="strategy_blueprint",
    )
    assert quality.verdict == ObligationSemanticVerdict.NEEDS_NORMALIZATION
    assert not quality.semantic_quarantine
    assert not quality.accepted
    assert quality.normalization_needs


def test_missing_first_step_is_not_a_rejection_reason() -> None:
    quality = ObligationSemanticGate().assess_statement(
        "Find a suitable invariant.",
        source_kind="strategy_blueprint",
    )
    assert not quality.accepted
    assert "no_executable_first_step" not in quality.rejection_reasons


def test_chinese_strategy_passes_route_admission(tmp_path) -> None:
    *_runtime, control, _main_goal = make_control_runtime(tmp_path)
    strategy = _chinese_strategy()

    admitted, records = control.admit_routes([strategy])

    assert admitted == [strategy]
    assert records[0].verdict in {GateVerdict.PASS, GateVerdict.SHADOW_BLOCK}
    assert control.state.strategy_blueprints[strategy.strategy_id].status == "accepted"


def test_first_unparseable_lemma_does_not_kill_strategy(tmp_path) -> None:
    *_runtime, control, _main_goal = make_control_runtime(tmp_path)
    strategy = make_domain_strategy(
        strategy_id="strategy-fallback-target",
        expected_lemmas=[
            "素数因子的分布结构",
            "Every admissible object has a canonical decomposition.",
            "The canonical decomposition satisfies the target relation.",
        ],
    )

    admitted, records = control.admit_routes([strategy])

    assert admitted == [strategy]
    assert records[0].verdict in {GateVerdict.PASS, GateVerdict.SHADOW_BLOCK}
    binding = next(
        item
        for item in control.state.route_target_bindings.values()
        if item.strategy_id == strategy.strategy_id
    )
    target = control.proof_graph.get_obligation(binding.direct_target_obligation_id)
    assert "canonical decomposition" in target.statement


def test_binding_failure_reason_is_actionable(tmp_path) -> None:
    *_runtime, control, _main_goal = make_control_runtime(tmp_path)
    strategy = make_domain_strategy(
        strategy_id="strategy-all-unparseable",
        expected_lemmas=["素数因子的分布结构", "整数集合的某种性质"],
    )

    admitted, records = control.admit_routes([strategy])

    assert admitted == []
    reasons = " ".join(records[0].reasons)
    assert "system parsing issue" in reasons
    assert "needs normalization" in reasons


def test_bottleneck_metadata_is_not_compiled_into_lemma(tmp_path) -> None:
    *_runtime, main_goal = make_control_runtime(tmp_path)
    strategy = make_domain_strategy(strategy_id="strategy-bottleneck-meta")
    strategy = strategy.model_copy(update={"bottleneck": "Find a suitable invariant."})
    compilation = StrategyBlueprintCompiler().compile(
        strategy,
        problem_hash=main_goal.problem_hash,
        main_goal=main_goal,
    )
    assert all(node.source_field != "bottleneck" for node in compilation.nodes)


def test_blueprint_edges_are_marked_as_list_order_guesses(tmp_path) -> None:
    *_runtime, main_goal = make_control_runtime(tmp_path)
    compilation = StrategyBlueprintCompiler().compile(
        make_domain_strategy(strategy_id="strategy-edge-origin"),
        problem_hash=main_goal.problem_hash,
        main_goal=main_goal,
    )
    assert compilation.edges
    assert all(edge.origin == "list_order_guess" for edge in compilation.edges)
    assert all(not edge.verified for edge in compilation.edges)


def test_main_goal_provenance_not_overwritten(tmp_path) -> None:
    *_runtime, control, main_goal = make_control_runtime(tmp_path)
    first = make_domain_strategy(strategy_id="strategy-first")
    second = make_domain_strategy(
        strategy_id="strategy-second",
        expected_lemmas=[
            "Every bounded orbit meets the fundamental domain.",
            "The fundamental domain intersection is finite.",
        ],
    )

    control.admit_routes([first])
    recorded = control.state.blueprint_nodes[main_goal.obligation_id].strategy_id
    control.admit_routes([second])

    assert (
        control.state.blueprint_nodes[main_goal.obligation_id].strategy_id == recorded
    )


def test_retract_tentative_obligation_removes_only_drafts(tmp_path) -> None:
    *_runtime, _control, main_goal = make_control_runtime(tmp_path)
    graph = _runtime[4]
    draft = graph.add_obligation(
        ProofObligation(
            obligation_id="draft-obligation",
            problem_hash=PROBLEM_HASH,
            route_ids=["route-blocked"],
            kind=ObligationKind.LEMMA,
            statement="Every draft satisfies the retraction relation.",
            normalized_statement=("every draft satisfies the retraction relation."),
            status="tentative",
        )
    )
    removed = graph.retract_tentative_obligation(
        draft.obligation_id, route_id="route-blocked", reason="test"
    )
    assert removed is not None
    assert all(item.obligation_id != draft.obligation_id for item in graph.obligations)
    # The main goal is open, not tentative: it must never be retractable.
    kept = graph.retract_tentative_obligation(main_goal.obligation_id, reason="test")
    assert kept is None
    assert any(
        item.obligation_id == main_goal.obligation_id for item in graph.obligations
    )


def test_admission_starvation_classification() -> None:
    repairable = [
        RouteAdmissionRecord(
            strategy_id=f"s{i}",
            verdict=GateVerdict.BLOCK,
            alignment_score=0.0,
            target_obligation_ids=[],
            reasons=["blueprint has no semantically admissible direct target"],
        )
        for i in range(3)
    ]
    verdict = ProofMeshOrchestrator._classify_admission_starvation(repairable)
    assert verdict["category"] == "systemic_semantic_failure"

    mathematical = [
        RouteAdmissionRecord(
            strategy_id=f"m{i}",
            verdict=GateVerdict.BLOCK,
            alignment_score=0.0,
            target_obligation_ids=[],
            reasons=["strategy was refuted by a counterexample"],
        )
        for i in range(3)
    ]
    verdict = ProofMeshOrchestrator._classify_admission_starvation(mathematical)
    assert verdict["category"] == "strategy_space_exhausted"


def test_repair_exhausted_terminates_with_reason() -> None:
    starvation = {
        "category": "systemic_semantic_failure",
        "repair_attempted": True,
        "repair_exhausted": True,
    }

    assert ProofMeshOrchestrator._admission_termination_reason(starvation) == (
        "NO_ROUTES_ADMITTED(repair_exhausted)"
    )
