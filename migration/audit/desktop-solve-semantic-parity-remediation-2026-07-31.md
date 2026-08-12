# Desktop solve semantic-parity remediation audit

Date: 2026-07-31

## Scope

This remediation addresses the production desktop solve path, not merely dormant classes or unit
fixtures. "Retained" below means that the capability is reachable from the live desktop solve
coordinator, contributes events and persisted evidence, and is restored across resume when its
policy gate or runtime trigger applies. Expensive conditional mechanisms are not forced to run on
every easy problem; their omission from an individual topology must be justified by the active
gate, trigger, or budget rather than by missing integration.

## Parity result

| Python solve stage | Java desktop production result | Status |
|---|---|---|
| Frozen statement, hash, and configuration preflight | Frozen evidence and sandbox/provider preflight are the first workflow cursor stage. | Retained (production wired) |
| Triage and problem classification | Planner triage is persisted and feeds strategy generation. | Retained (production wired) |
| Strategy generation, diversity, and admission | Candidate strategies are diversified, scored, admitted, and projected as strategy blueprints. | Retained (production wired) |
| Route admission and Prover/Skeptic/Tool/Referee teams | Every admitted route receives the required role team; Tool Specialist is assigned when computation is requested. | Retained (production wired) |
| Isolated parallel exploration | Admitted routes execute in isolated virtual-thread tasks and merge through controlled route state. | Retained (production wired) |
| Multi-segment continuation and Working Delta | Routes can execute multiple continuation segments, carry bounded computation context, and persist working deltas. | Retained (production wired) |
| Independent route review | Structural, detailed, team, tool, replay, and escalation checks are independent of route authors. | Retained (production wired) |
| Committed checkpoint, CAS, rollback, and branching | Route commits use checkpoint compare-and-set; rejected commits retain rollback and branch evidence. | Retained (production wired) |
| Claim extraction, lemma memory, proof graph, and proof debt | Live routes populate all four stores; structured projections are written for the desktop and API. | Retained (production wired) |
| Cross-route broker, deduplication, receipts, and utility | Typed messages pass through deduplication, semantic receipts, and downstream-utility gates. | Retained (production wired) |
| Goal/Plan/Failure/Utility proof control | The proof-control facade now governs live blueprints, goal links, failures, utility, and control actions. | Retained (production wired) |
| Inspiration mechanisms, referee, budget, and outcome learning | Trigger detection, mechanism selection, assignment, referee, reservations, materialization, and outcome learning run in the live controller. | Retained (production wired) |
| Meta review, meta pivot, deep exploration, and stall recovery | Meta review and persistent strategist decisions can pivot, deepen, widen, cool down, abandon, or recover routes. | Retained (production wired) |
| WIDEN/DEEPEN/VERIFY/REVISE scheduling | The desktop solve is now a bounded policy loop rather than a fixed one-pass sequence. | Retained (production wired) |
| Synthesis | Synthesis consumes admitted, reviewed, and verified route evidence. | Retained (production wired) |
| Blind final review and validation escalation | Final validation includes structural, deterministic, blind, adversarial, computation-replay, optional cross-provider, and formalization coverage checks. | Retained (production wired) |
| Exact resume from committed state | Resume re-enters at the persisted workflow cursor and does not restart completed provider calls or materialize evidence twice. | Retained (production wired) |
| Docker/Python computation sandbox | Sandboxed computation remains bounded and now has independent deterministic replay audit evidence. | Retained (production wired) |

## Production evidence

- `DesktopSolveCoordinator` is the single live desktop orchestration entry point and persists the
  workflow cursor, route states, control decisions, review reports, and final validation evidence.
- `DesktopSolveCheckpoint` carries strategy blueprints, goal links, meta pivots, route deltas,
  failures, computation programs/audits, final reports, and formal coverage across interruption.
- `RunExecutionBackend`, `RunApiService`, and `DesktopRunManager` expose and forward real resume.
- The API proof-graph view reads the generated `structured/proof-graph.json` projection rather
  than synthesizing a placeholder node.
- The computation broker independently replays admitted deterministic computations before final
  acceptance.

The live projection set includes `proof-graph.json`, `lemma-memory.json`, `typed-memory.json`,
`message-store.json`, `strategy-blueprints.json`, `goal-links.json`, `meta-pivots.json`,
`computation-audits.json`, `formalization-coverage.json`, `final-validation-execution.json`, and
`final-review-reports.json`.

## Verification

- Current-tree full Maven wrapper verification: PASS on JDK 25.
- Unit, integration, Docker/PostgreSQL, computation sandbox security, SpotBugs/FindSecBugs,
  dependency policy, duplicate-class, coverage, performance, and frozen-authority gates: PASS.
- Docker sandbox preflight used server 29.6.2.
- Packaged application clean-start health check: PASS, version 0.8.0.
- Installed application health check from `C:\Program Files\MathProofMesh`: PASS.
- Installed desktop JAR SHA-256 matches the verified build:
  `e9899f5bc632cdb10b55f1063d6ca61951dea99c3042c44df06f45cb08620de9`.

## Distribution and installation

- Installer: `target/desktop-dist/MathProofMesh-0.8.0.exe`
- Installer SHA-256:
  `cd5976ddd3000b94d93d2396a9c00be64c2c44c3bb242f2d23eca0cff97e868c`
- Portable ZIP: `target/desktop-dist/MathProofMesh-0.8.0-windows-x64-portable.zip`
- Portable ZIP SHA-256:
  `bde75d6ce20d2fcf6b52c85ea8af231e7061064780d7dd2622989e15b91e71b5`
- Installed launcher: `C:\Program Files\MathProofMesh\MathProofMesh.exe`
- Public desktop shortcut target: `C:\Program Files\MathProofMesh\MathProofMesh.exe`

The old same-version product was removed before installing this build because Windows Installer
correctly rejected an in-place same-version bundle upgrade. The application-data directory was not
removed: three protected configuration files and six run directories remain present under the
current user's local application data. No credential material is recorded in this audit.
