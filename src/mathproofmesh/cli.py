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
from .schemas import GoalClarificationDecision, GoalClarificationRequest

app = typer.Typer(
    name="mathproofmesh",
    help="Sparse, verification-first multi-agent mathematical reasoning.",
    no_args_is_help=True,
)
console = Console()
activity_console = Console(stderr=True)
_VALID_ACTIVITY_MODES: set[str] = {"off", "compact", "detailed"}


def _apply_topology_overrides(
    config: SystemConfig,
    *,
    topology_mode: str | None,
    proof_graph_mode: str | None,
    disable_route_teams: bool,
    disable_cross_route: bool,
) -> SystemConfig:
    data = config.model_dump(mode="python")
    topology = data["topology"]
    if topology_mode is not None:
        if topology_mode not in {"legacy_sparse", "hierarchical_sparse"}:
            raise typer.BadParameter(
                "--topology-mode must be legacy_sparse or hierarchical_sparse"
            )
        topology["mode"] = topology_mode
        if topology_mode == "hierarchical_sparse":
            if not topology["proof_graph"]["enabled"]:
                topology["proof_graph"]["mode"] = "off"
            if not topology["inspiration"]["enabled"]:
                topology["inspiration"]["mode"] = "off"
    if proof_graph_mode is not None:
        if proof_graph_mode not in {"off", "shadow", "active"}:
            raise typer.BadParameter(
                "--proof-graph-mode must be off, shadow, or active"
            )
        topology["proof_graph"]["mode"] = proof_graph_mode
        topology["proof_graph"]["enabled"] = proof_graph_mode != "off"
    if disable_route_teams:
        topology["route_teams"]["enabled"] = False
    if disable_cross_route:
        topology["cross_route"]["enabled"] = False
    return SystemConfig.model_validate(data)


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
    interactive_clarification: bool = True,
):
    config.runtime.activity_mode = mode
    view = ConsoleActivityView(
        language=config.runtime.output_language,
        mode=mode,
        max_items=config.runtime.activity_max_visible,
        console=activity_console,
    )
    listener = view.handle if mode != "off" else None

    async def clarify(
        request: GoalClarificationRequest,
    ) -> GoalClarificationDecision:
        candidates = [
            request.assessment.recommended_statement,
            *(
                item.statement
                for item in request.assessment.alternative_interpretations
            ),
        ]
        activity_console.print(
            Panel.fit(
                "\n".join(
                    [
                        f"原题：{request.original_statement}",
                        *(
                            f"{index}. {statement}"
                            for index, statement in enumerate(candidates, start=1)
                        ),
                        f"{len(candidates) + 1}. 输入自定义规范化目标",
                    ]
                ),
                title="题意存在歧义，请确认规范化目标",
                border_style="yellow",
            )
        )
        selected = typer.prompt(
            "选择",
            type=int,
        )
        if not 1 <= selected <= len(candidates) + 1:
            raise typer.BadParameter("选择超出候选范围")
        if selected <= len(candidates):
            statement = candidates[selected - 1]
            candidate_index: int | None = selected - 1
        else:
            statement = typer.prompt("规范化目标").strip()
            candidate_index = None
        return GoalClarificationDecision(
            request_id=request.request_id,
            canonical_statement=statement,
            source="user_confirmed",
            selected_candidate_index=candidate_index,
        )

    with view:
        return asyncio.run(
            ProofMeshOrchestrator(
                config,
                mock_responders=mock_responders,
                activity_listener=listener,
                clarification_resolver=clarify if interactive_clarification else None,
            ).solve(problem, run_id=run_id)
        )


def _run_resume_with_activity(
    config: SystemConfig,
    *,
    run_id: str,
    mode: ActivityMode,
    resume_mode: str | None = None,
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
            ).resume(
                run_id,
                intervention=(
                    None
                    if resume_mode is None or resume_mode == "normal"
                    else resume_mode
                ),
            )
        )


def _apply_desktop_resume_context(
    config: SystemConfig,
    desktop_home: Path,
) -> SystemConfig:
    """Reuse a Desktop run root and its DPAPI-protected credentials."""

    from .desktop.configuration import DesktopConfigService
    from .desktop.paths import DesktopPaths
    from .desktop.security import CredentialVault
    from .desktop.settings import SettingsStore

    paths = DesktopPaths.discover(desktop_home)
    settings = SettingsStore(paths.settings_file).load()
    service = DesktopConfigService(
        paths,
        CredentialVault(paths.credentials_file),
    )
    return service.apply_runtime_context(config, settings)


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
    topology_mode: Optional[str] = typer.Option(None, "--topology-mode"),
    proof_graph_mode: Optional[str] = typer.Option(None, "--proof-graph-mode"),
    disable_route_teams: bool = typer.Option(False, "--disable-route-teams"),
    disable_cross_route: bool = typer.Option(False, "--disable-cross-route"),
) -> None:
    """Solve a problem from a UTF-8 text file using the configured API-key agents."""
    cfg = load_config(config)
    cfg = _apply_topology_overrides(
        cfg,
        topology_mode=topology_mode,
        proof_graph_mode=proof_graph_mode,
        disable_route_teams=disable_route_teams,
        disable_cross_route=disable_cross_route,
    )
    _configure_logging(cfg.runtime.log_level)
    mode = _resolve_activity_mode(cfg, activity, json_output=json_output)
    text = problem.read_text(encoding="utf-8")
    result = _run_solver_with_activity(
        cfg,
        text,
        run_id=run_id,
        mode=mode,
        interactive_clarification=not json_output,
    )
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
    run_report = str(Path(result.run_directory) / "reports" / "run_report.md")
    console.print(
        Panel.fit(
            f"status: [bold]{result.status.value}[/bold]\n"
            f"math status: {result.math_status.value}\n"
            f"execution status: {result.execution_status.value}\n"
            f"final verification: {verdict}\n"
            f"calls: {result.total_calls}\n"
            f"tokens: {result.total_usage.total_tokens}\n"
            f"run directory: {result.run_directory}\n"
            f"activity timeline: {timeline}\n"
            f"run report: {run_report}",
            title="MathProofMesh",
        )
    )
    if result.final_proof and result.math_status.value == "verified":
        console.print("\n[bold]Answer[/bold]\n" + result.final_proof.answer)
    elif result.research_progress_report:
        heading = (
            "研究进展"
            if cfg.runtime.output_language.lower().startswith("zh")
            else "Research progress"
        )
        console.print(
            f"\n[bold]{heading}[/bold]\n" + result.research_progress_report.summary
        )


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
    topology_mode: Optional[str] = typer.Option(None, "--topology-mode"),
    proof_graph_mode: Optional[str] = typer.Option(None, "--proof-graph-mode"),
    disable_route_teams: bool = typer.Option(False, "--disable-route-teams"),
    disable_cross_route: bool = typer.Option(False, "--disable-cross-route"),
    resume_mode: Optional[str] = typer.Option(
        None,
        "--resume-mode",
        help=(
            "Optional intervention: normal, reopen_with_pivot, "
            "reset_stagnation, or replay_stage."
        ),
    ),
    desktop_home: Optional[Path] = typer.Option(
        None,
        "--desktop-home",
        exists=True,
        file_okay=False,
        readable=True,
        help=(
            "Reuse a Desktop home directory for its run root, settings, "
            "learning state, and DPAPI-protected API credentials."
        ),
    ),
) -> None:
    """Resume from the latest stage snapshot and verified proof checkpoint."""
    cfg = load_config(config)
    if desktop_home is not None:
        try:
            cfg = _apply_desktop_resume_context(cfg, desktop_home)
        except RuntimeError as exc:
            raise typer.BadParameter(
                str(exc),
                param_hint="--desktop-home",
            ) from exc
    cfg = _apply_topology_overrides(
        cfg,
        topology_mode=topology_mode,
        proof_graph_mode=proof_graph_mode,
        disable_route_teams=disable_route_teams,
        disable_cross_route=disable_cross_route,
    )
    _configure_logging(cfg.runtime.log_level)
    mode = _resolve_activity_mode(cfg, activity, json_output=json_output)
    result = _run_resume_with_activity(
        cfg,
        run_id=run_id,
        mode=mode,
        resume_mode=resume_mode,
    )
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
    run_report = str(Path(result.run_directory) / "reports" / "run_report.md")
    console.print(
        Panel.fit(
            f"status: [bold]{result.status.value}[/bold]\n"
            f"math status: {result.math_status.value}\n"
            f"execution status: {result.execution_status.value}\n"
            f"resumed: {result.resumed}\n"
            f"resume checkpoint: {result.resumed_from_checkpoint_id or 'none'}\n"
            f"final verification: {verdict}\n"
            f"calls recorded: {result.total_calls}\n"
            f"run directory: {result.run_directory}\n"
            f"activity timeline: {timeline}\n"
            f"run report: {run_report}",
            title="MathProofMesh resume",
        )
    )
    if result.final_proof and result.math_status.value == "verified":
        console.print("\n[bold]Answer[/bold]\n" + result.final_proof.answer)
    elif result.research_progress_report:
        heading = (
            "研究进展"
            if cfg.runtime.output_language.lower().startswith("zh")
            else "Research progress"
        )
        console.print(
            f"\n[bold]{heading}[/bold]\n" + result.research_progress_report.summary
        )


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
    run_report = str(Path(result.run_directory) / "reports" / "run_report.md")
    console.print(
        Panel.fit(
            f"status: [bold]{result.status.value}[/bold]\n"
            f"calls: {result.total_calls}\n"
            f"run directory: {result.run_directory}\n"
            f"activity timeline: {timeline}\n"
            f"run report: {run_report}",
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
