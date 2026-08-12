from __future__ import annotations

import json


def snapshot() -> dict[str, object]:
    return {
        "problem_contract": {
            "problem_id": "problem-1",
            "integrity_hash": "a" * 64,
            "kind": "theorem",
        },
        "strategies": [
            {"strategy_id": "strategy-1", "status": "active", "score": 0.9}
        ],
        "messages": [
            {
                "message_id": "message-1",
                "status": "admitted",
                "content_hash": "b" * 64,
            },
            {
                "message_id": "message-2",
                "status": "delivered",
                "content_hash": "c" * 64,
            },
        ],
        "deliveries": [
            {"delivery_id": "delivery-1", "state": "prompt_consumed"}
        ],
        "memory": {
            "facts": [{"memory_id": "memory-1", "status": "verified"}],
            "insights": [],
            "negative": [],
        },
        "proof_graph": {
            "nodes": ["goal", "lemma-1"],
            "edges": [
                {"source_id": "lemma-1", "target_id": "goal", "status": "active"}
            ],
        },
        "checkpoints": [
            {
                "checkpoint_id": "checkpoint-1",
                "status": "committed",
                "parent_checkpoint_id": "",
            }
        ],
        "recovery": {
            "checkpoint_id": "checkpoint-1",
            "provider_calls_before_resume": 0,
        },
        "usage": {"provider_calls": 0, "input_tokens": 0, "output_tokens": 0},
        "final_state": {
            "status": "completed",
            "verified_claim_hash": "d" * 64,
            "explanation": "Python wording",
        },
    }


def main() -> None:
    print(
        json.dumps(
            snapshot(),
            ensure_ascii=False,
            allow_nan=False,
            sort_keys=True,
            separators=(",", ":"),
        )
    )


if __name__ == "__main__":
    main()
