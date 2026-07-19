from __future__ import annotations

import asyncio
import json
import logging
import os
from pathlib import Path
from typing import Optional

import typer
from rich.console import Console
from rich.panel import Panel

from .activity import ActivityMode, ConsoleActivityView
from .config import ContinuationConfig, SystemConfig, load_config
from .llm.deepseek import DeepSeekClient
from .llm.pool import AgentPool
from .mock_demo import build_demo_config, demo_responders
from .orchestrator import ProofMeshOrchestrator

app = typer.Typer(
    name="mathproofmesh",
    help="Sparse, verification-first multi-agent mathematical reasoning.",
    no_args_is_help=True,
)
console = Console()
activity_console = Console(stderr=True)
_VALID_ACTIVITY_MODES: set[str] = {"off", "compact", "detailed"}


def _configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level.upper(), logging.INFO),
        format="%(asctime)s %(levelname)s %(name)s: %(message)s",
    )


def _resolve_activity_mode(
    config: SystemConfig,
    override: str | None,
    *,
    json_output: bool = False,
) -> ActivityMode:
    # Machine-readable JSON remains clean unless activity was explicitly requested.
    raw = (
        override
        if override is not None
        else ("off" if json_output else config.runtime.activity_mode)
    )
    normalized = raw.strip().lower()
    if normalized not in _VALID_ACTIVITY_MODES:
        raise typer.BadParameter("--activity must be one of: off, compact, detailed")
    return normalized  # type: ignore[return-value]


def _run_solver_with_activity(
    config: SystemConfig,
    problem: str,
    *,
    run_id: str | None,
    mode: ActivityMode,
    mock_responders=None,
):
    config.runtime.activity_mode = mode
    view = ConsoleActivityView(
        language=config.runtime.output_language,
        mode=mode,
        max_items=config.runtime.activity_max_visible,
        console=activity_console,
    )
    listener = view.handle if mode != "off" else None
    with view:
        return asyncio.run(
            ProofMeshOrchestrator(
                config,
                mock_responders=mock_responders,
                activity_listener=listener,
            ).solve(problem, run_id=run_id)
        )


def _run_resume_with_activity(
    config: SystemConfig,
    *,
    run_id: str,
    mode: ActivityMode,
    mock_responders=None,
):
    config.runtime.activity_mode = mode
    view = ConsoleActivityView(
        language=config.runtime.output_language,
        mode=mode,
        max_items=config.runtime.activity_max_visible,
        console=activity_console,
    )
    listener = view.handle if mode != "off" else None
    with view:
        return asyncio.run(
            ProofMeshOrchestrator(
                config,
                mock_responders=mock_responders,
                activity_listener=listener,
            ).resume(run_id)
        )


@app.command()
def solve(
    problem: Path = typer.Argument(..., exists=True, dir_okay=False, readable=True),
    config: Path = typer.Option(
        ..., "--config", "-c", exists=True, dir_okay=False, readable=True
    ),
    run_id: Optional[str] = typer.Option(
        None, help="Optional deterministic run directory name."
    ),
    json_output: bool = typer.Option(
        False, "--json", help="Print the full RunResult JSON."
    ),
    activity: Optional[str] = typer.Option(
        None,
        "--activity",
        help="Live progress timeline: off, compact, or detailed. Defaults to runtime.activity_mode.",
    ),
) -> None:
    """Solve a problem from a UTF-8 text file using the configured API-key agents."""
    cfg = load_config(config)
    _configure_logging(cfg.runtime.log_level)
    mode = _resolve_activity_mode(cfg, activity, json_output=json_output)
    text = problem.read_text(encoding="utf-8")
    result = _run_solver_with_activity(cfg, text, run_id=run_id, mode=mode)
    if json_output:
        console.print_json(
            json.dumps(result.model_dump(mode="json"), ensure_ascii=False)
        )
        return
    verdict = (
        result.final_verification.verdict.value if result.final_verification else "none"
    )
    timeline = (
        str(Path(result.run_directory) / "reports" / "activity_timeline.md")
        if cfg.runtime.activity_persist
        else "disabled"
    )
    console.print(
        Panel.fit(
            f"status: [bold]{result.status.value}[/bold]\n"
            f"final verification: {verdict}\n"
            f"calls: {result.total_calls}\n"
            f"tokens: {result.total_usage.total_tokens}\n"
            f"run directory: {result.run_directory}\n"
            f"activity timeline: {timeline}",
            title="MathProofMesh",
        )
    )
    if result.final_proof:
        console.print("\n[bold]Answer[/bold]\n" + result.final_proof.answer)


@app.command()
def resume(
    run_id: str = typer.Argument(..., help="Existing run directory name to resume."),
    config: Path = typer.Option(
        ..., "--config", "-c", exists=True, dir_okay=False, readable=True
    ),
    json_output: bool = typer.Option(
        False, "--json", help="Print the full RunResult JSON."
    ),
    activity: Optional[str] = typer.Option(
        None,
        "--activity",
        help="Live progress timeline: off, compact, or detailed.",
    ),
) -> None:
    """Resume from the latest stage snapshot and verified proof checkpoint."""
    cfg = load_config(config)
    _configure_logging(cfg.runtime.log_level)
    mode = _resolve_activity_mode(cfg, activity, json_output=json_output)
    result = _run_resume_with_activity(cfg, run_id=run_id, mode=mode)
    if json_output:
        console.print_json(
            json.dumps(result.model_dump(mode="json"), ensure_ascii=False)
        )
        return
    verdict = (
        result.final_verification.verdict.value if result.final_verification else "none"
    )
    console.print(
        Panel.fit(
            f"status: [bold]{result.status.value}[/bold]\n"
            f"resumed: {result.resumed}\n"
            f"resume checkpoint: {result.resumed_from_checkpoint_id or 'none'}\n"
            f"final verification: {verdict}\n"
            f"calls recorded: {result.total_calls}\n"
            f"run directory: {result.run_directory}",
            title="MathProofMesh resume",
        )
    )
    if result.final_proof:
        console.print("\n[bold]Answer[/bold]\n" + result.final_proof.answer)


@app.command()
def demo(
    run_root: Path = typer.Option(Path("runs"), help="Directory for demo artifacts."),
    continuation: bool = typer.Option(
        False,
        "--continuation",
        help="Exercise verified proof checkpoints and segmented continuation.",
    ),
    activity: Optional[str] = typer.Option(
        None,
        "--activity",
        help="Live progress timeline: off, compact, or detailed.",
    ),
) -> None:
    """Run a deterministic end-to-end smoke test without external API calls."""
    cfg = build_demo_config(str(run_root))
    if continuation:
        cfg.continuation = ContinuationConfig(
            enabled=True,
            segments_per_explore_call=1,
            max_segments_per_path=4,
            max_failover_agents=1,
            process_resume_enabled=True,
        )
        cfg.budget.max_total_calls = max(cfg.budget.max_total_calls, 80)
    _configure_logging(cfg.runtime.log_level)
    mode = _resolve_activity_mode(cfg, activity)
    problem = "Prove that for every positive integer n, 1+3+...+(2n-1)=n^2."
    result = _run_solver_with_activity(
        cfg,
        problem,
        run_id="demo_sum_of_odds",
        mode=mode,
        mock_responders=demo_responders(cfg),
    )
    timeline = (
        str(Path(result.run_directory) / "reports" / "activity_timeline.md")
        if cfg.runtime.activity_persist
        else "disabled"
    )
    console.print(
        Panel.fit(
            f"status: [bold]{result.status.value}[/bold]\n"
            f"calls: {result.total_calls}\n"
            f"run directory: {result.run_directory}\n"
            f"activity timeline: {timeline}",
            title="Deterministic demo",
        )
    )
    if result.final_proof:
        console.print(result.final_proof.answer)


@app.command()
def probe(
    config: Path = typer.Option(
        ..., "--config", "-c", exists=True, dir_okay=False, readable=True
    ),
    completion: bool = typer.Option(
        False,
        "--completion",
        help="Also make one tiny billed chat-completion request per agent.",
    ),
) -> None:
    """Validate configured credentials and model visibility without printing any key."""
    cfg = load_config(config)
    _configure_logging(cfg.runtime.log_level)

    async def run_probe() -> list[dict[str, object]]:
        pool = AgentPool(cfg)

        async def inspect(agent: object) -> dict[str, object]:
            runtime = agent
            entry: dict[str, object] = {
                "agent": runtime.id,
                "provider": runtime.provider,
                "model": runtime.config.model,
            }
            try:
                if isinstance(runtime.client, DeepSeekClient):
                    models = await runtime.client.list_models()
                    entry["credential_ok"] = True
                    entry["model_visible"] = runtime.config.model in models
                else:
                    entry["credential_ok"] = None
                    entry["model_visible"] = None
                    entry["note"] = (
                        "model-list probe is implemented for DeepSeek agents"
                    )

                if completion:
                    response = await runtime.call(
                        [
                            {
                                "role": "system",
                                "content": "Return one JSON object only.",
                            },
                            {
                                "role": "user",
                                "content": '{"probe":"Reply with {\\"ok\\":true}."}',
                            },
                        ],
                        temperature=0.0,
                        max_output_tokens=2048,
                        json_mode=True,
                    )
                    entry["completion_ok"] = bool(response.text.strip())
                    entry["input_tokens"] = response.input_tokens
                    entry["output_tokens"] = response.output_tokens
            except (
                Exception
            ) as exc:  # The command reports per-agent failures without leaking secrets.
                entry["credential_ok"] = False
                entry["error_type"] = type(exc).__name__
                entry["error"] = str(exc)[:240]
            return entry

        try:
            return await asyncio.gather(*(inspect(agent) for agent in pool.agents))
        finally:
            await pool.aclose()

    results = asyncio.run(run_probe())
    console.print_json(json.dumps(results, ensure_ascii=False))


@app.command("serve")
def serve_command(
    config: Path = typer.Option(
        ..., "--config", "-c", exists=True, dir_okay=False, readable=True
    ),
    host: str = typer.Option("127.0.0.1"),
    port: int = typer.Option(8000, min=1, max=65535),
) -> None:
    """Start the optional HTTP API, including the SSE activity stream."""
    try:
        import uvicorn
    except ImportError as exc:
        raise typer.BadParameter(
            "Install with: pip install 'mathproofmesh[server]'"
        ) from exc
    os.environ["MATHPROOFMESH_CONFIG"] = str(config.resolve())
    uvicorn.run("mathproofmesh.server:create_app", factory=True, host=host, port=port)


if __name__ == "__main__":
    app()
