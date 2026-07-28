from __future__ import annotations

from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.schemas import ClaimCard, ClaimStatus, StrategyCard
from mathproofmesh.topology import (
    SparseTopologyRouter,
    _cosine_similarity,
    _math_embedding,
    _math_normalize,
    jaccard_similarity,
    math_similarity,
)


def _strategy(title: str, idea: str, success: float) -> StrategyCard:
    return StrategyCard(
        title=title,
        core_idea=idea,
        independence_basis=idea,
        bottleneck="b",
        falsification_test="test",
        estimated_success=success,
        estimated_cost=0.2,
    )


def test_latex_and_ascii_renamed_recurrence_bounds_match() -> None:
    latex = "$a_{n+1} \\le a_n + C$"
    ascii_form = "b_{k+1} <= b_k + D"
    # Math-normalized similarity sees the same mechanism...
    assert math_similarity(latex, ascii_form) >= 0.6
    # ...while plain lexical Jaccard is near zero on the same pair.
    assert jaccard_similarity(latex, ascii_form) < 0.2


def test_alpha_renaming_is_order_canonical() -> None:
    assert _math_normalize("f(x)+g(y)") == _math_normalize("u(s)+w(t)")


def test_math_embedding_is_deterministic_and_alpha_invariant() -> None:
    first = _math_embedding("$a_{n+1} \\le a_n + C$")
    second = _math_embedding("b_{k+1} <= b_k + D")

    assert first == _math_embedding("$a_{n+1} \\le a_n + C$")
    assert _cosine_similarity(first, second) >= 0.9


def test_single_letters_with_numeric_subscripts_rename_as_units() -> None:
    assert _math_normalize("a_1 + a_2") == _math_normalize("x_1 + x_2")


def test_function_words_are_never_renamed() -> None:
    normalized = _math_normalize("\\gcd(m, n) = 1 and sin x + log y")
    assert "gcd" in normalized
    assert "sin" in normalized
    assert "log" in normalized
    # The single-letter variables around them are still canonicalized.
    assert "v1" in normalized


def test_fixed_operator_table_and_quantifiers() -> None:
    normalized = _math_normalize("$\\forall x \\in S, x \\le y$")
    assert "对任意" in normalized
    assert "∈" in normalized
    assert "\\" not in normalized
    assert "$" not in normalized


def test_genuinely_different_statements_stay_apart() -> None:
    recurrence = "$a_{n+1} \\le a_n + C$"
    quadratic = "\\forall x \\in S, f(x) = x^2 + 1 是奇函数"
    combinatorial = "用双计数法统计集合划分的个数 double counting of set partitions"
    assert math_similarity(recurrence, quadratic) < 0.5
    assert math_similarity(recurrence, combinatorial) < 0.5


def test_diverse_strategy_selection_avoids_near_duplicates(
    demo_config, artifact_store
) -> None:
    # Original identical-mechanism dedup behavior (template: test_topology.py).
    pool = AgentPool(demo_config)
    router = SparseTopologyRouter(demo_config, pool, artifact_store)
    strategies = [
        _strategy("Induction A", "induction recurrence squares", 0.95),
        _strategy("Induction B", "induction recurrence square identity", 0.94),
        _strategy("Telescoping", "finite differences telescope", 0.88),
    ]
    selected = router.select_diverse_strategies(strategies, 2)
    titles = {s.title for s in selected}
    assert "Telescoping" in titles
    assert len(titles) == 2


def test_diverse_strategy_selection_dedupes_renamed_notation(
    demo_config, artifact_store
) -> None:
    # The same recurrence-bound mechanism written in LaTeX with one variable
    # set and in ASCII with another must count as near-duplicates.
    pool = AgentPool(demo_config)
    router = SparseTopologyRouter(demo_config, pool, artifact_store)
    strategies = [
        _strategy("Bound A", "show $a_{n+1} \\le a_n + C$ by induction", 0.95),
        _strategy("Bound B", "show b_{k+1} <= b_k + D by induction", 0.94),
        _strategy("Telescoping", "finite differences telescope the sum", 0.88),
    ]
    selected = router.select_diverse_strategies(strategies, 2)
    titles = {s.title for s in selected}
    assert "Telescoping" in titles
    assert len(titles) == 2


def test_relevant_claims_rank_math_equivalent_statement_first(
    demo_config, artifact_store
) -> None:
    pool = AgentPool(demo_config)
    router = SparseTopologyRouter(demo_config, pool, artifact_store)
    recurrence_claim = ClaimCard(
        statement="$x_{n+1} \\le x_n + M$",
        conclusion="序列增量有界",
        source_attempt_id="path-1",
        status=ClaimStatus.VERIFIED,
        verification_confidence=0.9,
        tags=["recurrence"],
    )
    geometry_claim = ClaimCard(
        statement="三角形内角和为180度",
        conclusion="角度恒等式成立",
        source_attempt_id="path-2",
        status=ClaimStatus.VERIFIED,
        verification_confidence=0.9,
        tags=["geometry"],
    )
    selected = router.relevant_claims(
        [geometry_claim, recurrence_claim],
        _strategy("递推上界", "b_{k+1} <= b_k + D", 0.8),
    )
    assert selected
    assert selected[0].statement == recurrence_claim.statement
