# v0.8.3–v1.0 综合改进：代码映射与实施记录

依据《MathProofMesh 综合改进总纲（合并版）》实施。本文件记录每个工作包落在真实代码的哪里、与总纲的偏差及理由。全部改动均有测试覆盖（tests/test_v083_*、tests/test_v084_*、tests/test_v09_*）。

## Phase A — v0.8.3a 入口链最小修复

| 能力 | 实际落点 | 说明 |
|---|---|---|
| 多语言四态语义门 | proof_control/semantic_quality.py（重写 assess）；proof_control/models.py `ObligationSemanticVerdict` | 中文量词/关系词表、Unicode ≤≥≠≡∣、LaTeX 命令、CJK bigram 分词、隐式量词谓词；解析视图额外做 NFKC 和 ≦/≧ 兼容折叠，但对象正文与内容哈希仍保持 NFC；ACCEPT/NEEDS_NORMALIZATION/SEARCH_OR_PROCESS_TASK/REJECT；缺元数据→可修复不隔离；无 first step 不再是拒绝理由 |
| direct target 全候选 | proof_control/strategy_blueprint.py（direct_target_node_ids=全部 path 节点） | controller._binding_from_blueprint 原有迭代逻辑自动获得降级链 |
| bottleneck 不成为引理 | strategy_blueprint.py compile（语义门 ACCEPT 才入节点） | 修正总纲 1.2-1：key_original_step 本就不是节点 |
| 边来源标记 | models.py BlueprintEdge.origin="list_order_guess" | WP5 最小版；完整边审查留待需要时 |
| BLOCK 回滚（替代 Draft Store 全量事务） | proof_graph/store.py `retract_tentative_obligation`；controller `_retract_blocked_blueprint`（三处 BLOCK 路径接线） | 修正总纲 1.2-3：blueprint 级暂存层已存在（state.blueprint_*），真实缺陷是准入后无回滚；单线程流水线下回滚等效原子性 |
| 可操作拒绝反馈 | controller._binding_from_blueprint（逐候选：needs_normalization+缺失项+原文；标注"系统解析问题，保持数学策略"） | 总纲 1.5 反馈可行动化 |
| main goal provenance | controller compile（main_goal 节点首写保留） | 修正总纲 1.2-2：仅溯源记录污染 |
| 0-route 熔断+诚实终态 | orchestrator `_classify_admission_starvation` / `_admission_termination_reason`、SolveState.termination_reason、自适应循环入口跳过、checkpoint/resume 与 RunResult/ResearchProgressReport | repairable ≥80% → systemic_semantic_failure；修复与再生均耗尽时固定为 `NO_ROUTES_ADMITTED(repair_exhausted)`；报告不再把门故障说成"开放证明义务" |
| 报告草稿分离 | orchestrator research report（open= open/blocked；tentative 单列 draft 计数） | WP10 最小版 |

## Phase B — v0.8.3b 全量准入机制

| 能力 | 实际落点 |
|---|---|
| 批量 LLM 规范化 | prompts.normalize_statements + schemas.PropositionNormalizationBatch + orchestrator._attempt_semantic_repair_admission（structural_verifier 角色执行，先于 planner 再生） |
| 入口有界修复循环 | _initial_strategies：admission 失败 → 按最多 12 节点分批规范化（每轮最多 2 调用）→ 再准入 → 仍失败才执行一次带类型审计任务的 planner 再生；planner 失败不以 generic fallback 掩盖 |
| 预算契约 | config RouteAdmissionControlConfig.semantic_repair_enabled/max_semantic_repair_calls(2)/semantic_repair_budget_fraction(0.1) + BlueprintReviewControlConfig（每轮 2、每批 12、最多 2 修复轮）；调用记 breadth 桶；tests/test_v083_semantic_repair.py 静态断言 |
| 任务审计 | proof_control/tasks.create_admission_review_task 支持 blueprint_review/repair_direct_target/edge_review/generate_plan/batch_repair，稳定幂等 ID、无 +4 过期、显式终态；54 节点回归测试；WakeConditionKind.ROUND_ADVANCED 已接入 WakeScheduler |
| goal_alignment 工作包 | CLAIM_WEAKER 与 SAME 同分、不再作为 framing/scope_mismatch；controller 将缺失的“弱命题⇒目标”物化为 open LEMMA bridge，写入 required/remaining obligation IDs，不写 Fact、不关闭义务；SynthesisReadinessGate 不再因 CLAIM_WEAKER 阻塞 |

四条从未接线且绕过统一预算/任务账本的 `review_ambiguous*` 方法已删除；实际复审统一走 `ExecutableTaskController` 与 orchestrator 的受限 dispatcher 路径。

## Phase C — v0.8.4 确定性拒绝簇与验证可信度（12 项全部落实）

1. attempt 依赖守卫 known 集并入 checkpoint.verified_claim_ids + memory.verified()（orchestrator `_committed_dependency_ids`）。
2. delta 守卫接受 prompt 实际注入的跨路径 ID（continuation.local_delta_verification shared_dependency_ids；调用点传 lemma 库 claim id + broker message id）。
3. memory alias 表 + 携证据升级 + mark_claim_checkpoint_verified 缺失 ID 记录性 no-op（原 KeyError 崩溃）；已提交步骤注册表使跨段 step 依赖合法。
4. resume 先载 lemma_memory 再载 stage 快照（REJECTED 不再复活）。
5. claim 生命周期 PASS 端只提升 checked_dependencies 覆盖或已 delta 级验证的 claim；REJECTED 前置守卫防复活防崩溃。
6. 截断（finish_reason=length 且非空）不再进入 JSON 修复；修复 prompt 附题面与阶段标签（agents.py）。
7. gather 重抛 BudgetExhaustedError（_raise_if_provider_circuit 扩展）。
8. 终审硬排除获胜链全部作者（attempt.agent_id+failover_chain+checkpoint 链）；AgentPool `strict_exclude` 禁止最后回退复用作者，池不足时终审明确 UNCERTAIN 并记 `final_verification_author_exclusion_exhausted`。
9. Lean：sorry/admit/axiom 扫描后才 CERTIFIED；编译失败→INCONCLUSIVE 非反例；默认必须在 digest-pinned Docker 镜像中以 network=none/read-only/cap-drop/no-new-privileges 执行；未配置沙箱时 fail closed；graph 无效证书同修。
10. 单轮无动作改为复用 `scheduler.global_no_progress_rounds_before_stop`（默认 3），solve/resume 一致。
11. 定理准入：structural_verify 强制第 7 条检查项（平凡化+hard_constraints）；确定性 `_hard_constraint_violations`（不得引用 X → external 依赖含 X 即 CRITICAL）。
12. SSE 断连：部分 CoT 只冲刷入本地 reasoning trace；异常另外携带有界的公开输出前缀及哈希，AgentRuntime 的同 key 重试将该公开前缀作为非证据恢复上下文，绝不前馈私有 reasoning。

## Phase D — v0.9 能力解锁

1. 跨段连续性：ProofDelta.working_notes / ProofCheckpoint.working_notes+proof_sketch / ProofAttempt.proof_sketch（哈希白名单外）；merge/genesis/attempt_from_checkpoint 传递；explore 要求填 sketch、continuation 回灌并要求更新 notes。
2. 簿记回填 + schema 瘦身：delta 六个簿记字段服务端覆写；_schema 剥离 pydantic title；两阶段输出 config 门控（agents.py，two_phase_output）。
3. 策略技巧引出：_TECHNIQUE_MENU 按 problem_kind 注入；等价重述前置；key_original_step 强制；olympiad/research 难度追加一次负约束独立采样（forbidden_mechanisms）。
4. 先算后猜：bounded_typed_probe fast path（新版已有）；`max_compute_cycles_per_segment` 默认保持 1，仅允许配置显式选择额外计算周期。
5. 算力缩放：BudgetConfig.scale_budget_with_difficulty 为显式 opt-in，默认关闭；启用时 olympiad/research 使用调用×2、轮+2、段×2，token/费用上限不缩放。缩放以构造期基线重算，重复调用与 resume 均幂等，并把请求配置、有效配置和缩放记录写入审计状态；停滞≥2 轮 deepen 换 explorer；finish reserve 无候选时本就不占用（核实无需改）。
6. 验证修复引擎：structural FAIL（无确定性守卫问题）仍跑一次 detailed；细审一致 PASS 时降级为可修复 UNCERTAIN 而非埋掉；delta UNCERTAIN 一次仲裁；PASS 无 checked_dependencies 自动降级；devil's advocate 条款入细审 prompt；反例守卫 ID 绑定+token 重叠兜底（ExperimentResult.target_claim_id）。
7. 负知识共享：memory.rejected()+跨路径 dead_ends 注入 explore/continuation prompt（_negative_knowledge_context）。

## Phase E — v1.0 结构升级

1. 检查点树化（最小可行）：除 rollback 外，store 提供任意已保留节点 `activate_proof_checkpoint`、叶节点 `list_proof_checkpoint_frontier` 与确定性 `select_best_proof_checkpoint`；默认列表仍只返回 active lineage，审计可包含所有分支；作者冲突回滚仍必须由独立 verifier 定位具体 step_id 后确认。
2. 形式化 IR：ProofStep.step_type（assumption_intro/discharge、case_split/close、definition/construction）+branch_label；active_assumptions None=继承/[]=清空；subgoal 账本对账；`formalization_coverage` 只统计绑定到精确 step_id、独立验证的 FORMAL_CERTIFICATE，并写入 RunResult/ResearchProgressReport。
3. 异构模型：config.example.yaml 异构说明；信任分去从众（只奖励证据支撑的 FAIL、惩罚零痕迹 PASS）；devil's advocate 见 D6。
4. 数学感知检索（topology 符号归一化+α-等价+确定性本地 feature-hash embedding，与结构 Jaccard 混合）与计算工具箱扩容（real_inequality/数论箱/共圆等）——见 tests/test_v09_math_similarity.py、tests/test_v09_toolbox.py。

## 审计加固

- subgoal 账本逐项对账：删除多个目标但只声明一个 `completed_subgoal` 必须 FAIL。
- G→G 空桥梁识别覆盖 Unicode、LaTeX、英文和中文的蕴含、等价表达。
- 计算缓存 runtime fingerprint 使用当前软件版本，旧版本缓存键不会跨版本命中。
- LemmaMemory 的 alias 与 committed step IDs 写入独立 runtime sidecar；旧 `lemma_memory.json` 格式保持不变，旧 checkpoint 缺少 sidecar 时按空状态兼容恢复。

## 已知限制（诚实清单）

- `enable_lean` 默认仍为 False；启用时必须另行提供 digest-pinned `lean_sandbox_image` 和可用 Docker。未实现完整 Lean 证明生成器。
- 两阶段输出默认关闭，需真实 API 评估后再默认开启。
- 完整 blueprint 边逐边模型语义审查与 admission PREPARED/COMMITTING 全事务机未建；当前采用有界任务、准入后物化/回滚和 edge origin 审计。
- 已提供任意 checkpoint 激活、frontier 与 best 选择原语，但没有实现完整 MCTS 或大拆 Orchestrator。
- 本轮只运行 Mock/离线工具测试；按硬约束未调用真实 Provider API。
