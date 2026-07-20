from pathlib import Path
from runpy import run_path


run_benchmark = run_path(
    str(Path(__file__).parents[1] / "benchmarks" / "reasoning_first_computation.py")
)["run_benchmark"]


def test_enumeration_proxy_reduces_reasoning_tokens_without_correctness_loss() -> None:
    result = run_benchmark()

    assert result["correctness_rate"] == 1.0
    assert result["estimated_token_reduction"] >= 0.80
