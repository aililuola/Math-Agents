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


async def test_hierarchical_resume_quarantines_legacy_only_checkpoint_claim(
    tmp_path,
) -> None:
    config = make_v07_config(
        tmp_path / "runs",
        graph_mode="active",
        inspiration_mode="off",
    )
    config.budget.max_rounds = 2
    run_id = "legacy-checkpoint-quarantine"
    store = ArtifactStore(config.runtime.run_root, run_id)
    problem = ProblemContract(
        exact_statement="Prove that the first n odd integers sum to n squared.",
        normalized_statement="first n odd integers sum to n squared",
    )
    legacy_claim = ClaimCard(
        claim_id="old-checkpoint-claim",
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
    synthesis_prompts: list[str] = []
    route_prove_prompts: list[str] = []
    legacy_continuation_prompts: list[str] = []

    def responder(
        schema_name: str | None,
        messages: list[Message],
        schema: dict[str, Any] | None,
    ) -> dict[str, Any]:
        text = "\n".join(item["content"] for item in messages)
        if schema_name == "FinalProof" and "[STAGE:synthesis]" in text:
            synthesis_prompts.append(text)
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
    typed_state = json.loads(
        (root / "structured" / "typed_memory.json").read_text(encoding="utf-8")
    )
    typed_statements = {
        item.get("statement", "") for item in typed_state.get("messages", {}).values()
    }
    assert MARKER not in typed_statements
    assert route_prove_prompts
    assert all(MARKER not in prompt for prompt in route_prove_prompts)
    assert not legacy_continuation_prompts
    assert synthesis_prompts
    assert all(MARKER not in prompt for prompt in synthesis_prompts)
    blind_packet = (root / "structured" / "blind_final_review_packet.json").read_text(
        encoding="utf-8"
    )
    assert MARKER not in blind_packet
    events = [
        json.loads(line)
        for line in (root / "events.jsonl").read_text(encoding="utf-8").splitlines()
    ]
    quarantine = [
        event
        for event in events
        if event.get("event_type") == "legacy_claims_quarantined"
    ]
    assert quarantine
    assert quarantine[-1]["payload"]["verified_count"] == 1
