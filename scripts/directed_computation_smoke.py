from __future__ import annotations

import argparse
import asyncio
import json
from pathlib import Path
from typing import Any

from mathproofmesh.activity import ActivityStream
from mathproofmesh.agents import StructuredAgentRunner
from mathproofmesh.computation.broker import ToolBroker
from mathproofmesh.llm.mock import MockClient
from mathproofmesh.llm.pool import AgentPool
from mathproofmesh.memory import LemmaMemory
from mathproofmesh.mock_demo import build_demo_config
from mathproofmesh.orchestrator import ProofMeshOrchestrator
from mathproofmesh.prompts import PromptFactory
from mathproofmesh.schemas import ProblemContract, StrategyCard, new_id
from mathproofmesh.store import ArtifactStore
from mathproofmesh.topology import SparseTopologyRouter


SMOKE_PROBLEM = (
    "定义 a_1=6，且 a_{n+1} 是大于 a_n、并与此前每一项的最大公因数都大于 "
    "1 的最小正整数。先定向计算前 12 项，再据此提出一个必须另行证明的候选规律。"
)


class DirectedComputationResponder:
    def __init__(self) -> None:
        self.exploration_calls = 0
        self.pattern_completion_calls = 0

    def __call__(
        self,
        schema_name: str | None,
        messages: list[dict[str, str]],
        schema: dict[str, Any] | None,
    ) -> dict[str, Any]:
        del messages, schema
        if schema_name == "CandidateConjectureBatch":
            self.pattern_completion_calls += 1
            return {
                "candidate_conjectures": [
                    {
                        "statement": (
                            "a_n = 2n + 4 for every n >= 1; equivalently, "
                            "a_{n+1} = a_n + 2."
                        ),
                        "rationale": (
                            "The exact 12-term prefix consists of consecutive even "
                            "integers starting at 6."
                        ),
                        "supporting_experiment_ids": ["smoke-greedy-prefix-12"],
                        "evidence_refs": [],
                        "scope_limitations": [
                            "A deterministic finite prefix does not prove the formula "
                            "for all n."
                        ],
                        "proof_obligations": [
                            "Prove from the least-candidate rule that every next term "
                            "is exactly the next even integer."
                        ],
                        "confidence": 0.95,
                        "status": "candidate",
                    }
                ]
            }
        if schema_name != "InitialExplorationTurn":
            raise AssertionError(f"unexpected schema in smoke test: {schema_name}")
        self.exploration_calls += 1
        if self.exploration_calls == 1:
            return {
                "action": "request_computation",
                "experiment_spec": {
                    "experiment_id": "smoke-greedy-prefix-12",
                    "purpose": "discover_pattern",
                    "target_claim": (
                        "The first 12 terms of the declared greedy sequence form "
                        "an exact finite prefix from which to propose a conjecture."
                    ),
                    "assumptions": [
                        "a_1=6",
                        "Each next term is the least larger positive integer sharing "
                        "a nontrivial gcd with every previous term.",
                    ],
                    "reasoning_basis": (
                        "The recurrence is deterministic, but manually checking every "
                        "least-candidate condition is error-prone."
                    ),
                    "why_computation_is_needed": (
                        "The typed sequence handler can replay all candidate checks "
                        "exactly and return an auditable finite prefix."
                    ),
                    "decision_if_confirmed": (
                        "Use the prefix only to formulate a candidate invariant for a "
                        "separate symbolic proof."
                    ),
                    "decision_if_refuted": (
                        "Discard the proposed invariant and correct the route before "
                        "any proof step depends on it."
                    ),
                    "noncomputational_alternative": (
                        "Derive the recurrence directly from the minimality condition "
                        "without using a numerical prefix."
                    ),
                    "method": "bounded_greedy_sequence",
                    "domains": {},
                    "arguments": {
                        "initial_values": [6],
                        "length": 12,
                        "rule": "gcd_overlap_all_prior",
                        "strictly_increasing": True,
                    },
                    "exact_arithmetic": True,
                    "broad_search": True,
                    "max_cases": 10_000,
                    "seed": 20260719,
                },
                "reason": "Generate the exact bounded prefix before proposing a pattern.",
            }
        return {
            "action": "submit_attempt",
            "attempt": {
                "problem_hash": "0" * 64,
                "strategy_id": "directed-computation-smoke",
                "agent_id": "explorer-a",
                "round_index": 0,
                "status": "partial",
                "final_answer": None,
                "proof_steps": [
                    {
                        "step_id": "bounded-observation",
                        "statement": (
                            "The typed experiment produced an exact 12-term finite "
                            "prefix; this is evidence for a conjecture, not its proof."
                        ),
                        "justification": (
                            "The bounded_greedy_sequence handler replayed the declared "
                            "least-candidate rule using exact integer arithmetic."
                        ),
                        "dependencies": [],
                        "calculations": [],
                        "citations": [],
                        "is_key_step": False,
                        "confidence": 0.99,
                    }
                ],
                "proposed_lemmas": [],
                "dead_ends": [],
                "unresolved_gaps": [
                    "Prove any candidate infinite pattern symbolically."
                ],
                "falsification_checks": [
                    "Do not promote the 12-term prefix to an infinite theorem."
                ],
                "self_confidence": 0.9,
            },
            "reason": "The directed computation completed and was interpreted safely.",
        }


async def run_smoke(run_root: Path) -> dict[str, Any]:
    run_id = new_id("dcsmoke")
    config = build_demo_config(str(run_root))
    config.continuation.enabled = False
    config.computation.enabled = True
    config.computation.typed_tools_enabled = True
    config.computation.bounded_typed_probe_fast_path = True
    config.computation.critical_calculation_gate_enabled = False

    store = ArtifactStore(run_root, run_id)
    activity = ActivityStream(store, persist=True)
    pool = AgentPool(config)
    agent = pool.get("explorer-a")
    responder = DirectedComputationResponder()
    agent.client = MockClient(responder=responder, profile="directed-computation-smoke")
    runner = StructuredAgentRunner(config, pool, store, activity=activity)
    tools = ToolBroker(config, store, activity)
    orchestrator = ProofMeshOrchestrator(config)
    memory = LemmaMemory(store)
    problem = ProblemContract(
        exact_statement=SMOKE_PROBLEM,
        normalized_statement=SMOKE_PROBLEM,
        allowed_tools=["bounded_greedy_sequence"],
    )
    strategy = StrategyCard(
        strategy_id="directed-computation-smoke",
        title="Exact finite prefix before conjecture",
        core_idea=(
            "Use one exact typed finite prefix to propose, but not prove, an invariant."
        ),
        independence_basis="Deterministic bounded computation followed by symbolic proof.",
        bottleneck="Convert the finite observation into a general symbolic argument.",
        falsification_test="Replay the first 12 terms with the registered typed handler.",
        estimated_success=0.8,
        tags=["sequence", "typed-computation"],
    )
    try:
        attempt = await orchestrator._explore_path(
            problem,
            strategy,
            agent,
            state=None,
            round_index=0,
            runner=runner,
            prompts=PromptFactory(computation_enabled=True),
            router=SparseTopologyRouter(config, pool, store),
            memory=memory,
            store=store,
            tools=tools,
            targeted_feedback=[],
            previous_attempt=None,
            budget_bucket="breadth",
        )
        activity.finalize()
        results = tools.results_for_path(f"path_{strategy.strategy_id}")
        if len(results) != 1:
            raise AssertionError(f"expected one computation result, got {len(results)}")
        result = results[0]
        completed_events = [
            event
            for event in activity.events
            if event.event_type == "computation_completed"
        ]
        if len(completed_events) != 1:
            raise AssertionError(
                f"expected one completed computation node, got {len(completed_events)}"
            )
        return {
            "problem": SMOKE_PROBLEM,
            "run_id": run_id,
            "run_dir": str(store.root),
            "attempt_status": attempt.status.value,
            "decision_rule": completed_events[0].metrics.get("rule_id"),
            "computation_node": completed_events[0].task_id,
            "outcome": result.outcome.value,
            "evidence_strength": result.evidence_strength.value,
            "values": result.certificate["values"],
            "candidate_conjectures": [
                candidate.model_dump(mode="json")
                for candidate in attempt.candidate_conjectures
            ],
            "pattern_completion_calls": responder.pattern_completion_calls,
            "claim_memory_count": len(memory.claims),
            "attempt_usage": attempt.usage.model_dump(mode="json"),
        }
    finally:
        await pool.aclose()


def main() -> None:
    parser = argparse.ArgumentParser(
        description="Run an offline end-to-end targeted computation node smoke test."
    )
    parser.add_argument(
        "--run-root",
        type=Path,
        default=Path("validation-runs") / "dc-smoke",
    )
    args = parser.parse_args()
    print(
        json.dumps(asyncio.run(run_smoke(args.run_root)), ensure_ascii=False, indent=2)
    )


if __name__ == "__main__":
    main()
