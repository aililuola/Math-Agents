from __future__ import annotations

import json
from pathlib import Path

from mathproofmesh.mock_demo import demo_responders
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.schemas import ExperimentSpec, RunStatus

from v07_helpers import make_v07_config


def _events(root: Path) -> list[dict[str, object]]:
    return [
        json.loads(line)
        for line in (root / "events.jsonl").read_text(encoding="utf-8").splitlines()
    ]


async def test_active_route_uses_real_prover_skeptic_referee_pipeline(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs", inspiration_mode="off")
    config.continuation.enabled = True
    config.continuation.segments_per_explore_call = 1
    result = await ProofMeshOrchestrator(
        config,
        mock_responders=demo_responders(config),
    ).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="active-route-team",
    )

    assert result.status == RunStatus.VERIFIED
    events = _events(Path(result.run_directory))
    names = [str(item["event_type"]) for item in events]
    assert "route_team_started" in names
    assert "route_skeptic_completed" in names
    assert "route_referee_completed" in names
    assert "route_local_review_completed" in names
    assert names.index("route_team_started") < names.index("route_referee_completed")
    reviews = [
        item["payload"]
        for item in events
        if item["event_type"] == "route_local_review_completed"
    ]
    assert reviews
    assert all(review["referee_agent_id"] for review in reviews)
    assert all(review["global_share_allowed"] is True for review in reviews)


async def test_active_tool_specialist_is_called_as_an_independent_role(
    tmp_path,
) -> None:
    config = make_v07_config(tmp_path / "runs", inspiration_mode="off")
    config.computation.enabled = True
    config.budget.initial_paths = 1
    config.budget.max_paths = 1
    config.budget.strategies_to_generate = 1
    config.budget.candidates_to_verify = 1
    config.budget.max_rounds = 1
    config.continuation.segments_per_explore_call = 1
    requested = False
    tool_audit_calls = 0

    def responder(schema_name, messages, schema):
        nonlocal requested, tool_audit_calls
        if schema_name == "ContinuationTurn" and not requested:
            requested = True
            spec = ExperimentSpec(
                purpose="falsify_claim",
                target_claim="For every sampled integer x, x squared equals x.",
                assumptions=["x is in the declared singleton domain."],
                reasoning_basis=(
                    "The route introduced a precise universal auxiliary claim."
                ),
                why_computation_is_needed=(
                    "One exact substitution cheaply prevents reliance on a false lemma."
                ),
                decision_if_confirmed="Keep investigating without promoting the sample.",
                decision_if_refuted="Delete the auxiliary claim and retain the independent proof.",
                noncomputational_alternative="Prove or refute the polynomial identity symbolically.",
                method="numeric_counterexample",
                arguments={
                    "lhs": "x^2",
                    "rhs": "x",
                    "relation": "eq",
                    "variables": ["x"],
                    "ranges": {"x": [10, 10]},
                    "samples": 1,
                },
                exact_arithmetic=False,
                max_cases=1,
            )
            return {
                "action": "request_computation",
                "experiment_spec": spec.model_dump(mode="json"),
                "reason": "Run a cheap directed refutation before continuing.",
            }
        if schema_name == "ToolAuditReport":
            tool_audit_calls += 1
        response = demo_responders(config)[config.agents[0].id](
            schema_name, messages, schema
        )
        if schema_name == "ContinuationTurn" and requested:
            response["experiment_impact"] = "execution"
            response["reason"] = (
                "The false auxiliary claim was removed; the telescoping proof is independent."
            )
        return response

    result = await ProofMeshOrchestrator(
        config,
        mock_responders={agent.id: responder for agent in config.agents},
    ).solve(
        "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2.",
        run_id="active-tool-specialist",
    )

    assert result.experiments
    events = _events(Path(result.run_directory))
    tool_events = [
        item for item in events if item["event_type"] == "route_tool_audit_completed"
    ]
    assert tool_audit_calls == len(tool_events) == 1
    started = [
        item["payload"]
        for item in events
        if item["event_type"] == "route_team_started"
        and item["payload"].get("tool_agent_id")
    ]
    assert len(started) == 1
    assigned = {
        started[0]["prover_agent_id"],
        started[0]["skeptic_agent_id"],
        started[0]["tool_agent_id"],
        started[0]["referee_agent_id"],
    }
    assert None not in assigned
    assert len(assigned) == 4
