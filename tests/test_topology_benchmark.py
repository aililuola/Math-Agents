from benchmarks.topology.run_mock_benchmark import run_mock_benchmark


def test_topology_mock_benchmark_covers_contracts_variants_and_metrics() -> None:
    result = run_mock_benchmark()
    assert result["provider_calls"] == 0
    assert result["variant_count"] == 11
    assert all(result["component_contracts"].values())
    active = next(
        item
        for item in result["variants"]
        if item["variant"] == "v0.7 active graph + inspiration active"
    )
    legacy = result["variants"][0]
    assert (
        active["metrics"]["verified_solve_rate"]
        > legacy["metrics"]["verified_solve_rate"]
    )
    assert active["metrics"]["resume_duplicate_delivery_rate"] == 0.0
    assert active["metrics"]["inspiration_trigger_rate"] == 1.0
    assert active["metrics"]["verified_breakthrough_conversion_rate"] > 0.0
