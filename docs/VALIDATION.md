# 可复现验证记录

**验证日期：2026 年 7 月 19 日。** 本记录严格区分源码静态质量、确定性 Mock/HTTP Mock 行为、安装产物验证和真实供应商联调。前三类已经执行；构建过程中未使用用户的真实 API key 发起付费请求。

## 1. 一键复验

安装开发依赖后运行：

```bash
bash scripts/validate.sh
```

脚本依次执行：

```text
compileall(src + tests)
→ Ruff lint
→ Ruff format check
→ pytest
→ deterministic continuation demo
```

演示不访问外部 API。它完整经过题目冻结、策略生成、隔离探索、`ProofDelta`、独立检查点验证、`ProofCheckpoint` 提交、Claim 传递、候选验证、Meta-review、综合和最终审计。

## 2. 最终源码验证结果

| 检查 | 结果 |
|---|---:|
| `python -m compileall -q src tests` | PASS |
| `ruff check .` | PASS |
| `ruff format --check .` | PASS |
| `PYTHONPATH=src pytest -q` | **60 passed, 1 skipped** |
| continuation deterministic demo | `verified`，25 calls，29,818 tokens |
| demo 证明检查点 | 3 条路径，共 6 个 checkpoint（3 个 genesis + 3 个已验证完成段） |
| DeepSeek 非流式 HTTP Mock | PASS |
| DeepSeek SSE HTTP Mock | PASS |
| SSE `[DONE]` 缺失守卫 | PASS |
| 请求 usage 但尾部 usage 摘要缺失守卫 | PASS |
| `reasoning_content` 增量脱敏 | PASS |
| YAML 配置解析 | PASS |
| 源码秘密扫描 | 源码、配置和文档中真实 key 与通用长 `sk-*` 模式均为 0 匹配 |

演示定理为前 `n` 个正奇数之和等于 `n^2`。选择简单定理是为了使状态机、故障恢复和审计链测试可重复，不能据此声称系统已经解决研究级开放问题。

## 3. 断线、接力与进程恢复验证

### 3.1 已完成运行的幂等恢复

先完成一次 continuation demo，再对同一 `run_id` 调用 `resume`：

```text
首次：verified，22 calls，25,969 tokens，6 checkpoints
恢复：verified，22 calls，25,969 tokens，6 checkpoints
```

最终审计已经通过时，恢复不会重复调用模型，也不会重复计费本地已记录的 token。

### 3.2 未完成检查点后的继续推理

测试构造了两段证明：第 1 段只完成连续平方差恒等式，第 2 段完成望远镜求和。首次运行将总调用上限设为 4，并关闭阶段级快照，以模拟更苛刻的中断：

```text
中断前：budget_exhausted，4 calls
最新恢复点：segment 1，proof_complete=false
阶段快照：不存在
```

随后把总调用上限提高至 40，并对同一 `run_id` 执行 `resume`：

```text
恢复后：verified，累计 13 calls
恢复来源：原 segment-1 committed checkpoint
新状态：segment 2，proof_complete=true
```

这同时验证了：

- 没有阶段快照时从 `ProblemContract` 和独立持久化的 Strategy/LemmaMemory 恢复；
- 未完成路线从自己的 `latest.json` 继续，而不是重新生成第 1 段；
- 预算、usage 和 Activity 序号跨进程累计；
- 已提交父检查点保留在不可变链中。

### 3.3 跨 API-key/Agent 接力

集成测试令首选 Explorer 在 `ProofDelta` 请求中抛出网络断连，备用 Explorer 返回合法增量。结果为：

```text
primary key 正常调用级重试耗尽
→ backup key 接收同一 checkpoint + current goal
→ 独立 Reviewer 验证
→ checkpoint source_agent_id=backup
→ failover_chain=[primary, backup]
```

还验证了：

- 401/403 在原 key 上不重复请求，但可以切换另一个 key；
- 连续失败的 key 进入短暂冷却，后续调度优先健康 Agent；
- checkpoint Reviewer 的首选和故障转移候选均排除当前 Delta 作者；
- Synthesizer 连接失败时可切换备用 Agent，最终审计继续排除实际完成综合的 Agent；
- 所有备用 Agent 均失败时，原 committed checkpoint 不被覆盖。

## 4. 检查点与信息完整性测试

自动化测试覆盖：

- `ProblemContract`、Claim 和 `ProofCheckpoint` 内容哈希；
- checkpoint 父节点必须等于当前 `latest.json`；
- segment 必须严格加一；
- problem/path/strategy 身份不可改变；
- proof step ID、Claim ID 不可重复；
- 新步骤只能依赖已提交对象或同一 Delta 中更早的步骤；
- Claim 自依赖、前向依赖和未验证依赖会被本地守卫拒绝；
- Reviewer FAIL/UNCERTAIN 时 Delta 只能进入 rejected 目录；
- prompt 使用内容寻址归档，重复阶段不会覆盖旧提示词；
- 比阶段快照更新的 `lemma_memory.json` 会在恢复时被重新加载；
- 被截断的 SSE、半截 JSON 和私有 `reasoning_content` 不会成为恢复点。

## 5. 调度器回归验证

新增回归测试覆盖以下通用情形：

- 路线覆盖率按 `current_paths / max_paths` 计算，而不是按初始路线数计算；
- 所有已审查路线均失败、仍有容量且预算允许时，本轮至少保留一个 `widen`；
- 达到 `max_paths` 或最终修订储备不足时，不越界拓宽；
- Structural FAIL 会进入路径统计并降低有效进展；
- 高置信度策略级失败不会因证明步骤较多而持续获得 `deepen`；
- 执行级、计划级与未知失败只获得配置允许的修补次数；
- 重复失败后进入可配置冷却；
- 动作成本根据新增路线数、continuation 段数、Reviewer、Claim 提取、验证和 Meta-review 动态计算；
- 最终预算储备根据 `reserve_revision_cycles` 与 `max_revisions` 计算；
- 调度产物记录每个候选动作的排名、分数、预计成本、未选原因和预算阻断原因。

对应自动化结果：`60 passed, 1 skipped`。确定性完整演示结果为 `verified`，25 calls，29,818 tokens。所有路线数、动作数、修补次数和调用预算均来自配置，不绑定某一道题。

## 6. 终审边界与修订回归验证

新增测试明确区分形式要求与数学条件：

- 标准命名定理不需要提供书目链接或页码，但必须写出准确调用形式，并从已有步骤显式验证全部假设；
- 缺失定理适用条件时，最终结构门返回执行级 FAIL，不能直接形成 `verified`；
- 预算允许时，缺口进入一次定向修订；修订稿必须重新执行结构审计和详细审计；
- 修订前的 PASS 不会复用，修订动作本身也不会自动升级最终状态；
- 策略级错误不通过继续润色同一证明修复；
- 确定性工具找到反例时仍覆盖模型 PASS。

集成回归模拟了“最终证明遗漏一个实际为真的定理假设”的情形。首次结构审计阻断详细审计；修订器补出显式推导；随后新的结构审计和详细审计均通过，最终状态才成为 `verified`。

## 7. 安装产物验证

v0.5.1 已在本地重新构建 Wheel 和 sdist。`dist/` 仍由 `.gitignore` 排除，因此 GitHub 源码提交不携带二进制构建产物。产物哈希不写回源码元数据，以免 sdist 因包含自身哈希记录而形成循环变化。

构建命令：

```bash
python -m hatchling build
```

结果：

| 产物 | 结果 |
|---|---:|
| `dist/mathproofmesh-0.5.1.tar.gz` | PASS |
| `dist/mathproofmesh-0.5.1-py3-none-any.whl` | PASS |
| wheel 安装到隔离 target | PASS |
| 从已安装 wheel 执行 continuation demo | `verified`，25 calls |
| wheel/sdist 通用长 `sk-*` 凭据模式扫描 | 0 匹配 |

## 8. GitHub CI

仓库包含 `.github/workflows/ci.yml`，对功能分支 push 和 Pull Request 执行：

```text
Python 3.11
→ pip install -e ".[dev,server]"
→ bash scripts/validate.sh
```

该工作流只运行确定性 Mock 测试，不读取 DeepSeek secrets，也不会产生真实模型费用。远程 CI 状态应以相应 GitHub commit/PR 页面为准。

## 9. 尚未实机验证的部分

本次构建没有使用聊天中提供的真实 DeepSeek key。尚需在用户本机验证：

- 五个 key 的当前有效性、余额、账户级并发限制和模型可见性；
- 真实 DeepSeek V4 Pro 对长 `ProofDelta` JSON Schema 的遵循率；
- 真实 SSE 长连接在本地网络、代理和防火墙下的稳定性；
- 真实 429/5xx 的 `Retry-After` 行为和供应商计费；
- 多 key 大规模并行下的实际成本、延迟和错误相关性。

真实联调应先执行 `mathproofmesh probe`，再使用 `config.deepseek-v4-pro.smoke.yaml`，最后切换正式配置。详见 [DEEPSEEK_V4_PRO.md](DEEPSEEK_V4_PRO.md)、[CHECKPOINT_RESUME.md](CHECKPOINT_RESUME.md) 与 [DEPLOYMENT.md](DEPLOYMENT.md)。
