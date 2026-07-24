from __future__ import annotations

import json
from pathlib import Path
from typing import Any

from mathproofmesh.llm.base import Message
from mathproofmesh.mock_demo import demo_responder
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.schemas import ClaimCard, ClaimStatus, ProblemContract, RunStatus
from mathproofmesh.store import ArtifactStore

from v07_helpers import make_v07_config


MARKER = "REJECTED_ROUTE_LOCAL_CLAIM_DO_NOT_SHARE"


async def test_legacy_pre_strategy_checkpoint_rebuilds_hierarchical_routes(
    tmp_path: Path,
) -> None:
    config = make_v07_config(
        tmp_path / "runs",
        graph_mode="active",
        inspiration_mode="off",
    )
    config.budget.max_rounds = 2
    run_id = "legacy-pre-strategy-hierarchical-resume"
    store = ArtifactStore(config.runtime.run_root, run_id)
    problem = ProblemContract(
        exact_statement="Prove that the first n odd integers sum to n squared.",
        normalized_statement="first n odd integers sum to n squared",
    )
    legacy_claim = ClaimCard(
        claim_id="old-route-local-claim",
        statement=MARKER,
        conclusion=MARKER,
        status=ClaimStatus.VERIFIED,
        verification_confidence=0.99,
    )
    store.write_json("structured", "problem_contract", problem)
    store.checkpoint(
        "triage",
        {
            "triage": None,
            "strategies": [],
            "attempts": [],
            "reports": [],
            "aggregate_reports": {},
            "meta_reviews": [],
            "claims": [legacy_claim.model_dump(mode="json")],
            "calls_started": 0,
            "stage_calls": {},
            "bucket_calls": {},
            "agent_metrics": [],
        },
    )
    route_prove_prompts: list[str] = []
    legacy_continuation_prompts: list[str] = []

    def responder(
        schema_name: str | None,
        messages: list[Message],
        schema: dict[str, Any] | None,
    ) -> dict[str, Any]:
        text = "\n".join(item["content"] for item in messages)
        if "[STAGE:route_prove]" in text:
            route_prove_prompts.append(text)
        if "[STAGE:proof_continuation]" in text:
            legacy_continuation_prompts.append(text)
        return demo_responder(schema_name, messages, schema)

    result = await ProofMeshOrchestrator(
        config,
        mock_responders={agent.id: responder for agent in config.agents},
    ).resume(run_id)

    assert result.status == RunStatus.VERIFIED
    root = Path(result.run_directory)
    strategies = json.loads(
        (root / "structured" / "selected_strategies.json").read_text(encoding="utf-8")
    )
    registry = json.loads(
        (root / "structured" / "route_registry.json").read_text(encoding="utf-8")
    )
    broker = json.loads(
        (root / "structured" / "message_broker.json").read_text(encoding="utf-8")
    )
    typed_memory = json.loads(
        (root / "structured" / "typed_memory.json").read_text(encoding="utf-8")
    )
    assert len(registry["routes"]) == len(strategies)
    assert {route["strategy_id"] for route in registry["routes"]} == {
        strategy["strategy_id"] for strategy in strategies
    }
    assert len(route_prove_prompts) == len(strategies)
    assert not legacy_continuation_prompts
    assert all(MARKER not in prompt for prompt in route_prove_prompts)
    assert "messages" in broker
    assert "tiers" in typed_memory

    events = [
        json.loads(line)
        for line in (root / "events.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    assert sum(
        event.get("event_type") == "route_registered" for event in events
    ) >= len(strategies)
    route_teams = [
        event for event in events if event.get("event_type") == "route_team_started"
    ]
    assert route_teams
    routes_by_id = {route["route_id"]: route for route in registry["routes"]}
    for event in route_teams:
        payload = event["payload"]
        assert any(
            member["agent_id"] == payload["prover_agent_id"]
            and member["role"] == "prover"
            for member in routes_by_id[payload["route_id"]]["members"]
        )

    inventory = json.loads(
        (root / "reports" / "global_fact_inventory.json").read_text(encoding="utf-8")
    )
    global_statements = {
        item["statement"] for item in inventory["broker_admitted_global_facts"]
    }
    assert MARKER not in global_statements
    assert any(
        item["statement"] == MARKER for item in inventory["legacy_claim_history"]
    )
    report = (root / "reports" / "run_report.md").read_text(encoding="utf-8")
    assert "Broker 准入的全局 Fact 数：" in report
    assert "已验证引理数：" not in report
    assert "Legacy ClaimMemory 历史记录数：" in report
    assert MARKER in report
