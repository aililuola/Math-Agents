# MathProofMesh 五个真实 DeepSeek API-Key 数学竞赛 Benchmark

## Codex 严格执行、记录、打包与后续诊断说明书

文档版本：v1.0  |  日期：2026-08-23  |  被测基线：`ea94a34041fd32a4f94ecb1a3532ddc314430a47`

建议验证分支：`validation/014-five-key-math-olympiad-benchmark`

用途：在不修改 Issues 001–013 数学权威与质量门的前提下，使用五个真实 DeepSeek API key 对多个数学方向、由易到难的证明题进行冷启动、重复性和恢复性测试，并生成可供外部独立审计的结构化证据包。

重要：本文档是 Benchmark 执行规范，不包含标准解答。每道题的“评估清单”只允许在运行结束后用于提取和审计，严禁进入模型提示、RAG、系统消息或任何 Provider 请求。

## 0. 一页执行摘要

- 固定代码基线：`ea94a34041fd32a4f94ecb1a3532ddc314430a47`；新建 `validation/014-five-key-math-olympiad-benchmark`，不得修改 `main`。
- 五个 DeepSeek key 只通过环境变量注入，仓库、日志、checkpoint、报告和压缩包中不得出现明文 key、Authorization header 或可逆凭据片段。
- 每道题是独立冷启动 Run；不得跨题继承 Claim、Negative Knowledge、Proof Graph、Strategy、Budget、Checkpoint 或 Provider result。
- 同一道题的重复试验也必须使用全新 Run State，仅保持题目、代码、模型参数、预算档位一致。
- 标准套件 20 次；指定 5 题各增加 2 次冷启动重复；指定 4 题各增加 1 次受控恢复试验。完整计划共 34 次真实运行。
- 先执行 Smoke 5 题；出现安全泄漏、数学权威越界、重复 Provider 调用、预算漂移或 checkpoint 损坏时立即停止，不得继续烧预算。
- 模型只收到 canonical problem prompt；不得收到题号来源、难度、评估清单、已知方法、官方解答链接或历史失败原因。
- 输出只保存公开结构化证明材料，不保存隐藏思维链。探索树由 Route、Attempt、Claim、Obligation、Pivot 与验证事件重建。
- 最终提交一个经过 secret scan 的 ZIP，包含摘要、逐题证据、最终证明、指标 CSV/JSON、配置快照和 SHA-256；不包含 target、数据库原文件、缓存、原始密钥或未经清洗的网络日志。

## 1. Benchmark 研究目标

本 Benchmark 不只回答“最终有没有证明出来”，而是同时回答四类问题：

- 数学能力：系统在数论、组合、图论、不等式、代数、几何、函数方程、序列与博弈中，能够完成多少严格证明，能够形成多少可验证的关键引理。
- 十三项修复有效性：Root Goal、永久负知识、Claim/Attempt、研究 checkpoint、Proof Graph、Pivot、Strategy、角色隔离、Artifact Broker、可复现计算、Run State、并发恢复、预算与停止策略是否在真实 Provider 运行中保持不变量。
- 工程可靠性：五 key 是否持续并发、是否发生 key 饥饿、重复调用、结果丢失、恢复漂移、非确定性合并或费用结算重复。
- 失败归因：数学失败究竟来自模型未产生关键想法，还是来自 Route 准入、义务编译、跨路线综合、验证、工具、并发、恢复或预算停止的新缺陷。

## 2. 基线、范围与不可修改项

```text
REPOSITORY=<当前 Math-Agents 仓库>
BASELINE_COMMIT=ea94a34041fd32a4f94ecb1a3532ddc314430a47
NEW_BRANCH=validation/014-five-key-math-olympiad-benchmark
ISSUES_001_013_STATUS=CLOSED
BENCHMARK_VERSION=olympiad-5key-v1
```

Codex 开始前必须核验：

```bash
git fetch origin
git switch fix/013-evidence-aware-budget-token-stop-policy
git pull --ff-only origin fix/013-evidence-aware-budget-token-stop-policy
git rev-parse HEAD
git status --short

# 只有 HEAD 完全等于以下 SHA 且工作区为空时继续：
ea94a34041fd32a4f94ecb1a3532ddc314430a47

git switch -c validation/014-five-key-math-olympiad-benchmark
```

Benchmark 允许新增 runner、manifest、schema、sanitizer、reporter 和 validation-only exporter；除非确有可复现的缺失数据，否则不得修改生产数学权威。任何生产修改必须先有失败测试、单独提交、解释为何仅是观测/导出，不得改变 Claim、Fact、Obligation、Proof Graph、Receipt 或 Budget 判定。

### 2.1 禁止事项

- 不修改 `main`，不在 Issue 013 分支直接开发 Benchmark。
- 不更换模型、不增加 Agent 数、不提升 key 并发、不放宽预算或 Token 门来“提高成绩”。
- 不删除、跳过、重写或放宽 Issues 001–013 的旧测试和质量门。
- 不把官方解答、题目来源、难度、评估清单或历史失败报告注入模型。
- 不允许通用互联网搜索、外部题解检索、AoPS/IMO solution 下载、RAG 答案库或人工中途提示。
- 不记录或索取隐藏思维链；只记录系统本来允许持久化的公开结构化输出、证明文本和工具结果。
- 不上传 API key、`.env`、Authorization header、raw HTTP dump、未经清洗的 Provider response、数据库文件、target、cache、dist 或运行临时目录。

## 3. 五 Key 安全配置与角色轮换

### 3.1 环境变量

```bash
DEEPSEEK_API_KEY_A=<secret>
DEEPSEEK_API_KEY_B=<secret>
DEEPSEEK_API_KEY_C=<secret>
DEEPSEEK_API_KEY_D=<secret>
DEEPSEEK_API_KEY_E=<secret>
BENCHMARK_GLOBAL_COST_CAP_USD=<user-set hard cap>
BENCHMARK_ALLOW_REAL_PROVIDER=true
```

配置文件只能引用变量名，不得展开后提交。日志中 key 只以 `KEY_A` 至 `KEY_E` 出现。不得记录 key 前缀、后缀、长度、哈希或任何可用于关联真实密钥的值。

### 3.2 并发拓扑

```text
enabled_keys=5
per_key_max_concurrency=1
research_slots=4
coordination_slots=1
max_in_flight_provider_calls=5
allow_coordination_borrowing=false
```

同一题内部使用 4 个 Research 槽和 1 个 Coordination 槽；题目之间顺序执行，禁止不同题目共享同一时刻的五 key，以免 Run State、费用和限流相互污染。

### 3.3 Coordination key 轮换

标准试验按问题编号循环指定 Coordination key；其余四把 key 进入 Research 池：

```text
P01/P06/P11/P16 -> KEY_A coordination
P02/P07/P12/P17 -> KEY_B coordination
P03/P08/P13/P18 -> KEY_C coordination
P04/P09/P14/P19 -> KEY_D coordination
P05/P10/P15/P20 -> KEY_E coordination
```

指定重复题的 Trial 2、Trial 3 将 Coordination key 各向后轮换一位和两位。角色轮换只改变 key 与运行角色的绑定，不改变模型、提示、温度、top_p、Token 档位、预算或停止阈值。

### 3.4 网络与答案泄漏隔离

- 应用网络只允许访问配置中的 DeepSeek Provider endpoint 和运行所需的本地 PostgreSQL/Temporal 服务。
- 禁用模型可调用的 web search、浏览器、远程代码库、外部文献检索和官方 solution URL。
- Benchmark 构建阶段可以保存题目来源元数据；真实 Run 的 Provider prompt 只能包含题目文本和系统既有通用求证协议。
- runner 必须计算 `problem_prompt_sha256` 和实际 Provider 首次请求的规范化题目哈希，二者不一致则该 Run 无效。

## 4. 实验设计与运行矩阵

### 4.1 四个难度层

| 层级 | 题目 | 目标 | 推荐硬上限（不得自动增加） |

|---|---:|---|---|

| S / Smoke | P01–P05 | 验证提示完整、基本 Route/Claim/Verifier、五 key 与打包链 | 18 calls；200k total tokens |

| C / Core | P06–P10 | 验证多引理推理、跨路线比较与规范化 | 32 calls；450k total tokens |

| A / Advanced | P11–P15 | 验证下降、极值、复杂几何、周期性与严格验证 | 56 calls；900k total tokens |

| X / Stress | P16–P20 | 验证历史失败、近期 IMO 难题、复杂博弈和析取型函数方程 | 88 calls；1.5M total tokens |

费用上限不得写死为本文档中的历史价格。Codex 必须从 Issue 013 的 immutable PricingSnapshot 计算每题最坏费用，并在任何真实调用前打印全套 34 次运行的最大费用估计。只有 `BENCHMARK_GLOBAL_COST_CAP_USD` 足够且用户已显式设置时才允许开始。

### 4.2 完整运行数量

```text
Standard cold-start runs:      20
Additional replication runs:     10  (P09,P12,P13,P15,P16 each +2)
Controlled recovery runs:         4  (P11,P17,P19,P20 each +1)
TOTAL REAL RUNS:                  34
```

### 4.3 阶段门

- Gate 0：先跑当前完整离线验证和 fake-provider Benchmark harness 测试；任何失败不得调用真实 API。
- Gate 1：P01–P05 全部完成、证据包完整且硬不变量为零后，才能进入 Core。数学题可以答错，但系统性违例不能继续。
- Gate 2：P06–P10 后检查 Provider error rate、预算结算、checkpoint、secret scan 和 Run 隔离。
- Gate 3：P11–P15 后检查高级题是否产生非空 Proof Graph、明确义务和可信 stop reason。
- Gate 4：只有前三层无阻断系统缺陷，才执行 P16–P20 及额外重复/恢复。

### 4.4 冷启动与受控恢复

- 标准和重复试验：全新 Run ID、全新 checkpoint 目录、全新数据库 run namespace；不得载入其他试验的任何数学状态。
- 恢复试验只在 durable boundary 中断，不在真实 Provider 调用处于不确定状态时故意杀进程。硬崩溃的 10 个边界继续由 fake-provider 专项测试负责。
- P11：在首个 `RESULT_DURABLE` 后停止并恢复。
- P17：在 `ALL_SETTLED` 后、稳定 merge 前停止并恢复。
- P19：在 `MERGE_PREPARED` durable 后、authority commit 前停止并恢复。
- P20：在 checkpoint v22 原子写入后完全重启 Desktop/Temporal，再继续。
- 恢复前后记录 task/result/lease/budget/zero-gain/authority hashes；恢复后重复 Provider 调用必须为零。

## 5. 题库总览

| ID | 层级 | 方向 | 难度 | 来源类型 | 重复/恢复 |

|---|---|---|---:|---|---|

| P01 | S / Smoke | 数论 | 1/5 | 经典校准题 | 标准冷启动 1 次 |

| P02 | S / Smoke | 组合与图论 | 1/5 | 经典校准题 | 标准冷启动 1 次 |

| P03 | S / Smoke | 不等式与代数 | 1/5 | Benchmark 自编校准题 | 标准冷启动 1 次 |

| P04 | S / Smoke | 几何 | 1/5 | 经典校准题 | 标准冷启动 1 次 |

| P05 | S / Smoke | 函数方程与序列 | 2/5 | Benchmark 自编变体 | 标准冷启动 1 次 |

| P06 | C / Core | 数论 | 3/5 | Benchmark 自编变体 | 标准冷启动 1 次；Replication 阶段不重复本题 |

| P07 | C / Core | 组合与图论 | 2/5 | 经典校准题 | 标准冷启动 1 次 |

| P08 | C / Core | 不等式与代数 | 2/5 | 经典校准题 | 标准冷启动 1 次 |

| P09 | C / Core | 几何 | 3/5 | 经典校准题 | 标准冷启动 1 次 + 额外冷启动 2 次（共 3 次，角色轮换） |

| P10 | C / Core | 函数方程与序列 | 3/5 | 经典分析校准题 | 标准冷启动 1 次 |

| P11 | A / Advanced | 数论 | 5/5 | 公开奥赛校准题 | 标准冷启动 1 次 + 受控恢复试验 1 次 |

| P12 | A / Advanced | 组合与图论 | 4/5 | 经典奥赛定理 | 标准冷启动 1 次 + 额外冷启动 2 次（共 3 次，角色轮换） |

| P13 | A / Advanced | 不等式与代数 | 4/5 | 公开奥赛校准题 | 标准冷启动 1 次 + 额外冷启动 2 次（共 3 次，角色轮换） |

| P14 | A / Advanced | 几何 | 5/5 | 近期公开奥赛题 | 标准冷启动 1 次 |

| P15 | A / Advanced | 函数方程与序列 | 4/5 | 近期公开奥赛题 | 标准冷启动 1 次 + 额外冷启动 2 次（共 3 次，角色轮换） |

| P16 | X / Stress | 数论 | 5+/5 | 历史失败回归题 | 标准冷启动 1 次 + 额外冷启动 2 次（共 3 次，角色轮换）；另做历史运行对照，不重跑旧代码 |

| P17 | X / Stress | 组合与图论 | 5/5 | 近期公开奥赛题 | 标准冷启动 1 次 + 受控恢复试验 1 次 |

| P18 | X / Stress | 不等式、优化与博弈 | 5/5 | 近期公开奥赛题 | 标准冷启动 1 次 |

| P19 | X / Stress | 几何 | 5/5 | 近期公开奥赛题 | 标准冷启动 1 次 + 受控恢复试验 1 次 |

| P20 | X / Stress | 函数方程与序列 | 5/5 | 近期公开奥赛题 | 标准冷启动 1 次 + 受控恢复试验 1 次 |

难度是本 Benchmark 的内部层级，不代表官方难度评级。公开经典题用于校准；近期 IMO 题和 Benchmark 自编变体用于降低纯记忆解答对结果的影响；P16 用于与修复前历史失败直接比较。

## 6. 20 道 canonical problem prompts

执行规则：下面每个 `Canonical prompt` 代码块是该题唯一允许传入系统的题目内容。代码块外的标题、来源、难度和评估清单均为运行后元数据。Codex 必须把 prompt 单独写入 `problems/Pxx/problem.txt`，并用 allowlist 确保 Provider 请求中没有评估清单。

### P01 — 互素整数的三次型最大公因数

元数据（不得入模）：层级 `S / Smoke`；方向 `数论`；内部难度 `1/5`；来源 `Benchmark curated classic`；计划 `标准冷启动 1 次`。

Canonical prompt（唯一入模文本）：

```text
Let a and b be coprime positive integers. Prove that gcd(a+b, a^2-ab+b^2) belongs to {1,3}.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录每一次 gcd 等价变形及其方向是否可逆。
- 记录互素条件究竟在哪一步被使用，禁止用“显然可约去”替代证明。
- 记录是否完整处理 2、3 以及一般素因子的情形。
- 记录结论中两个可能值是否都给出可实现例子。

### P02 — 竞赛图中的有向 Hamilton 路

元数据（不得入模）：层级 `S / Smoke`；方向 `组合与图论`；内部难度 `1/5`；来源 `Benchmark curated classic`；计划 `标准冷启动 1 次`。

Canonical prompt（唯一入模文本）：

```text
A tournament is obtained by orienting every edge of a finite complete graph. Prove that every finite tournament contains a directed Hamiltonian path.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录归纳对象、归纳基与插入不变量。
- 若采用极长路径法，记录为何无法继续延长会导出覆盖全部顶点。
- 记录路径方向在每一步是否一致，禁止把无向路径误当成有向路径。

### P03 — 单位和条件下的乘积不等式

元数据（不得入模）：层级 `S / Smoke`；方向 `不等式与代数`；内部难度 `1/5`；来源 `Benchmark-authored`；计划 `标准冷启动 1 次`。

Canonical prompt（唯一入模文本）：

```text
Let a,b,c be positive real numbers with a+b+c=1. Prove that (1-a)(1-b)(1-c) >= 8abc, and determine all equality cases.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录每个因子正性及使用 AM-GM 的合法性。
- 记录三个局部不等式如何相乘，以及是否引入额外条件。
- 记录等号条件是否同时满足，而非逐项孤立判断。

### P04 — Gergonne 共点定理

元数据（不得入模）：层级 `S / Smoke`；方向 `几何`；内部难度 `1/5`；来源 `Gergonne theorem calibration`；计划 `标准冷启动 1 次`。

Canonical prompt（唯一入模文本）：

```text
In triangle ABC, the incircle touches BC, CA, and AB at D, E, and F, respectively. Prove that the cevians AD, BE, and CF are concurrent.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录所有切线段相等关系及点的对应。
- 记录 Ceva 定理所需的三个比值是否按同一方向书写。
- 记录三角形非退化与接触点位于边内部的条件。

### P05 — 整数域上的嵌套平移函数方程

元数据（不得入模）：层级 `S / Smoke`；方向 `函数方程与序列`；内部难度 `2/5`；来源 `Benchmark-authored`；计划 `标准冷启动 1 次`。

Canonical prompt（唯一入模文本）：

```text
Find all functions f: Z -> Z such that f(m+f(n)) = f(m)+n for all integers m and n.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录 f(0) 的推导，禁止未经证明假设 f(0)=0。
- 记录周期性、可加性、单射或满射中实际用到的性质。
- 记录候选函数回代验证，不能只给必要条件。

### P06 — 奇素数幂和商的公因子结构

元数据（不得入模）：层级 `C / Core`；方向 `数论`；内部难度 `3/5`；来源 `Benchmark-authored`；计划 `标准冷启动 1 次；Replication 阶段不重复本题`。

Canonical prompt（唯一入模文本）：

```text
Let p be an odd prime and let a,b be coprime positive integers. Prove that gcd(a+b, (a^p+b^p)/(a+b)) belongs to {1,p}.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录商为整数的理由。
- 逐素数记录 q=2、q=p 与 q not equal to p 的处理。
- 若使用 LTE，必须记录其全部适用条件；若不用 LTE，记录等价的素因子或同余论证。
- 记录为何 p 的指数不会超过 1。

### P07 — 六人中的三角同质关系

元数据（不得入模）：层级 `C / Core`；方向 `组合与图论`；内部难度 `2/5`；来源 `Ramsey R(3,3) calibration`；计划 `标准冷启动 1 次`。

Canonical prompt（唯一入模文本）：

```text
Among any six people, prove that there are either three people who know one another pairwise or three people no two of whom know one another. Assume acquaintance is symmetric.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录图模型中两种颜色与原关系的映射。
- 记录选定顶点后为何至少有三条同色关联。
- 记录两种分支是否穷尽。

### P08 — Nesbitt 不等式

元数据（不得入模）：层级 `C / Core`；方向 `不等式与代数`；内部难度 `2/5`；来源 `Nesbitt inequality calibration`；计划 `标准冷启动 1 次`。

Canonical prompt（唯一入模文本）：

```text
For positive real numbers a,b,c, prove that a/(b+c) + b/(c+a) + c/(a+b) >= 3/2, and determine all equality cases.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录分母正性。
- 记录所用 Cauchy、Titu、重排或代数恒等式的完整形式。
- 记录等号条件与正数域是否一致。

### P09 — Simson 线

元数据（不得入模）：层级 `C / Core`；方向 `几何`；内部难度 `3/5`；来源 `Simson line calibration`；计划 `标准冷启动 1 次 + 额外冷启动 2 次（共 3 次，角色轮换）`。

Canonical prompt（唯一入模文本）：

```text
Let P be a point on the circumcircle of a nondegenerate triangle ABC, with P distinct from A,B,C. Let X,Y,Z be the perpendicular feet from P to BC, CA, AB, respectively. Prove that X,Y,Z are collinear.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录有向角或普通角的约定。
- 记录由垂直关系得到的圆内接四边形。
- 记录最终共线判据，禁止依赖“从图上看”。
- 记录若使用坐标法，坐标归一化不得丢失一般性。

### P10 — 带局部有界条件的二次型函数方程

元数据（不得入模）：层级 `C / Core`；方向 `函数方程与序列`；内部难度 `3/5`；来源 `Quadratic functional equation calibration`；计划 `标准冷启动 1 次`。

Canonical prompt（唯一入模文本）：

```text
Find all functions f: R -> R satisfying f(x+y)+f(x-y)=2f(x)+2f(y) for all real x,y, under the additional assumption that f is bounded on some nonempty open interval.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录 f(0)、奇偶性与整数/有理数缩放关系。
- 记录局部有界条件在排除病态解时的确切作用。
- 禁止未经证明直接从有理数结论推广到实数。
- 记录所有候选解的回代验证。

### P11 — Vieta Jumping 整除平方问题

元数据（不得入模）：层级 `A / Advanced`；方向 `数论`；内部难度 `5/5`；来源 `IMO 1988 Problem 6`；计划 `标准冷启动 1 次 + 受控恢复试验 1 次`。

Canonical prompt（唯一入模文本）：

```text
Let a and b be positive integers such that ab+1 divides a^2+b^2. Prove that (a^2+b^2)/(ab+1) is a perfect square.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录最小反例或下降量的明确定义。
- 记录二次方程另一根的整数性、正性与严格变小。
- 记录所有边界情形和下降终止情形。
- 记录“商为平方”的最后闭环，不能停在经验性模式。

### P12 — Sperner 定理

元数据（不得入模）：层级 `A / Advanced`；方向 `组合与图论`；内部难度 `4/5`；来源 `Sperner theorem calibration`；计划 `标准冷启动 1 次 + 额外冷启动 2 次（共 3 次，角色轮换）`。

Canonical prompt（唯一入模文本）：

```text
Let F be a family of subsets of {1,2,...,n} such that no member of F contains another member of F. Prove that |F| <= binom(n, floor(n/2)).
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录采用 LYM、不相交链计数或对称链分解中的哪一条主路线。
- 记录每个集合被计数的次数以及除法是否合法。
- 记录奇偶 n 的中间层处理。
- 记录是否给出达到上界的构造。

### P13 — 根式分母循环不等式

元数据（不得入模）：层级 `A / Advanced`；方向 `不等式与代数`；内部难度 `4/5`；来源 `IMO 2001 Problem 2`；计划 `标准冷启动 1 次 + 额外冷启动 2 次（共 3 次，角色轮换）`。

Canonical prompt（唯一入模文本）：

```text
Let a,b,c be positive real numbers. Prove that a/sqrt(a^2+8bc) + b/sqrt(b^2+8ca) + c/sqrt(c^2+8ab) >= 1.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录齐次化或归一化是否保持一般性。
- 记录平方、开方、Cauchy 或切线估计时的不等号方向和正性。
- 记录循环结构是否被错误地当成对称结构。
- 记录等号情形。

### P14 — 内切圆平行切线与圆周角

元数据（不得入模）：层级 `A / Advanced`；方向 `几何`；内部难度 `5/5`；来源 `IMO 2024 Problem 4`；计划 `标准冷启动 1 次`。

Canonical prompt（唯一入模文本）：

```text
Let ABC be a triangle with AB<AC<BC. Let I and omega be its incenter and incircle. Let X be the point on line BC, different from C, such that the line through X parallel to AC is tangent to omega. Let Y be the point on line BC, different from B, such that the line through Y parallel to AB is tangent to omega. Let AI meet the circumcircle of ABC again at P. Let K and L be the midpoints of AC and AB. Prove that angle KIL + angle YPX = 180 degrees.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录 X、Y 的位置与相应切线分支，禁止默认图形顺序。
- 记录角度采用有向角还是 0–180 度普通角。
- 记录所有切线、内心、圆周角和中点关系的依赖链。
- 若采用坐标或复数法，记录退化分母和实数性检查。

### P15 — 频次递推序列的奇偶子列周期性

元数据（不得入模）：层级 `A / Advanced`；方向 `函数方程与序列`；内部难度 `4/5`；来源 `IMO 2024 Problem 3`；计划 `标准冷启动 1 次 + 额外冷启动 2 次（共 3 次，角色轮换）`。

Canonical prompt（唯一入模文本）：

```text
Let a1,a2,a3,... be an infinite sequence of positive integers, and let N be a positive integer. Suppose that for every n>N, an equals the number of occurrences of a(n-1) in the list a1,a2,...,a(n-1). Prove that at least one of the subsequences a1,a3,a5,... and a2,a4,a6,... is eventually periodic.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录递推条件仅从 n>N 开始，禁止擅自扩展到初始段。
- 记录频数定义中的“值”和“出现次数”不可混淆。
- 记录最终周期的周期 p 与起点 M 是否明确存在。
- 记录奇子列和偶子列中至少一个成立的逻辑，不得证明成“二者都成立”后跳步。

### P16 — 最小不整除素数序列的出现次数上界

元数据（不得入模）：层级 `X / Stress`；方向 `数论`；内部难度 `5+/5`；来源 `User-supplied legacy regression problem`；计划 `标准冷启动 1 次 + 额外冷启动 2 次（共 3 次，角色轮换）；另做历史运行对照，不重跑旧代码`。

Canonical prompt（唯一入模文本）：

```text
Let the sequence {a_n} satisfy a_1=2, and for each n>=2 let a_n be the smallest prime number that does not divide product_{k=1}^{n-1}(a_k+n-k). For any prime p, let f(p) be the number of times p appears in this sequence. Prove that for every positive integer m and every set of pairwise distinct primes p_1,...,p_m, sum_{i=1}^m f(p_i) <= (max_i p_i + sum_{i=1}^m p_i)/2.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录题目乘积、下标平移和“最小素数”语义的完整规范化结果。
- 记录全局路线、局部路线以及跨路线桥梁义务是否分别建立。
- 记录所有与极小击中集、可行集、周期性或素因子删除有关的 Claim 状态，但不得把本清单注入模型提示。
- 记录是否再次出现旧错误：把全局目标误改为前缀稳定、把路线整体判死、负知识过期或压缩丢失。
- 记录最终证明中每个 f(p) 计数边界与求和步骤。

### P17 — 2025×2025 网格矩形覆盖极值

元数据（不得入模）：层级 `X / Stress`；方向 `组合与图论`；内部难度 `5/5`；来源 `IMO 2025 Problem 6`；计划 `标准冷启动 1 次 + 受控恢复试验 1 次`。

Canonical prompt（唯一入模文本）：

```text
Consider a 2025 by 2025 grid of unit squares. Place pairwise nonoverlapping rectangular tiles whose sides lie on grid lines. Determine the minimum number of tiles needed so that every row and every column contains exactly one uncovered unit square.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 分别记录下界证明与达到下界的构造，二者必须独立闭环。
- 记录每个矩形的行区间、列区间与不重叠条件。
- 记录每行每列“恰好一个”而非“至少一个”的检查。
- 记录小规模实例仅作为探索，不得代替一般证明。

### P18 — 交替约束的不等式博弈

元数据（不得入模）：层级 `X / Stress`；方向 `不等式、优化与博弈`；内部难度 `5/5`；来源 `IMO 2025 Problem 5`；计划 `标准冷启动 1 次`。

Canonical prompt（唯一入模文本）：

```text
Fix a positive real number lambda. On turn n>=1, a nonnegative real x_n is chosen. If n is odd, Alice must choose x_n so that x_1+...+x_n <= lambda*n. If n is even, Bazza must choose x_n so that x_1^2+...+x_n^2 <= n. A player who cannot move loses; an infinite play is a draw. Determine all lambda for which Alice has a winning strategy and all lambda for which Bazza has a winning strategy.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录量词顺序：策略必须对对手所有合法应对成立。
- 记录胜、负、无限和局的分类是否穷尽。
- 记录临界 lambda 的边界是否被严格包含或排除。
- 记录构造策略与不可能性证明，不能只给直觉优化。

### P19 — 双圆、圆心与正交中心切线问题

元数据（不得入模）：层级 `X / Stress`；方向 `几何`；内部难度 `5/5`；来源 `IMO 2025 Problem 2`；计划 `标准冷启动 1 次 + 受控恢复试验 1 次`。

Canonical prompt（唯一入模文本）：

```text
Let Omega and Gamma be circles with centers M and N, with radius(Omega)<radius(Gamma), intersecting at distinct points A and B. Line MN meets Omega at C and Gamma at D, with C,M,N,D in this order. Let P be the circumcenter of triangle ACD. Line AP meets Omega again at E and Gamma again at F. Let H be the orthocenter of triangle PMN. Prove that the line through H parallel to AP is tangent to the circumcircle of triangle BEF.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录 C,M,N,D 的线性顺序以及它对符号的影响。
- 记录 P、H 的定义是否被准确用于坐标或向量表达。
- 记录切线判据：半径垂直、幂、角度或重根条件中的哪一种。
- 记录所有圆的归属，避免把 E、F 或 B 放入错误圆。

### P20 — 析取型有理函数方程的有限值问题

元数据（不得入模）：层级 `X / Stress`；方向 `函数方程与序列`；内部难度 `5/5`；来源 `IMO 2024 Problem 6`；计划 `标准冷启动 1 次 + 受控恢复试验 1 次`。

Canonical prompt（唯一入模文本）：

```text
Let Q be the set of rational numbers. A function f:Q->Q is called aquaesulian if for every x,y in Q, at least one of the following holds: f(x+f(y))=f(x)+y, or f(f(x)+y)=x+f(y). Prove that there is an integer c such that for every aquaesulian f, the set {f(r)+f(-r): r in Q} has at most c elements, and determine the smallest possible c.
```

EVALUATION_ONLY — 运行结束后必须提取：

- 记录析取条件不能在不同推导中被默认为固定同一分支。
- 记录所有关于单射、满射、周期或不变量的推导前提。
- 记录上界证明与达到最小 c 的构造或例子。
- 记录有理数域的除法、缩放和零值边界。

## 7. 每个 Run 必须记录的通用证据

下列记录适用于 34 个真实 Run。任何字段无法导出时，Codex 必须标记 `MISSING` 并说明生产系统是否缺乏观测接口；禁止捏造 0、空数组或 PASS。

### 7.1 输入完整性与可复现元数据

- problem_id、trial_id、run_id、benchmark_version、UTC start/end。
- Git branch、HEAD SHA、dirty status、构建产物版本、Java/Maven/Python/PostgreSQL/Temporal 版本。
- problem_prompt_sha256、Root Goal hash、config hash、pricing hash、model ID、Provider ID、temperature、top_p、所有 Token 档位。
- key role map 仅使用 KEY_A–KEY_E；Coordination key；每 key 最大并发。
- 网络策略、工具 allowlist、是否启用计算工具；答案检索必须为 false。

### 7.2 Provider 与五 Key 使用

- 每个物理 Provider call 的匿名 provider_call_id、request/idempotency hash、agent_id、key label、role、stage、开始/结束时间。
- 每调用输入 Token、输出 Token、总 Token、费用、finish reason、retry/failover、是否 JSON repair。
- 每 key 的 call 数、Token、费用、busy time、lease count、rate-limit/provider failure。
- 全局最大并发、Research 最大并发、Coordination 是否保留、平均槽利用率、queue wait、barrier wait、straggler idle。
- duplicate provider calls、duplicate charges、lease collision、idle eligible credential while waiting。

### 7.3 探索树与 Strategy/Route

- 所有 StrategyCard 的稳定 ID、signature、parent、source、admission verdict、rejection reason、lineage 和 archive 状态。
- 所有 Route 的创建、等待、冻结、唤醒、完成、失败与停止原因。
- Route 之间的依赖、冲突集、读集、work kind、stable ordinal。
- 探索树 JSON：节点类型至少含 Strategy、Route、Attempt、Claim、Obligation、Pivot、Verification；边必须有关系类型。
- 禁止保存隐藏 CoT；节点摘要只能来自公开结构化产物或最终证明文本。

### 7.4 Claim、Attempt、Obligation 与 Proof Graph

- 每个 Attempt 的 author、target、status、artifact hash、失败层级和是否重复。
- 每个 Claim 的规范化文本、Claim ID、父义务、支持 Attempts、独立 review、Claim Court verdict。
- 每个 Obligation 的创建来源、依赖、risk、状态转移、wake condition、closed/refuted evidence。
- 每个 verified Fact 的 provenance、对应 Claim、reviewer、receipt 和 Proof Graph 节点。
- Proof Graph 每 Epoch 的节点/边数量、canonical duplicates、root reachability、proof debt、authority hash。

### 7.5 Negative Knowledge、Pivot、Artifact 与计算

- Permanent Negative Knowledge 的条目、类型、scope、来源、是否被重复违反、是否错误过期。
- 每个 Semantic Pivot 的触发证据、旧机制、新机制、产物、outcome；空 Pivot 成功数。
- Artifact Broker 中每个外部/计算产物的 ID、hash、生产者、消费者、验证状态。
- 任何 Python/SymPy/搜索程序的源码 hash、输入、版本、随机 seed、stdout/stderr hash 和可复现结果。
- 不得把数值搜索本身当作一般证明；必须记录从实验到定理的逻辑桥梁。

### 7.6 Checkpoint、Run State、并发与恢复

- 每次 checkpoint schema、checkpoint hash、epoch/task/result/receipt/lease/telemetry/budget snapshots。
- Research Epoch 状态流转和 mutation/merge receipt；authority before/after hash。
- 恢复前后 task/result/lease/budget/zero-gain/Proof Graph hashes。
- post-restore task loss、provider replay、duplicate merge、duplicate authority mutation、state drift。
- Run State 终止原因、可恢复工作、quarantine 项、第二次恢复是否 no-op。

### 7.7 Budget、Token 与停止

- 每 Epoch canonical BudgetStateHash、候选动作及选择：WIDEN/DEEPEN/VERIFY/REVISE/SYNTHESIZE/STOP。
- 动作 envelope、物理调用 reservation、committed/reserved/available calls/tokens/cost。
- finish reserve、stage token envelope、输入估算、实际用量、overrun、uncertain usage。
- CertifiedGainReceipt、per-target/global zero-gain、停滞计数、stop reason。
- stale decision reuse、late rejection、synthesis reserve violation、zero-gain deepen accept、budget drift。

### 7.8 最终证明与验证

- 最终公开证明原文，不做运行后人工润色。
- 系统自评：COMPLETE/INCOMPLETE/REFUTED/UNCERTAIN、置信度、未关闭义务。
- 结构验证、独立复证、falsifier、dependency audit、blind final verdict 的独立结果。
- 最终证明中每个关键步骤与 Proof Graph Claim/Fact 的映射。
- external_score、external_comments、external_failure_attribution 留空，由后续独立评估填写。

## 8. Issues 001–013 真实运行观察矩阵

| Issue | 真实运行必须记录 | 硬失败条件 |

|---:|---|---|

| 001 Root Goal | 题面规范化、量词、目标类型、Root Goal hash、每次恢复 hash | 题意/量词漂移，或 Root Goal 被路线重写 |

| 002 Permanent Negative Knowledge | 负知识来源、scope、重试与修复关系、恢复持久性 | 已证伪机制无修复却重复；条目错误过期 |

| 003 Attempt–Claim Separation | Attempt、Claim、review、Claim Court 独立 ID 与关系 | Attempt 自我提升 Claim/Fact；未审查 Claim 进入权威 |

| 004 Durable Research Checkpoint | checkpoint frontier、公开 findings、budget exhaustion 前保存 | 已产生公开证据在停止/恢复后丢失 |

| 005 Canonical Proof Graph | 节点/边规范化、重复、依赖、root reachability、proof debt | 重复节点改变权威；无依赖 Fact；图与 Claim Ledger 冲突 |

| 006 Semantic Pivot | 触发、机制差异、产物、结果 | 空 Pivot 标记成功；未改变机制却重置停滞 |

| 007 Strategy Portfolio | 原 Strategy、派生、archive、fallback、lineage | 通用 fallback 覆盖/删除原领域策略 |

| 008 Role Separation | author/reviewer/falsifier/adjudicator agent 与 lease | 同一不允许角色组合；盲审看到被禁止身份 |

| 009 Mathematical Artifact Broker | artifact provenance、hash、consumer、验证状态 | 幻影产物；未经验证工具结果成为 Fact |

| 010 Reproducible Computation | 代码、版本、输入、seed、hash、结果复跑 | 不可复现计算直接支撑权威 Claim |

| 011 Run State Reconciliation | 终止、pending/waiting/frozen、wake condition、resume decision | 终止状态仍盲目调用；状态矛盾未 quarantine |

| 012 Sustained Multi-Key Concurrency | lease、实际调用区间、all-settled、merge hash、receipts、restore | 重复调用/合并/authority mutation；lease collision；恢复漂移 |

| 013 Budget/Token/Stop | state hash、envelope、settlement、gain、zero-gain、stop | 预算旁路、重复结算、reserve 破坏、zero-gain 继续深挖 |

## 9. 数学评分与失败归因

### 9.1 外部 0–7 分评分（运行后由独立评估者填写）

| 分数 | 含义 |

|---:|---|

| 0 | 无相关数学进展，或最终论证建立在明显错误上。 |

| 1 | 有一个正确的基本观察，但未形成可用引理。 |

| 2 | 得到一个有意义且正确的局部引理，距离主线仍远。 |

| 3 | 多个正确步骤或一条连贯路线，但核心桥梁缺失。 |

| 4 | 主思想基本正确，存在实质性缺口或未处理关键情形。 |

| 5 | 证明近乎完整，只有一个明确、可局部修复的缺口。 |

| 6 | 数学上正确，仅有轻微表达、边界或引用不充分。 |

| 7 | 完整、严格、自足，所有边界/等号/构造/最小性均闭环。 |

任何答案泄漏、人工中途提示、Root Goal 漂移或权威越界会将该 Run 标记为 `INVALID`，不进入数学平均分。系统自评不得替代外部评分。

### 9.2 失败归因代码

```text
PROMPT_INTEGRITY_FAILURE
STRATEGY_GENERATION_FAILURE
ROUTE_ADMISSION_FAILURE
OBLIGATION_COMPILATION_FAILURE
PROOF_SEARCH_FAILURE
CROSS_ROUTE_SYNTHESIS_FAILURE
CLAIM_REVIEW_FAILURE
PROOF_GRAPH_FAILURE
PIVOT_FAILURE
ROLE_SEPARATION_FAILURE
ARTIFACT_OR_TOOL_FAILURE
COMPUTATION_REPRODUCIBILITY_FAILURE
RUN_STATE_OR_RECOVERY_FAILURE
CONCURRENCY_FAILURE
BUDGET_TOKEN_STOP_FAILURE
PROVIDER_OR_RATE_LIMIT_FAILURE
MODEL_CAPABILITY_LIMIT
INSUFFICIENT_EVIDENCE_TO_ATTRIBUTE
```

归因规则：只有存在可复现系统不变量违例时才归入对应系统失败；如果系统完整保存路线、正确停止且没有违例，只是没有想到关键引理，应归入 `MODEL_CAPABILITY_LIMIT` 或 `INSUFFICIENT_EVIDENCE_TO_ATTRIBUTE`。

## 10. 主要统计指标

### 10.1 数学结果

- Full solve rate：external score=7 的有效 Run 比例。
- Near solve rate：external score>=5。
- Substantial progress rate：external score>=3。
- 按领域、层级、公开/近期/变体/历史回归分组的均值、中位数和分布。
- verified Facts、closed Obligations、refuted false Claims、Proof Debt 变化。

### 10.2 系统完整性

- 13 项硬不变量 violation count；目标全部为 0。
- 完整 evidence bundle 比例；MISSING 字段统计。
- invalid run 数及原因。
- checkpoint/resume task loss、provider replay、state drift。

### 10.3 成本与效率

- calls、input/output/total tokens、USD、wall time，按题/层/阶段/key。
- 每 1 分 external score 的 calls/tokens/cost。
- 每个 verified Fact、closed Obligation、full solve 的资源消耗。
- Research/Coordination 槽利用率、queue/barrier/straggler 时间。

### 10.4 重复性

- P09/P12/P13/P15/P16 三次冷启动的 external score 范围。
- 最终 verdict 一致率、stop reason 一致率、关键桥梁 Claim 重现率。
- 策略 signature Jaccard 相似度与证明路线多样性。
- 五 key 角色轮换后调用失败、Token、费用和成功率是否系统偏移。

## 11. 输出目录与逐 Run 证据包

```text
benchmark/olympiad-5key-v1/
  README.md
  benchmark-manifest.yaml
  problems/P01...P20/problem.txt
  schemas/
  scripts/
  results/
    aggregate/
      benchmark-summary.md
      problem-scores.csv
      issue-001-013-matrix.csv
      provider-key-usage.csv
      cost-and-token-summary.csv
      failure-attribution.csv
      historical-P16-comparison.md
    runs/
      P01/T1/<run_id>/
        run-manifest.json
        config-snapshot.redacted.yaml
        git-state.txt
        provider-usage.ndjson
        concurrency-metrics.json
        strategies.json
        routes.json
        attempts.json
        claims.json
        obligations.json
        claim-court.json
        negative-knowledge.json
        proof-graph.json
        proof-debt-series.csv
        pivots.json
        artifacts.json
        computations.json
        epochs.json
        receipts.json
        checkpoints.json
        budget-decisions.json
        budget-usage.json
        zero-gain.json
        final-proof.md
        final-verification.json
        issue-matrix.json
        failure-attribution.json
        redaction-report.json
        checksums.sha256
  MANIFEST.json
  checksums.sha256
```

若当前系统对象名不同，Codex 可以映射到实际字段，但不得省略语义。数据库原文件不提交；只导出与 Run 相关的去密结构化行，并保留稳定 ID、hash、状态与外键。

## 12. 机器可读记录模板

### 12.1 benchmark-manifest.yaml 示例

```yaml
benchmark_id: olympiad-5key-v1
baseline_commit: ea94a34041fd32a4f94ecb1a3532ddc314430a47
branch: validation/014-five-key-math-olympiad-benchmark
real_provider: true
provider_key_labels: [KEY_A, KEY_B, KEY_C, KEY_D, KEY_E]
network_policy: DEEPSEEK_ONLY
solution_retrieval: false
cross_problem_memory: false
suites:
  standard: [P01, P02, P03, P04, P05, P06, P07, P08, P09, P10,
             P11, P12, P13, P14, P15, P16, P17, P18, P19, P20]
  replication: [P09, P12, P13, P15, P16]
  controlled_recovery: [P11, P17, P19, P20]
hard_gates:
  secret_leaks: 0
  root_goal_drifts: 0
  duplicate_provider_calls: 0
  duplicate_budget_settlements: 0
  post_restore_state_drifts: 0
  authority_violations: 0
```

### 12.2 run-manifest.json 最小字段

```json
{
  "benchmark_id": "olympiad-5key-v1",
  "problem_id": "Pxx",
  "trial_id": "T1",
  "run_id": "...",
  "baseline_commit": "...",
  "problem_prompt_sha256": "...",
  "root_goal_hash_initial": "...",
  "root_goal_hash_final": "...",
  "config_hash": "...",
  "pricing_hash": "...",
  "model_id": "...",
  "provider_id": "...",
  "coordination_key_label": "KEY_A",
  "research_key_labels": ["KEY_B","KEY_C","KEY_D","KEY_E"],
  "cold_start": true,
  "recovery_trial": false,
  "final_status": "COMPLETE|INCOMPLETE|REFUTED|UNCERTAIN|INVALID",
  "stop_reason": "...",
  "external_score": null,
  "bundle_complete": true
}
```

### 12.3 issue-matrix.json 最小字段

```json
{
  "issue_001": {"violations": 0, "evidence_refs": []},
  "issue_002": {"violations": 0, "evidence_refs": []},
  "issue_003": {"violations": 0, "evidence_refs": []},
  "issue_004": {"violations": 0, "evidence_refs": []},
  "issue_005": {"violations": 0, "evidence_refs": []},
  "issue_006": {"violations": 0, "evidence_refs": []},
  "issue_007": {"violations": 0, "evidence_refs": []},
  "issue_008": {"violations": 0, "evidence_refs": []},
  "issue_009": {"violations": 0, "evidence_refs": []},
  "issue_010": {"violations": 0, "evidence_refs": []},
  "issue_011": {"violations": 0, "evidence_refs": []},
  "issue_012": {"violations": 0, "evidence_refs": []},
  "issue_013": {"violations": 0, "evidence_refs": []}
}
```

## 13. Codex 实施阶段与提交要求

### Phase 0 — 基线与完整验证

- 核验 HEAD、分支、工作区。
- 执行当前 `verify-all.ps1 -Offline` 与 Linux/JDK 25 `clean verify`。
- 记录当前测试总数、覆盖率、SpotBugs、PostgreSQL、Sidecar 门。
- 失败则停止；禁止在 Benchmark 分支顺手修 unrelated 生产缺陷。

### Phase 1 — Benchmark harness（无真实 API）

- 创建 manifest、problem files、schemas、runner、sanitizer、validator、aggregator。
- 使用 fake provider 覆盖 20 题 prompt 加载、34-run schedule、key rotation、cold-start isolation、controlled resume。
- 测试 prompt allowlist，确保 metadata/evaluation checklist 不进入 Provider 请求。
- 测试 secret redaction 与 bundle checksum。

```bash
git commit -m "test(benchmark): add five-key olympiad evaluation harness"
```

### Phase 2 — 观测导出补齐（仅在确实缺字段时）

- 优先使用现有 ledger/snapshot/receipt/telemetry。
- 新增 validation-only exporter，不改变数学状态。
- 任何生产改动先写失败测试，说明旧字段为何无法外部审计。
- 增加架构测试：exporter 只读，不能 mutate Claim/Fact/Graph/Budget。

```bash
git commit -m "feat(benchmark): export sanitized run evidence without authority changes"
```

### Phase 3 — Dry run 与五 Key preflight

- 运行 fake-provider 全套并验证 schema。
- 五 key 各执行一次单独的最小连接检查，记入 `preflight/`，不计入题目成绩。
- 打印当前 PricingSnapshot 与 34 次上限费用估计，但不打印 key。
- 确认 `BENCHMARK_GLOBAL_COST_CAP_USD` 与 real-provider 显式开关。

### Phase 4 — 真实分层运行

- 按 P01–P05、P06–P10、P11–P15、P16–P20 顺序。
- 每层后运行 gate validator 和 secret scan。
- 任何硬不变量非零立即停止；保留已有证据，不删除失败 Run。
- 数学失败不自动停止下一题，只要系统完整性仍通过。

### Phase 5 — 重复性与恢复

- P09/P12/P13/P15/P16 追加 T2/T3 冷启动。
- P11/P17/P19/P20 执行规定 durable boundary 的受控停止/恢复。
- 恢复试验不得复用标准试验的 Run State。

### Phase 6 — 汇总、清洗、最终回归

- 生成 aggregate CSV/MD，不填写 external score。
- 执行 secret scan、PII/credential scan、checksums。
- 重新运行完整本地、Linux/JDK 25、Docker/PostgreSQL、SpotBugs/FindSecBugs 和 GitHub Actions。
- 不得把真实运行 bundle 推送到 Git；只在用户指定本地输出目录生成最终 ZIP。

## 14. P16 历史失败对照

P16 是本项目此前真实失败题。Codex 不得重跑旧代码，也不得把旧诊断注入新 Run。新 Run 完成后，单独从用户已有历史报告提取可比统计：

- API calls、input/output/total tokens、费用、wall time。
- Strategy/Route 数、关键路线是否保留、核心桥梁义务是否建立。
- verified Facts、closed Obligations、Proof Debt 起止。
- 负知识过期、路线整体判死、压缩丢失、重复恢复、budget stop。
- 最终 score、stop reason 与 failure attribution。

对照报告只在运行后生成，文件名 `historical-P16-comparison.md`。它不得进入 P16 的任何 Provider prompt 或 runtime context。

## 15. 最终提交给外部评估者的 ZIP

文件名：

```text
MathProofMesh_olympiad-5key-v1_<HEAD12>_<UTCSTAMP>.zip
```

必须包含：

- 本文档的 Markdown 版本与实际 benchmark manifest。
- 34 个 Run 的去密证据包；若因硬 gate 提前停止，则包含已完成 Run 和明确 stop report。
- 20 个最终证明；重复/恢复试验各自保留，不覆盖。
- aggregate 表、P16 历史对照、Git/环境/配置/价格快照。
- redaction report、secret scan report、MANIFEST.json、checksums.sha256。

必须排除：

- 五个 API key、`.env`、Authorization header、raw request headers。
- 未清洗网络抓包、数据库原文件、target、cache、dist、临时 checkpoint 目录。
- 官方解答、外部 solution、人工补写证明、隐藏思维链。

## 16. Benchmark 完成判定

工程层与数学层分开判定。

### 16.1 工程层 PASS

```text
SECRET_LEAKS=0
CROSS_PROBLEM_STATE_LEAKS=0
ROOT_GOAL_DRIFTS=0
UNREVIEWED_FACT_PROMOTIONS=0
DUPLICATE_PROVIDER_CALLS=0
DUPLICATE_PROVIDER_CALL_CHARGES=0
DUPLICATE_MERGES=0
POST_RESTORE_TASK_LOSSES=0
POST_RESTORE_PROVIDER_CALL_REPLAYS=0
POST_RESTORE_BUDGET_DRIFT=0
SYNTHESIS_RESERVE_VIOLATIONS=0
ZERO_GAIN_DEEPEN_ACCEPTS=0
BUNDLE_CHECKSUM_FAILURES=0
RESULT=PASS
```

数学层不设“必须解出多少题”作为工程发布门。低成功率可能揭示模型能力或新的搜索缺陷，必须由证据归因。

### 16.2 新 Issue 的建立条件

- 一次安全/数学权威严重违例即可建立新 Issue。
- 一般系统缺陷应在至少两个独立 Run 可复现，或在一个 Run 中有确定的状态/代码证据。
- “模型没有想到解法”本身不是系统 bug。
- 新 Issue 必须含修复前证据、违反的不变量、最小复现和可写成自动测试的验收条件。

## 17. 已知局限

- 经典和公开 IMO 题可能出现在模型训练语料中，因此成绩不能等同于纯粹的未见题泛化。近期题、Benchmark 自编变体和历史回归题用于部分缓解。
- DeepSeek API 的服务端实现、随机性、限流和模型版本可能随时间改变；必须保存 model ID、响应元数据和 PricingSnapshot。
- 三次重复只能提供初步方差，不足以给出高置信统计显著性。
- 几何题若系统没有可靠图形工具，会同时测试文本几何表示能力；应在归因时区分工具缺失和模型推理失败。
- 系统自带 verifier 不是外部真值，最终数学分必须由独立评估完成。

## 18. 可直接交给 Codex 的入口指令

```text
请严格执行附件《MathProofMesh 五个真实 DeepSeek API-Key 数学竞赛 Benchmark：Codex 严格执行、记录、打包与后续诊断说明书》。

基线：
- HEAD 必须为 ea94a34041fd32a4f94ecb1a3532ddc314430a47
- 新分支：validation/014-five-key-math-olympiad-benchmark
- 不修改 main

核心要求：
1. 先完整验证基线，再创建 Benchmark harness；单元测试和 dry run 不得调用真实 API。
2. 五个 key 只从 DEEPSEEK_API_KEY_A...E 环境变量读取，任何文件和日志不得出现密钥或 Authorization header。
3. 真实运行前必须打印 34 次运行的 calls/tokens/费用最坏估计，并要求 BENCHMARK_GLOBAL_COST_CAP_USD 已设置。
4. 每道题独立冷启动，禁止跨题数学状态；指定五题做三次冷启动，指定四题做 durable-boundary 受控恢复。
5. Provider 只能收到 canonical problem prompt；难度、来源、评估清单、历史失败和官方解答不得入模。
6. 禁止 web search、外部题解、RAG solution、人工中途提示；允许的本地计算必须完整记录并可复现。
7. 不保存隐藏思维链，只保存公开结构化 Strategy/Route/Attempt/Claim/Obligation/Pivot/Verifier/Proof 和工具产物。
8. 逐 Run 导出本文档第 7 节全部证据，并生成 Issues 001–013 observation matrix。
9. 每层执行硬 gate；安全泄漏、权威越界、重复 Provider 调用/结算、恢复漂移或预算旁路任一非零时立即停止。
10. 不因数学失败删除 Run；外部数学分字段保持 null，不得下载官方解答自评。
11. 结果 bundle 必须通过 secret scan、schema validation 和 SHA-256；不得提交到 Git，只生成本地去密 ZIP。
12. 最终重新执行完整本地验证、Linux/JDK 25 clean verify、Docker/PostgreSQL、SpotBugs/FindSecBugs、Sidecar 和 GitHub Actions。
13. 最终汇报分开写工程完整性、数学结果、资源效率、重复性、P16 历史对照和新缺陷候选。
14. 若实际源码名称与本文不同，先建立映射，不得猜测或创建平行权威系统。
15. 不得只回复“已完成”；必须给出分支、HEAD、提交列表、测试、34 个 Run 状态、硬诊断、ZIP 路径和已知限制。
```

## 19. 来源说明

公开题来源仅用于题面核对，不允许在运行期间访问解答：

- International Mathematical Olympiad, official problem archive, 1988 Problem 6.
- International Mathematical Olympiad, official problem archive, 2001 Problem 2.
- International Mathematical Olympiad 2024 official English paper: Problems 3, 4, and 6.
- International Mathematical Olympiad 2025 official English paper: Problems 2, 5, and 6.
- P16 为用户此前提供并用于 MathProofMesh 失败诊断的历史回归题。
- 其余题目为经典校准题或本 Benchmark 的结构化变体；本文档不附标准解答。

## 20. 预算余量修订（2026-08-24）

第一次真实 P01/T1 Campaign 已按硬门停止并永久保留，不得续跑或改写。该次运行证明旧的
`characters / 4` 输入估算把 1,151 个实际输入 Token 估为 1,096，产生 55 Token 的
输入维度漂移。经用户明确授权，后续必须从全新 Campaign 冷启动，并采用以下修订预算：

| 档位 | 最大调用 | 最大轮数 | 单 Run 总 Token | 单调用输出上限 |
| --- | ---: | ---: | ---: | ---: |
| SMOKE | 48 | 6 | 2,304,000 | 32,000 |
| CORE | 48 | 8 | 2,304,000 | 32,000 |
| ADVANCED | 64 | 12 | 3,072,000 | 32,000 |
| STRESS | 96 | 16 | 4,608,000 | 32,000 |

调度动作按每次物理调用 16,000 输入 Token 预留，真实 Prompt 另使用 UTF-8 字节、字符数、
25% tokenizer 余量和 128 Token 消息封装余量进行确定性估算。未使用的预留在 Provider
返回实际 usage 后释放，不计为实际费用。

34 个隔离 Run 的最坏上限为 2,304 次调用、110,592,000 Token；按冻结的最高输出单价
计算为 96.21504 美元，仍低于用户设置的 100 美元全局硬上限，并保留 3.78496 美元余量。
顶层 Artifact Recovery、Post-Failure Recovery 和 JSON Repair 统一使用 32,000 Token 上限，
不得再在修复链中暗自回落到 16,000 或 8,192 Token。
每个档位在调用真实 Provider 前还必须确定性证明：最坏有界 Triage、Strategy Generation、
一次 Replenishment、两批 Critical Claim Preflight、三个初始 Route Exploration Envelope 与
受保护 Finalization Reserve 可以同时容纳。不能满足时必须以
`BENCHMARK_INITIAL_EXPLORATION_ENVELOPE_EXHAUSTED` 在预检阶段停止，不得花费真实调用后才发现
初始研究队列不可达。
本修订只扩大资源余量，不降低任何 overrun、重复结算、权威、密钥、checkpoint 或恢复硬门。
本文前述“不得放宽预算”规则继续约束未授权的成绩导向修改；本节是用户明确授权后的唯一例外，
并取代旧 Campaign 的冻结 Token 档位。
