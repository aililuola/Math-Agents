# JavaMathProofMesh-0.8.0 完整分阶段迁移计划书

> **用途**：本文件是交给 Codex 的主迁移规范。Codex 必须逐阶段执行，不得自行改变目标架构、跳过阶段门禁、删减 Python 基线功能，或修改原 Python 项目。
>
> **目标目录**：`JavaMathProofMesh-0.8.0`
>
> **源基线**：用户提供的 `Math-Agents-feature-mathproofmesh-v0.8.0-goal-plan-failure-utility-control.zip` 完整快照。
>
> **计划制定日期**：2026-07-29（America/New_York）

---

## 1. 最终目标与完成定义

本迁移的最终产物不是把 Python 文件机械翻译成 Java，而是在**不改动原 Python 项目**的前提下，建立一个由 Java 负责生产运行主链的 MathProofMesh 版本：

1. Java 负责契约、配置、Agent 编排、可靠消息传递、三级记忆、证明义务图、证明控制、灵感机制、验证升级、预算调度、断点恢复、REST/SSE、CLI、桌面端、审计、数据库事务和持久化工作流。
2. PostgreSQL 是权威状态存储；文件系统只保存不可变、内容寻址的 Artifact。
3. Temporal 负责长时间工作流的持久化调度，但不取代 MathProofMesh 自己的数学状态、消息幂等和数据库事务。
4. SymPy、Z3 表达式转换及少数在 Java 中直接重写风险较高的数学能力，保留为**新目录中的受限 Python 计算侧车**。该侧车不是原 Python 应用，也不能在运行时导入或修改原项目。确定性的几何、图、序列与基础模运算优先原生 Java。
5. Java 应用是唯一生产入口。Python 侧车只能通过版本化 JSON-RPC 标准输入/输出协议接收白名单计算请求，不能参与工作流编排、数据库写入、消息投递或秘密管理。
6. 原 Python 测试基线、JSON 契约、哈希结果、Checkpoint 恢复和 Mock 端到端行为构成迁移验收依据。

只有同时满足以下条件，才可宣布迁移完成：

- `JavaMathProofMesh-0.8.0` 可独立构建、测试、启动、执行 Mock 求解、暂停、崩溃恢复和终审；
- 原 Python 目录的迁移前后文件清单与 SHA-256 完全一致；
- 排除 `.git` 后的 **401 个源快照文件全部有明确归宿**：142 个运行源文件/资源、167 个测试/测试支持文件（164 个 `test_*.py` 测试模块、`conftest.py` 和 2 个共享 helper），以及 92 个配置、文档、基准、打包、脚本和项目元数据文件；
- 必需路径不存在 `TODO`、`FIXME`、占位返回、空实现或以 `UnsupportedOperationException` 代替功能的情况；
- 关键领域不变量、恢复语义、消息幂等、反例传播和安全门禁全部通过；
- 所有发布门禁通过，生成 SBOM、依赖安全报告、迁移覆盖报告、数据库迁移文件和发布包。

---

## 2. 已核验的源项目事实

### 2.1 版本事实

压缩包和目录名包含 `v0.8.0`，但快照内部：

- `pyproject.toml` 声明 `version = "0.8.2"`；
- `BUILD_INFO.json` 声明项目版本 `0.8.2`，验证日期为 `2026-07-27`；
- 快照包含 0.8.1、0.8.2 以及后续兼容/回归命名的测试和修复文档；
- `BUILD_INFO.json` 记录完整依赖环境下为 `759 passed`。

因此，本计划采用下列不变规则：

- 目标文件夹与 Java 发布线仍命名为 **`JavaMathProofMesh-0.8.0` / `0.8.0`**，遵守用户指定；
- 功能基线不是早期 0.8.0，而是**此次压缩包中的全部功能快照**；
- Codex 不得因为目标目录叫 0.8.0 而删除 0.8.1/0.8.2 已存在的语义控制、迁移和 exactly-once 修复。

### 2.2 完整性指纹

- 原压缩包 SHA-256：`5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2`
- 本次解压快照中迁移前原始文件集合（排除 `.git`）的组合清单 SHA-256：`9f3def2bec8cea99d0a18b51fbb5fa8ce53f44a24ce869f6495cac809b7e3770`
- 对应逐文件清单：`SOURCE_SNAPSHOT_SHA256SUMS.txt`（401 行）
- `BUILD_INFO.json` 中记录的运行构建提交：`ed7486aae4915c14971f65725982c4b0ca772f88`

组合清单的算法固定如下，不允许 Codex 自行选择另一种目录哈希算法：

1. 以原 Python 工作树根为基准，只枚举普通文件；排除任一路径段为 `.git` 的文件。目标目录创建后，还必须排除 `JavaMathProofMesh-0.8.0/**`。
2. 相对路径统一使用 `/`，以 UTF-8 编码后的路径字节按升序排序。
3. 每个文件生成一行：`<小写 SHA-256><两个 ASCII 空格><相对 POSIX 路径><LF>`；包括最后一行后的 LF，禁止 CRLF、BOM、绝对路径或本地化排序。
4. 将全部 401 行按上述顺序连接为 UTF-8 字节串，再计算该字节串的 SHA-256；预期即为上述组合清单 SHA-256。
5. 创建目标目录后，每次只复算第 0 阶段冻结的 401 个源路径，并另行检查目标目录之外没有新增、删除或未纳入冻结清单的文件。

Codex 必须在创建目标目录前于本机重新计算，并逐行比对随计划提供的清单，不能仅复制上述结果。若任一文件哈希、路径、行数或组合哈希不同，立即停止，不得继续迁移。

### 2.3 规模与关键模块

排除 `.git` 后，快照共有 **401 个文件**。其中：142 个运行源文件/前端资源文件（137 个 `.py` 文件和 5 个桌面 Web 资源，包含二进制图标）；167 个测试/测试支持 Python 文件（164 个 `test_*.py` 测试模块、`conftest.py` 和 2 个共享 helper），显式定义 707 个测试函数并参数化收集为 759 项；另有 92 个配置、文档、基准、Windows 打包、脚本、许可证和项目元数据文件。`BUILD_INFO.json` 的测试文件统计为 166，和快照直接库存相差 1，因此迁移以直接文件库存和 759 项测试收集结果为准，不得忽略辅助文件。主要 Python 代码约 7.3 万行，测试代码约 2.59 万行。关键文件包括：

- `orchestrator.py`：整体阶段次序、并行探索、验证、综合和恢复；
- `proof_control/controller.py`：Goal–Plan–Failure–Utility 控制；
- `schemas.py`：严格契约、枚举、哈希和状态模型；
- `communication/broker.py`：消息准入、投递、去重、回执和效用；
- `memory.py`：Fact / Insight / Negative 三级记忆；
- `proof_graph/store.py`：证明义务图、依赖、冲突和传播；
- `inspiration/`：表示切换、类比、构造、不变量、逆向目标、元策略、Surprise Budget、Novelty 和 Outcome Ledger；
- `computation/`：符号、图、整数、模运算、数论、实不等式、递推、序列和几何计算；
- `llm/`：DeepSeek、Anthropic、Gemini、OpenAI-compatible 和 Mock；
- `desktop/`：本地桌面 UI、运行管理和 Windows 凭据保护。

完整逐文件归宿见：

- `PYTHON_SOURCE_MIGRATION_MAP.csv`
- `PYTHON_TEST_MIGRATION_MAP.csv`
- `OPS_CONFIG_DOC_MIGRATION_MAP.csv`
- `SOURCE_SNAPSHOT_SHA256SUMS.txt`

---

## 3. Codex 不可违反的硬性规则

### 3.1 目录和源代码隔离

1. 将包含现有 Python 项目的 Git 工作树根目录记为 `WORKSPACE_ROOT`，目标固定为 `WORKSPACE_ROOT/JavaMathProofMesh-0.8.0/`。这是唯一允许新增和修改的目录。不得把目标放进 `src/`、Python 包目录或任一已有子项目内部。
2. 不得修改、删除、重命名、格式化、移动任何迁移开始前已经存在的 Python 项目文件。允许在工作树根新增且仅新增 `JavaMathProofMesh-0.8.0/`。
3. 不得在目标目录之外创建 `.venv`、`.pytest_cache`、`__pycache__`、`dist`、`target`、临时文件、日志或测试输出。
4. 不得创建指向原目录的可写符号链接、junction 或运行时依赖。
5. 可以只读读取 Python 源、文档、配置、测试和旧运行目录；需要资源时必须复制到新目录并记录来源 SHA-256。
6. 第 0 阶段在创建目标目录前记录“原始文件集合”。此后每阶段完整性检查必须证明：原始文件路径、大小和 SHA-256 全部不变，且目标目录之外没有新增文件。目标目录本身不纳入原始文件哈希。任一违规即判定阶段失败。

### 3.2 执行顺序

1. 严格按第 0 阶段到第 17 阶段执行。
2. 一个阶段未通过退出门禁，不得开始下一阶段。
3. 不得一次性生成大批未经测试的代码再回头修复。
4. 每一阶段必须先完成领域测试，再完成集成测试，再完成阶段报告。
5. 若处于 Git 仓库，必须新建分支 `feature/java-mathproofmesh-0.8.0-migration`；不得在 `main` 上直接开发。每个通过门禁的阶段形成一个独立提交，提交中只允许新目录内容。

### 3.3 安全优先、效率其次、功能完整

优先级严格为：

1. **安全、数学正确性以及与其直接相关的恢复/一致性不变量**，这些均不可妥协；
2. **高效、简洁、可维护的实现**，在存在同等安全与正确性的成熟快速方案时优先采用，避免冗长重复代码；
3. **完成整个迁移并保持功能完整与兼容**。功能完整是最终完成条件，不能以“提效”为理由删除基线能力；
4. 仅在上述均满足后考虑非必要扩展。

“高效、简洁”不是删功能，也不是绕过校验。其含义是：

- 优先使用 JDK、Spring Boot 和 PostgreSQL 已有能力，避免无必要框架；
- 使用 Java `record`、`sealed interface`、`enum`、不可变集合和明确事务边界减少样板代码；
- 不使用反射式魔法、通用 Map 到处传递、过度抽象或为“以后可能用”而建立空层；
- 首版不引入 Kafka、Redis、RabbitMQ、Neo4j、Elasticsearch、Spring AI、Lombok、MapStruct 或微服务拆分；
- 只有在基准或真实需求证明 PostgreSQL/模块化单体不足后，才能另立 ADR 引入新组件。

### 3.4 禁止的捷径

- 禁止以逐行翻译的方式制造一个新的超大型 `Orchestrator.java`；
- 禁止把结构化消息退化为字符串聊天；
- 禁止把所有 Pydantic 对象退化成 `Map<String,Object>`；
- 禁止放宽未知字段拒绝、哈希校验、作用域校验、作者/Reviewer 隔离和证据门禁；
- 禁止删除失败、负面记忆、反例、迁移、恢复或 shadow 模式以减少工作量；
- 禁止用“消息已 ACK”代替“消息已在通过验证的 ProofDelta 中实际使用”；
- 禁止将 LLM 调用、数据库、网络、文件系统或当前时间直接放入 Temporal Workflow 代码；
- 禁止默认执行模型生成的 Python；
- 禁止在测试中调用付费模型，除非用户显式设置 `MPM_ALLOW_LIVE_PROVIDER_CALLS=true`；
- 禁止在 Git、日志、Prompt、Artifact、SSE 或异常中泄露 API key、Bearer token、完整秘密或私有推理链。

---

## 4. 最终技术栈与安装要求

### 4.1 用户需要安装的组件

| 组件 | 要求 | 是否必须手动安装 | 用途 |
|---|---|---:|---|
| Eclipse Temurin JDK | JDK 25；推荐当前安全补丁 `25.0.4+7` 或更高 25.x 补丁 | 是 | 编译和运行 Java |
| Git | 当前受支持版本 | 是 | 分支、阶段提交和完整性检查 |
| Docker Desktop / Docker Engine + Compose v2 | 可执行 `docker compose` | 是 | PostgreSQL；后期本地 Temporal 与沙箱 |
| Python | `>=3.11`，推荐 3.13.x | 是 | Python 基线测试和受限计算侧车 |
| Apache Maven | 3.9.16 | 否 | 由项目内 Maven Wrapper 下载并锁定；不依赖全局 `mvn` |
| PostgreSQL | 18.4 | 否 | 通过 Docker Compose 启动，不要求本机安装 PostgreSQL Server |
| Temporal | Java SDK 1.37.0；本地开发镜像 `temporalio/temporal:1.8.1`（内嵌 Server 1.31.2） | 否 | Maven 依赖与第 13 阶段本地开发/CI 服务 |
| Spring Boot / JavaFX 等 | 见依赖矩阵 | 否 | 全部由 Maven 解析 |
| Node.js | 不需要 | 否 | 前端复用静态资源，不另建 Node 工程 |
| Redis/Kafka/Neo4j | 不需要 | 否 | 首版明确不引入 |

### 4.2 固定依赖矩阵

Codex 从第 0 阶段预检开始即必须使用下列版本，不得使用 `LATEST`、`RELEASE`、动态范围或 Snapshot：

| 技术 | 固定版本/管理方式 |
|---|---|
| Java 编译级别 | 25 |
| Apache Maven | 3.9.16 |
| Apache Maven Wrapper | 3.3.4，`only-script` 类型；固定 Maven 分发 URL 和 `distributionSha256Sum` |
| Spring Boot | 4.1.0 |
| Spring Framework | 由 Spring Boot 4.1.0 管理，要求 7.0.8+ |
| Spring Modulith BOM | 2.1.0 |
| Temporal Java BOM / SDK / testing | 1.37.0 |
| Temporal CLI/本地开发镜像 | `temporalio/temporal:1.8.1`；内嵌 Temporal Server 1.31.2；第 0 阶段记录完整不可变 repo digest，第 13 阶段 Compose 只允许 `image@sha256:...` |
| PostgreSQL 服务 | 18.4；Docker 镜像拉取后固定 digest |
| PostgreSQL JDBC、Flyway Core、Jackson、JUnit、Micrometer、Testcontainers | 优先由 Spring Boot 4.1.0 BOM 管理，不单独覆盖 |
| Flyway PostgreSQL 支持模块 | 显式加入 `org.flywaydb:flyway-database-postgresql`，版本跟随 Boot/Flyway 管理 |
| JavaFX | 25.0.4 |
| Picocli | 4.7.7，仅使用核心库，不使用其旧 Spring Boot starter |
| JGraphT | 1.5.3 |
| JNA JPMS | 5.19.1 |
| ArchUnit | 1.4.2 |
| Maven Enforcer Plugin | 3.6.3 |
| SpotBugs Maven Plugin | 4.10.3.0，并启用 FindSecBugs |
| JaCoCo | 0.8.15 |
| OWASP Dependency-Check Maven Plugin | 12.2.2 |
| CycloneDX Maven Plugin | 2.9.2 |

官方核验入口：

- JDK：<https://adoptium.net/temurin/releases>
- Spring Boot 系统要求：<https://docs.spring.io/spring-boot/system-requirements.html>
- Spring Modulith：<https://docs.spring.io/spring-modulith/reference/index.html>
- Temporal Java：<https://docs.temporal.io/develop/java>
- Temporal CLI/开发服务：<https://docs.temporal.io/cli>
- Temporal 开发镜像：<https://hub.docker.com/r/temporalio/temporal/tags>
- PostgreSQL 18.4：<https://www.postgresql.org/docs/release/18.4/>
- Maven：<https://maven.apache.org/download.cgi>
- Maven Wrapper：<https://maven.apache.org/tools/wrapper/>
- JavaFX：<https://openjfx.io/>

### 4.3 Windows 环境核验命令

```powershell
java -version
javac -version
git --version
docker version
docker compose version
python --version
py -0p
```

验收：

- `java` 和 `javac` 都是 25；
- Docker daemon 可用；
- `docker compose version` 可用；
- Python 至少 3.11；
- 不要求全局 `mvn`；第 0 阶段由 Codex 在目标目录内安全引导 Maven Wrapper 3.3.4，之后统一运行 Maven 3.9.16。

### 4.4 SQL 是否需要

需要数据库能力，但**不要求用户安装本地 PostgreSQL**。项目提供：

```text
JavaMathProofMesh-0.8.0/docker-compose.yml
JavaMathProofMesh-0.8.0/.env.local.example
JavaMathProofMesh-0.8.0/scripts/db-up.ps1
JavaMathProofMesh-0.8.0/scripts/db-down.ps1
JavaMathProofMesh-0.8.0/scripts/db-reset-test-only.ps1
```

`docker-compose.yml` 必须：

- 绑定 `127.0.0.1`，不对公网开放；
- 使用 `postgres:18.4-bookworm`，首次拉取后记录并固定镜像 digest；
- 密码只来自未跟踪的 `.env.local`；
- 配置健康检查和持久卷；
- 不使用默认弱密码；
- 开发数据库与测试数据库分离。

---

## 5. 固定目标目录结构

Codex 必须创建且只在下列根目录工作：

```text
JavaMathProofMesh-0.8.0/
├─ pom.xml
├─ mvnw
├─ mvnw.cmd
├─ .mvn/
├─ .gitignore
├─ .gitattributes
├─ .editorconfig
├─ README.md
├─ LICENSE
├─ NOTICE.md
├─ CHANGELOG.md
├─ docker-compose.yml
├─ .env.local.example
├─ config/
│  ├─ application.yaml
│  ├─ application-dev.yaml
│  ├─ application-test.yaml
│  ├─ mock.yaml
│  ├─ deepseek-v4-pro.yaml
│  ├─ deepseek-v4-pro-smoke.yaml
│  ├─ topology-active.yaml
│  ├─ proof-control-shadow.yaml
│  └─ proof-control-active.yaml
├─ docs/
│  ├─ architecture.md
│  ├─ security.md
│  ├─ operations.md
│  ├─ compatibility.md
│  ├─ database.md
│  ├─ temporal.md
│  └─ adr/
├─ scripts/
│  ├─ preflight.ps1 / preflight.sh
│  ├─ check-original-immutable.ps1 / .sh
│  ├─ baseline-python-tests.ps1 / .sh
│  ├─ db-up.ps1 / .sh
│  ├─ db-down.ps1 / .sh
│  ├─ temporal-dev-up.ps1 / .sh
│  ├─ temporal-dev-down.ps1 / .sh
│  ├─ verify-all.ps1 / .sh
│  └─ package-desktop.ps1 / .sh
├─ migration/
│  ├─ BASELINE.json
│  ├─ state.json
│  ├─ dependency-lock.yaml
│  ├─ source-state.csv
│  ├─ test-state.csv
│  ├─ auxiliary-state.csv
│  ├─ baseline/
│  │  ├─ source-manifest.csv
│  │  ├─ test-inventory.json
│  │  ├─ schemas/
│  │  ├─ hash-vectors.jsonl
│  │  ├─ config-fixtures/
│  │  └─ recorded-provider-fixtures/
│  ├─ reports/
│  │  └─ phase-00.md ... phase-17.md
│  └─ tools/
├─ mathproofmesh-contracts/
├─ mathproofmesh-core/
├─ mathproofmesh-server/
├─ mathproofmesh-desktop/
├─ mathproofmesh-compatibility/
└─ python-compute-service/
```

### 5.1 Maven 坐标

```xml
<groupId>io.github.aililuola</groupId>
<version>0.8.0-SNAPSHOT</version>
```

父 artifact：`java-mathproofmesh-parent`。

正式发布时统一改为 `0.8.0`，不得在子模块使用不同版本。

### 5.2 模块依赖方向

```text
mathproofmesh-contracts
        ↑
mathproofmesh-core
        ↑
mathproofmesh-server
        ↑
mathproofmesh-desktop

mathproofmesh-compatibility → 可依赖以上模块，仅用于测试/迁移，不进入生产包
python-compute-service      → 独立进程，不依赖原 Python 目录
```

约束：

- `contracts`：只允许 JDK、Jackson Core/Databind/Annotations 和 Jakarta Validation；不得依赖 Spring、JDBC、Temporal、JavaFX。Canonical JSON、严格反序列化和兼容哈希实现放在此模块。
- `core`：纯领域和应用规则；不得依赖 Spring、JDBC、HTTP、Temporal、JavaFX。
- `server`：Spring Boot、JDBC、Flyway、Provider、Temporal、REST、SSE、CLI 和适配器。
- `desktop`：JavaFX、JNA 和本地 server 启动器；不得直接访问数据库 Repository。
- `compatibility`：基线读取、差分测试、旧运行导入；不得被生产模块依赖。
- 不启用 JPMS `module-info.java`；使用 Maven 模块、Spring Modulith 和 ArchUnit 约束边界，避免 Spring/JavaFX/Temporal 的 JPMS 复杂度。

---

## 6. 统一编码与迁移规则

### 6.1 类型规则

1. Python 顶层 `StrEnum` → 同名 Java `enum`，使用 `@JsonValue`/`@JsonCreator` 保持小写字符串值。
2. 不可变 Pydantic 模型 → Java `record`；构造器中执行领域校验和防御性复制。
3. 有明确多态层级 → `sealed interface` + 明确 `permits`；禁止 Jackson 默认类型信息。
4. Python 顶层类原则上保持同名，每个 public 类型独立文件。
5. 模块级纯函数 → `<模块名>Functions` 或计划指定的领域服务；不得复制为无边界的通用 Util 类。
6. Python `Any` 必须逐项审计：
   - 有稳定结构时建立明确类型；
   - 真正扩展字段才允许 `JsonNode`；
   - 禁止在核心领域使用任意 Java 对象反序列化。
7. 所有列表、Set、Map 在构造后不可变；对顺序有语义的集合不得改为无序 Set。
8. 所有输入字符串执行与 Pydantic `str_strip_whitespace` 等价的 trim；必填字符串 trim 后为空必须失败。
9. 未知 JSON/YAML 字段必须失败；不得静默忽略。

### 6.2 命名与序列化

- Java 字段采用 camelCase；通过 Jackson 保持外部 `snake_case`。
- JSON 输出 UTF-8、无 BOM、无缩进、键按 Python 兼容规则排序。
- 所有状态、类型、证据和动作值保持 Python 字面值。
- 所有向外契约必须有 golden fixture。

### 6.3 错误处理

- 领域错误使用密封异常层级或明确错误码；不能吞异常后返回空对象。
- HTTP 层输出稳定错误结构：`code`、`message`、`run_id`、`correlation_id`，不得输出堆栈和秘密。
- 外部失败必须区分：配置、鉴权、限流、超时、协议、结构化输出、数学验证、预算、恢复冲突和内部错误。
- 对可重试和不可重试错误建立显式表；401/403 默认不可同 key 重试。

### 6.4 效率规则

- I/O 型 Agent 调用使用 Java 25 虚拟线程，但同时受全局、每 key、RPM、预算和运行租约限制。
- 不使用无界线程池、无界队列或无界缓存。
- 数据库写入按聚合事务处理；批量消息、依赖和事件使用 JDBC batch。
- 常用图计算在单次运行中使用 JGraphT 投影，PostgreSQL 仍是权威源。
- Artifact 大内容不重复放入 Temporal history 或多张表；数据库只存引用和哈希。
- 优先 Spring `JdbcClient`/`NamedParameterJdbcTemplate`，不使用 JPA/Hibernate，避免复杂对象图和隐式查询。

---

## 7. 每阶段统一执行协议

Codex 每阶段必须完成以下固定步骤：

1. 读取本计划、阶段门禁 YAML 和三个迁移映射 CSV；三个 CSV 的并集必须覆盖冻结清单中的全部 401 个非 Git 文件。
2. 运行 `check-original-immutable`，记录源清单哈希。
3. 检查 `migration/state.json`，确认前一阶段为 `passed`。
4. 将当前阶段标记为 `in_progress`，记录开始时间和 Git commit。
5. 只实现本阶段范围，不提前把后续阶段写成占位类。
6. 先写/移植测试，再完成实现；必要时使用小步提交，但阶段最终只保留可构建状态。
7. 执行本阶段命令、完整 `./mvnw -B -ntp verify` 和源完整性检查。
8. 更新 `source-state.csv`、`test-state.csv` 与 `auxiliary-state.csv`：`pending → in_progress → migrated/verified`；不得只更新代码而遗漏配置、文档、基准或打包文件。
9. 生成 `migration/reports/phase-XX.md`，内容必须包括：
   - 新增/修改文件；
   - 已迁移 Python 文件和测试；
   - 设计决定；
   - 完整命令和结果；
   - 失败过的测试及修复；
   - 安全检查；
   - 性能或资源变化；
   - 源目录前后 SHA-256；
   - 已知但不阻塞的问题；
   - 退出门禁逐项 PASS/FAIL。
10. 全部门禁 PASS 后，将 `state.json` 标记为 `passed` 并形成阶段提交。
11. 若任何门禁 FAIL，状态标记为 `blocked`，停止；不得跳过或用临时禁用测试的方式继续。

所有阶段都至少执行：

```powershell
.\scripts\check-original-immutable.ps1
.\mvnw.cmd -B -ntp verify
```

Linux 对应：

```bash
./scripts/check-original-immutable.sh
./mvnw -B -ntp verify
```

---

# 8. 分阶段迁移计划

## 第 0 阶段：不可变基线、环境和框架预检

### 目标

创建目标目录，证明源快照未损坏，建立可复现 Python 基线和全部迁移库存；此阶段不写 Java 业务逻辑。

### 必做任务

1. 确认源目录同时包含 `pyproject.toml`、`BUILD_INFO.json`、`src/mathproofmesh` 和 `tests`。不满足即停止。
2. 在工作树根目录创建唯一的新顶层目录 `JavaMathProofMesh-0.8.0/`。不得创建在 `src/`、`tests/`、现有 Python 包或任一已有子目录内；除该目标目录外，工作树不得出现其他新增文件。
3. 写入 `migration/BASELINE.json`：
   - 源绝对路径；
   - 压缩包 SHA-256；
   - 每个源文件相对路径、大小、SHA-256；
   - `pyproject.toml` 版本；
   - `BUILD_INFO.json` 全部元数据；
   - Python、OS、Git、Docker、JDK 版本；
   - 计划基线测试数 759。
4. 编写 Windows/Linux 源不可变检查脚本。比较路径、大小和 SHA-256；任何新增/删除/变化都失败。
5. 所有 Python 缓存重定向到目标：

```powershell
$env:PYTHONDONTWRITEBYTECODE='1'
$env:PYTHONPYCACHEPREFIX="$TargetRoot\.cache\pycache"
$env:PYTEST_ADDOPTS="-o cache_dir=$TargetRoot\.cache\pytest --basetemp=$TargetRoot\.cache\pytest-tmp"
```

6. 在目标目录创建 `.venv-baseline`。禁止 editable install。安装 `pyproject.toml` 中 core、server、desktop、dev 依赖，尤其 `z3-solver`。
7. 使用 `PYTHONPATH=<源目录>/src` 运行完整测试；期望 `759 passed`。若不一致，记录具体收集数和失败原因并停止。
8. 导出所有 Pydantic JSON Schema、枚举字面值、配置 Schema 和默认值到 `migration/baseline/`。
9. 生成 hash golden vectors：
   - 原始字符串、空字符串、中文、Unicode 辅助平面字符；
   - null、布尔、整数、负数、浮点、嵌套字典和列表；
   - `MessageEnvelope`、`ClaimCard`、`ProofCheckpoint`、ProblemContract；
   - 预期 canonical JSON、content hash、semantic hash。
10. 建立 `source-state.csv`、`test-state.csv` 和 `auxiliary-state.csv`，分别导入附件三个映射文件并增加 `status`、`java_path`、`verified_by`、`notes` 列。校验行数必须分别为 142、167、92，三表源路径并集必须恰好等于冻结清单的 401 个文件，不能重复、遗漏或多出。
11. 在目标目录根安全引导 Apache Maven Wrapper **3.3.4 `only-script`**，且不要求全局安装 Maven：
    - 将 Apache Maven 3.9.16 官方二进制 ZIP/TAR 下载到 `TARGET_ROOT/.cache/bootstrap-maven/`，同时下载官方 `.sha512`；SHA-512 校验必须通过。若系统已安装 GnuPG，可再使用官方 `KEYS` 校验 `.asc`，但 PGP 不是额外的强制安装项；
    - 仅使用这份已校验的临时 Maven 执行一次全限定目标 `org.apache.maven.plugins:maven-wrapper-plugin:3.3.4:wrapper`，参数固定为 `-Dtype=only-script -Dmaven=3.9.16 -DdistributionUrl=<官方 Maven 3.9.16 二进制 URL> -DdistributionSha256Sum=<该二进制包 SHA-256>`；不得调用未限定版本的 `wrapper:wrapper`；
    - 生成 `mvnw`、`mvnw.cmd` 和 `.mvn/wrapper/maven-wrapper.properties`，确认 `wrapperVersion=3.3.4`、`distributionUrl` 与 `distributionSha256Sum` 精确正确，且不生成/不提交 `maven-wrapper.jar` 或 `MavenWrapperDownloader.java`；
    - 使用新生成的 `mvnw`/`mvnw.cmd -version` 再次下载并核验 Maven 3.9.16，输出必须显示实际运行版本 3.9.16；
    - 将 Wrapper 插件版本、Maven 版本、官方下载 URL、官方 SHA-512、计算 SHA-256、可选 PGP 结果、生成文件 SHA-256 和下载时间写入 `migration/dependency-lock.yaml`；`.cache/bootstrap-maven/` 必须被 `.gitignore` 排除，不得使用未知镜像重定向、动态版本或未经校验的二进制。
12. 在 `migration/preflight/pom.xml` 建立只用于解析的最小预检 POM，并使用刚引导的 Wrapper 依次执行：

```powershell
.\mvnw.cmd -f migration/preflight/pom.xml -B -ntp validate
.\mvnw.cmd -f migration/preflight/pom.xml -B -ntp help:effective-pom
.\mvnw.cmd -f migration/preflight/pom.xml -B -ntp dependency:go-offline
.\mvnw.cmd -f migration/preflight/pom.xml -B -ntp dependency:tree -Dverbose
```

    预检 POM 必须精确解析：Spring Boot 4.1.0 BOM、Spring Modulith 2.1.0 BOM、Temporal BOM/SDK/testing 1.37.0、JavaFX 25.0.4 的当前平台 classifier、PostgreSQL JDBC、`flyway-core`、`flyway-database-postgresql`、Testcontainers PostgreSQL、Picocli 4.7.7、JGraphT 1.5.3、JNA JPMS 5.19.1、ArchUnit 1.4.2，以及第 4.2 节全部构建/安全插件。生成 effective POM、依赖树、插件解析清单和冲突报告。任何 artifact 缺失、Snapshot、版本漂移、重复类、BOM 冲突或 Java 25 不兼容都使阶段阻塞；不得自行换版本。
13. 精确预检并锁定两个官方容器镜像；不得使用 `latest`：
    - 拉取 `postgres:18.4-bookworm`；记录镜像 ID、目标平台、完整 `RepoDigests` 和选定的 `postgres@sha256:...`；
    - 拉取 `temporalio/temporal:1.8.1`；验证 CLI 报告 1.8.1，并通过启动日志或 `temporal operator cluster system` 验证其内嵌 Server 为 1.31.2；记录镜像 ID、目标平台、完整 `RepoDigests` 和选定的 `temporalio/temporal@sha256:...`；
    - 将两个不可变引用写入 `migration/dependency-lock.yaml`，同时生成不含秘密、可由 Compose 直接读取的 `migration/image-lock.env`，键名固定为 `POSTGRES_IMAGE` 和 `TEMPORAL_DEV_IMAGE`；后续 Compose 只能引用这两个键，禁止再引用可变 tag；
    - 使用 loopback 端口完成最小健康检查，不写 MathProofMesh 业务数据。Temporal 预检必须使用 `server start-dev --ip 0.0.0.0 --port 7233 --headless --db-filename /var/lib/temporal/temporal.db --namespace mathproofmesh --disable-config-file --disable-config-env --log-format json --log-level warn`，宿主机只映射 `127.0.0.1:7233:7233`，健康检查固定为 `temporal --disable-config-file --disable-config-env --address 127.0.0.1:7233 operator cluster health`；
    - 若 Registry、digest、版本核验、只读容器加固或健康检查任一失败，阶段必须阻塞并记录网络、代理、平台和完整错误；不得退回 `latest`、关闭安全参数或换用未知镜像。
14. 对所有原 YAML、`.env.example`、Provider 配置、桌面配置和 proof-control profile 建立字段库存；列出 Java 目标配置键、类型、默认值、秘密来源、未知字段策略、环境变量名和对应验证器。不得在预检中加载真实密钥。
15. 新建 ADR 0001–0008，记录既定架构：Java-first hybrid、模块化单体、PostgreSQL 权威、Temporal 后置、stdio 侧车、首版无 Kafka/Redis/Neo4j、Provider 直接适配、旧哈希兼容。

### 阶段门禁

- 原压缩包和第 0 阶段创建目标目录之前冻结的源文件清单均与预期指纹吻合；
- Python 完整基线严格报告 `759 passed`；若收集数、失败数或跳过数不一致，阶段必须为 `BLOCKED`；
- JDK 25、Git、Docker Compose v2 和 Python ≥ 3.11 的检查脚本可重复执行；
- 临时 Maven 3.9.16 官方分发包已通过 SHA-512 核验；由它调用全限定 Maven Wrapper Plugin 3.3.4 生成 `only-script` Wrapper，并使用 `distributionSha256Sum` 固定 Maven 3.9.16；
- 预检 POM 能离线解析第 4.2 节全部固定 BOM、库、插件及当前平台 JavaFX artifact，依赖树中不存在 Snapshot、未解释版本漂移、重复类、BOM 冲突或 Java 25 不兼容；
- `postgres:18.4-bookworm` 与 `temporalio/temporal:1.8.1` 已分别锁定为完整 `image@sha256:...`；Temporal CLI 1.8.1、内嵌 Server 1.31.2、headless SQLite 持久化和 loopback 健康检查均通过；
- 所有 Pydantic Schema、枚举、默认值、哈希向量、测试库存和配置字段库存已生成；
- `source-state.csv`、`test-state.csv`、`auxiliary-state.csv` 分别恰好有 142、167、92 条源记录；三表源路径并集恰好是冻结清单中的 401 个非 Git 文件，且无重复、遗漏或额外路径；
- 目标目录之外没有新增文件，原 Python 项目逐字节无变化；
- `migration/reports/phase-00.md` 包含命令、退出码、版本、依赖预检、镜像 digest、401 文件覆盖证明和所有失败证据，且结论为 `PASS`。

---

## 第 1 阶段：Maven 多模块骨架、依赖锁定与安全构建

### 目标

建立能够在 JDK 25 上稳定编译的最小多模块工程，先验证框架组合，不迁移领域功能。

### 必做任务

1. 复用并复核第 0 阶段生成的 Maven Wrapper 3.3.4 `only-script` 文件和 Maven 3.9.16 分发校验；将其作为正式工程 Wrapper。不得重新生成不同版本，也不得提交 `maven-wrapper.jar`。
2. 父 POM 使用 Spring Boot 4.1.0 管理基础依赖；导入 Spring Modulith 2.1.0 BOM 和 Temporal 1.37.0 BOM。数据库迁移同时声明 `flyway-core` 与 `flyway-database-postgresql`，不能只加入 Core。
3. 创建五个 Java 模块及独立 Python 侧车目录。
4. 配置 `maven.compiler.release=25`、UTF-8、参数名保留、严格编译警告。
5. 配置 Maven Enforcer：
   - Java 25；
   - Maven 3.9.16；
   - dependency convergence；
   - ban duplicate classes；
   - 禁止 Snapshot；
   - 禁止模块依赖反向。
6. 配置 Surefire/Failsafe、JaCoCo、SpotBugs/FindSecBugs、OWASP Dependency-Check、CycloneDX。
7. 创建最小 Spring Boot `MathProofMeshApplication`，只提供 context smoke test，不开放业务接口。
8. 创建 ArchUnit 测试，固定第 5.2 节依赖方向。
9. 建立 `.gitignore`，至少排除：`.env*`、`target/`、`.cache/`、`.venv*`、数据库数据、Temporal 数据、runs、raw response、API key、桌面打包输出。
10. 生成：

```powershell
.\mvnw.cmd -B -ntp dependency:go-offline
.\mvnw.cmd -B -ntp dependency:tree -Dverbose
.\mvnw.cmd -B -ntp verify
.\mvnw.cmd -B -ntp cyclonedx:makeAggregateBom
```

11. 保存依赖树、插件版本、容器镜像 digest 和 SBOM 到迁移报告，不把本机仓库路径写入发布文件。
12. 不使用 `picocli-spring-boot-starter` 或 `temporal-spring-boot-starter`，避免其传递依赖覆盖 Boot 4.1.0 管理；只使用 Picocli 核心库、Temporal SDK 核心库和显式 Spring 配置。

### 阶段门禁

- 所有模块构建成功；
- 依赖收敛、无 Snapshot；
- Boot context、Modulith 模块扫描和 Temporal test dependency smoke test 成功；
- OWASP 扫描无未解释的高危；
- SBOM 生成；
- 原目录不变。

---

## 第 2 阶段：契约、枚举、严格校验、Canonical JSON 与哈希兼容

### 目标

将 Python 的 `schemas.py`、`task_contracts.py` 及其他稳定数据模型完整迁移为 Java 契约，建立后续所有模块的强类型地基。

### 迁移顺序

#### 2A. 基础工具和枚举

- `StrictContract` 约定；
- `ContractValidationException`；
- `ContractStrings`、`ImmutableCollections`；
- `PythonCompatibleIdGenerator`；
- `PythonIsoTimestampCodec`；
- 所有 `StrEnum`/Literal 值。

#### 2B. 消息、作用域和证据契约

- `QuantifierSpec`、`VariableBinding`；
- `MessageEnvelope`、`MessageReceipt`；
- Evidence、MemoryTier、Verification、RouteRole、MessageType、Priority、ReceiptStatus；
- Message immutable payload、content hash 和 semantic hash。

#### 2C. 问题、策略、尝试、步骤、Claim 和 Checkpoint

- `ProblemContract`、Triage、StrategySet/StrategyCard；
- `ProofAttempt`、`ProofStep`、`ProofDelta`、`ClaimCard`；
- `ProofCheckpoint`、`WorkingProofCheckpoint`；
- 完成状态必须有答案、完成证明不得保留子目标等不变量。

#### 2D. 计算、验证、Meta、综合和运行对象

- Experiment、Computation、Certificate、VerificationReport、MetaReview、FinalProof、Usage、Citation、EvidenceRef 等。

#### 2E. Proof Graph、Proof Control、Inspiration 契约

- Python 中对应 `models.py` 的所有稳定 DTO；
- 此阶段只迁移数据和校验，业务算法留在相应阶段。

### Canonical JSON 与哈希规范

必须精确实现 Python：

```python
if isinstance(value, str):
    raw = value.encode("utf-8")
else:
    raw = json.dumps(value, ensure_ascii=False, sort_keys=True,
                     separators=(",", ":")).encode("utf-8")
sha256(raw).hexdigest()
```

Java 规则：

1. 顶层 String 直接哈希 UTF-8 字节，不加 JSON 引号。
2. 其他值转 JsonNode；对象键按 Unicode code point 序排序，不能直接假定 UTF-16 `String.compareTo` 与 Python 完全等价。
3. 禁止非 ASCII 转义；控制字符按 JSON 规范转义；无空格、无换行。
4. 数字词法必须通过 golden vectors 与 Python 一致；若 Jackson 默认浮点格式不一致，建立专用 Python-compatible number writer，不能改 Python 预期哈希。
5. List 顺序保留；null 明确保留；禁止自动去除默认字段，除非 Python `model_dump` 对该哈希明确不包含。
6. `MessageEnvelope.immutable_payload` 字段必须精确为：`problem_hash`、`source_route_id`、`message_type`、`normalized_statement`、`assumptions`、`conclusion`、`quantifiers`、`dependencies`、`evidence_type`、`memory_tier`。
7. Message semantic hash 精确包含：`assumptions`、`conclusion`、`quantifiers`、`variable_bindings`。
8. ProofCheckpoint hash 排除 `working_notes`、`proof_sketch`、时间和可变投递信息；字段集合和 ProofStep checkpoint payload 与 Python一致。
9. ID 保持 `prefix_` + UUID4 小写十六进制前 12 位；数据库冲突时最多重试 5 次，仍冲突则失败。

### 测试

- 每个契约至少有：合法、缺字段、未知字段、边界、非法状态、JSON round-trip 测试；
- 导入全部 Python Schema fixture；
- Java 读取 Python JSON，重新序列化，业务字段和哈希一致；
- Java 生成 JSON，再由只读 Python compatibility runner 验证；
- 100% 通过 hash vectors。

### 阶段门禁

- 契约库存 100% 映射；
- 所有枚举字面值一致；
- 所有 golden hash 一致；
- 核心契约不得残留 `Map<String,Object>`；
- 契约模块不依赖 Spring。

---

## 第 3 阶段：配置、Profile、题目预检与秘密管理

### 目标

完整迁移 `config.py` 和 `goal_preflight.py`，保证所有默认值、范围、交叉约束和 profile 行为一致。

### 必做任务

1. 使用 Jackson YAML 严格解析；禁止 Spring relaxed binding 直接吞掉未知字段。
2. 建立与 Python 同名的配置 record：`AgentConfig`、`BudgetConfig`、`SchedulerConfig`、`TopologyConfig`、`ProofControlConfig`、`ComputationConfig`、`RuntimeConfig`、`SystemConfig` 及其全部嵌套配置。
3. 逐条迁移所有 Field 范围和 model validator；至少覆盖：
   - budget shares 总和为 1；
   - initial paths ≤ max paths；
   - reasoning effort 需要 thinking；
   - DeepSeek model 标识；
   - hierarchical_sparse 的依赖；
   - active proof control 需要 active proof graph、typed memory、typed communication；
   - fast-lane 禁止自动 Fact 晋升和 sandboxed Python；
   - Lean/Sandbox 镜像必须固定 sha256；
   - Deep Exploration tier 严格递增。
4. 将原配置复制为只读 fixtures，并创建等价 Java profile。不得在配置文件内保存 key。
5. 实现 `SecretValue`：`toString`/日志永远输出 `[REDACTED]`；按需获取环境变量；HTTP 调用结束后尽快丢弃引用。
6. 实现 `ProviderEndpointPolicy`：
   - 生产默认只允许 HTTPS；
   - 只允许配置文件中的管理员 allowlist；
   - 拒绝用户请求覆盖 `base_url`；
   - 拒绝私网、环回、link-local、file/jar 等 scheme，开发 Mock profile 除外；
   - 禁止自动跟随跨主机重定向。
7. 实现 Goal Preflight：原题完整性 hash、题型、交付物、语言和硬约束；原题为权威文本，翻译/语义视图只能作为 sidecar。
8. 配置输出只允许脱敏导出。

### 阶段门禁

- 所有原 YAML fixture 被 Java 成功解析；
- Python 与 Java 脱敏后的配置语义一致；
- 每个 Python 配置 validator 至少一个 Java 测试；
- 秘密不出现在日志、异常、JSON 或快照。

---

## 第 4 阶段：PostgreSQL、Flyway、Artifact、Outbox/Inbox 与运行租约

### 目标

把文件级权威状态迁移为 PostgreSQL 事务状态，同时保留内容寻址 Artifact 和旧运行只读兼容入口。

### 必做任务

1. 创建 PostgreSQL Docker Compose、开发/测试数据源和 Testcontainers 测试；应用依赖同时包含 `flyway-core` 与 `flyway-database-postgresql`。
2. 使用 Flyway；已应用迁移永不修改，只能新增版本。
3. 使用 Spring JDBC/JdbcClient；禁止 JPA/Hibernate。
4. 建立第 10 章定义的表、约束、索引和状态列。
5. 所有运行内记录带 `run_id`；查询必须显式按 run 隔离。
6. IDs 使用 TEXT 以兼容 Python 前缀 ID 和用户 run ID；哈希为 64 位小写十六进制并有 check constraint。
7. 实现内容寻址 `ArtifactStore`：
   - 路径 `artifacts/sha256/<前两位>/<完整hash>`；
   - 先写同目录临时文件、fsync、原子移动；
   - 校验实际字节 hash；
   - 拒绝绝对路径、`..`、symlink/reparse point；
   - 默认大小上限和配额；
   - 数据库保存媒体类型、大小、来源、保留策略和 hash。
8. 实现事务 Outbox/Inbox：领域状态与 outbox 同事务提交；Relay 使用 `FOR UPDATE SKIP LOCKED`；消费者以 `(consumer_name,event_id)` 唯一去重。
9. 实现 `RunLease` 与 fencing token：同一 `run_id` 只允许一个活动 owner；所有关键写入校验 fencing token，避免双进程同时恢复。
10. 实现乐观锁 `version`，冲突不得最后写覆盖。
11. 建立 append-only `event_log`，禁止业务代码更新/删除历史事件。
12. 文件型旧 store 尚不导入，只建立只读 Port。

### 阶段门禁

- Flyway 从空库一次成功；重复启动不重复迁移；
- Repository 集成测试在真实 Postgres Testcontainer 通过；
- Outbox 崩溃点测试、Inbox 重复测试、租约争用测试通过；
- Artifact 路径穿越、symlink、hash 不符和超限测试通过；
- 不存在拼接 SQL。

---

## 第 5 阶段：类型化通信、路线注册、稀疏拓扑和可靠回执

### 目标

完整迁移 `communication/` 和核心拓扑逻辑，把消息发送实现为可恢复、可审计的领域状态机。

### 核心类型/服务

- `RouteRegistry`、`RouteDescriptor`、`RouteMember`；
- `MessageBroker`、`MessageAdmissionPolicy`、`MessageDeliveryService`；
- `MessageReceiptService`、`MessageUtilityVerifier`；
- `SparseTopologyRouter`、`DeliveryKey`；
- 对应 Repository 和领域事件。

### 准入顺序

Broker 必须按固定顺序执行并返回稳定拒绝码：

1. Schema 和长度；
2. problem hash；
3. source agent/route/role 所有权；
4. target route 存在且在允许邻居/桥接范围；
5. TTL；
6. Artifact 引用安全与存在性；
7. 量词顺序、变量绑定和作用域；
8. 依赖存在、无环、未被反例失效；
9. Evidence 与 MemoryTier 合法；
10. 作者/Reviewer 独立；
11. 内容 hash；
12. 内容级去重；
13. 优先级、速率和 inbox 容量；
14. 持久化 message、delivery 和 outbox。

### Exactly-once 领域语义

- 网络只能保证至少一次；系统保证**领域效果幂等一次**。
- Delivery 状态必须区分：`queued`、`delivered`、`prompt_consumed`、`acknowledged`、`expired/rejected/deferred`。
- 在发起 LLM 前，先原子写入 `prompt_consumed` 和 provider request artifact；恢复时已 consumed 的 delivery 绝不再次插入新 Prompt。
- `MessageReceipt.accepted` 只表示接收者解析并接受，不表示消息产生证明效用。
- `actually_used` 只能由通过验证的 ProofDelta/Checkpoint 与 Proof Graph 实际引用核验后写入。
- `referenced_in_step_ids` 和 `claimed_closed_obligation_ids` 只是待核验声明。

### 阶段门禁

- Python 消息、Broker、路由、优先级、liveness、拓扑测试全部映射；
- 重复发送、崩溃恢复、已 consumed 未 ACK、跨 run 泄漏和无效作用域测试通过；
- 不存在任何绕过 Broker 的跨路线数学上下文读取。

---

## 第 6 阶段：Fact/Insight/Negative 三级记忆与 Proof Obligation Graph

### 目标

完整迁移 `memory.py` 和 `proof_graph/`，形成可信知识和证明依赖权威模型。

### 三级记忆规则

- Fact：必须满足证据强度、独立验证、作用域、依赖闭包、无有效反例和置信度门槛；
- Insight：候选想法、局部实验、未完成策略；不能当定理使用；
- Negative：反例、失败结论、不可行路线和经重放确认的否定信息。

晋升/降级必须由 `MemoryPromotionPolicy` 和 `MemoryInvalidationService` 执行；其他模块无直接改状态权限。

### Proof Graph

迁移义务类型、边类型、Claim、桥梁、矛盾、等价、strengthens/weakens、construction 和 closes 关系。实现：

- 环检测和拓扑排序；
- 依赖闭包；
- proof debt 和核心 bottleneck；
- 冲突和重复机制检测；
- Counterexample 递归传播；
- freeze/minimal subgraph；
- reopening 和版本审计。

PostgreSQL 是权威；JGraphT 是每次查询/调度的内存投影。禁止首版引入 Neo4j。

### 事务要求

反例导致失效必须在一个事务中：写 Negative → 标记原 Claim invalidated → 递归标记后继 `needs_reverify` → 重开受影响义务 → 写 event/outbox。

### 阶段门禁

- 记忆、typed memory、proof graph 测试全部迁移；
- 环、缺失依赖、作者自审、有限实验冒充 Fact 均被拒绝；
- 反例传播可重复执行且结果相同；
- 大图中不存在每节点重复全表查询的 N+1 实现。

---

## 第 7 阶段：Agent Runtime、Provider 适配、预算、限流和持久化调用状态

### 目标

完整迁移 `agents.py`、`llm/`、`prompts.py` 和 Agent 相关预算能力。

### Provider 适配

建立 `LlmProvider` 接口和实现：

- `DeepSeekProvider`；
- `AnthropicProvider`；
- `GeminiProvider`；
- `OpenAiCompatibleProvider`；
- `MockProvider`。

使用 JDK `HttpClient`、Jackson 和自写有界 SSE parser；不引入 Spring AI。每个适配器必须明确：URL、鉴权头、请求字段、streaming、usage、reasoning 内容、request ID 和错误映射。

### Agent Runtime

1. Java 25 虚拟线程执行 I/O；全局和每 Agent 使用公平 Semaphore；RPM 使用滑动窗口/令牌桶。
2. `AgentPool` 按角色、专长、提供商、负载和 trust 选择，禁止同一作者作为 Reviewer。
3. `StructuredAgentRunner` 固定流程：保存脱敏 prompt artifact → 预算预留 → 持久化 call planned → 外部调用 → 原始响应 artifact → 提取首个平衡 JSON → 严格契约解析 → 有界 JSON repair → 记录 usage/成本/延迟。
4. JSON repair 只能修字段/类型/包裹，Prompt 明确禁止改变数学内容。
5. `provider_call` 状态：`planned → dispatched → streaming → succeeded/failed/ambiguous/cancelled`，结果应用另有 `applied_at` 和唯一键。
6. 断线时若远端结果未知，标记 ambiguous；按配置决定是否重试，并记录潜在重复费用，不伪称 exactly once 外部调用。
7. 401/403 对同一凭据不得重试；只有 KeyPool 已明确配置另一把有效 key 且轮换策略允许时，才可切换凭据进行一次受审计尝试。408/409/429/5xx 和网络错误才进入有界退避；尊重 Retry-After。
8. 熔断状态、失败计数和冷却时间持久化。
9. 用量总账可从 provider_call 重算并与运行 usage reconciliation。
10. 默认禁止真实调用；测试只使用 Mock HTTP/SSE 和录制 fixture。

### 阶段门禁

- 五类 Provider 的 Mock 协议测试通过；
- SSE 分片、尾块、空闲超时、最大响应、取消和重连测试通过；
- 预算、每 key 并发、RPM、failover、熔断和 usage reconciliation 通过；
- secrets/redaction 扫描通过；
- 无付费调用。

---

## 第 8 阶段：计算引擎、证据门禁与受限 Python 侧车

### 目标

迁移 `computation/`、`tools.py` 和 `critical_calculations.py`，在正确性和安全性优先的前提下实现 Java 原生与 Python 侧车组合。

### Java 原生优先迁移

- modular exhaustive；
- bounded integer search；
- graph certificate；
- recurrence check；
- bounded greedy sequence；
- candidate period check；
- exact geometry（BigInteger/BigDecimal/有理数，禁止 double 冒充精确证明）；
- number theory 的确定性小规模检查；
- AST/schema/预算/证据门禁。

### 侧车保留

- SymPy simplify/equivalent/factor；
- 与 Python/Z3 兼容性高度相关的初始 SMT/实不等式检查；
- 仍需原库语义的安全 handler。

### 侧车固定协议

- 目录完全位于 `python-compute-service/`；
- 依赖固定并生成 lock/hash；
- 使用 UTF-8、每行一个 JSON-RPC 2.0 对象的 stdin/stdout；
- 字段：`protocol_version`、`request_id`、`method`、`params`、`limits`；响应含 `result/error`、`certificate`、`stdout_hash`、`tool_version`、`cpu_ms`；
- Java Worker Pool 启动受控进程，不开放 TCP 端口；
- 环境变量白名单，不传 API key；
- stderr 有界采集并脱敏；
- 超时立即终止整个进程树；
- 每次返回重新校验 request ID、Schema、大小和证书；
- 任意代码执行默认关闭。

### Sandboxed Python

只有显式 `sandboxed_python_enabled=true` 才可启用，并必须通过 pinned digest Docker 镜像、无网络、只读根文件系统、非 root、CPU/内存/PID/时间限制运行。模型给出的代码先过 AST 白名单；属性访问、dunder、导入、文件、网络、进程、反射、动态执行均拒绝。

### 证据语义

- 未找到反例不是证明；
- bounded experiment 不能进入 Fact；
- finite enumeration 只有覆盖原命题完整有限域时才是 exhaustive certificate；
- Counterexample 经独立 replay 后进入 Negative；
- 计算结果必须绑定原命题映射，不能只保存数字输出。

### 阶段门禁

- 计算与 critical gate Python 测试全部映射；
- Java 原生与 Python 原 handler 差分一致；
- 侧车协议畸形、超时、进程崩溃、超大输出和注入测试通过；
- sandbox 默认关闭；
- 计算缓存以 canonical identity 去重且 run 隔离。

---

## 第 9 阶段：验证升级、能力画像、盲终审、Mutation 与 Formal Micro-Cert

### 目标

完整迁移 `verification/` 和 `context_policy.py`，建立与路线热度无关的风险驱动验证。

### 固定验证阶梯

```text
本地确定性检查
→ 新上下文同模型盲审
→ 对抗 Prompt 盲审
→ 可选异构模型/Provider
→ 精确工具或形式化微证书
```

### 必做任务

1. Structural verifier 必须先于 Detailed verifier；结构失败默认不消耗详细审稿。
2. Reviewer 不能是作者；最终盲包不含 agent、route、分数、自信、投票、原 Prompt、私有推理或内部路径。
3. 盲包只含原题、清洗证明、明确引用证据和必要 Negative 边界。
4. `ValidationEscalator` 根据风险、分歧、Fact 晋升意图和 final proof 选择阶段。
5. 无异构 Provider 时，安全降级为对抗盲审 + 工具/形式化检查并记录诊断。
6. Formal backend 只定义接口；Lean 默认关闭。编译失败产生形式化义务，不能自动反驳自然语言 Claim。
7. Mutation harness 固定执行删除假设、反转量词、改符号、断依赖、插循环等；假阳性更新 verifier capability cell。
8. Claim 验证状态不得被低权威结果静默提升或回退。

### 阶段门禁

- verification、blind、formal、capability、context 测试全部迁移；
- 盲包泄漏静态扫描为零；
- Mutation 假阳性/首错定位测试通过；
- author/referee、Fact promotion 和 final proof 高风险门禁不可绕过。

---

## 第 10 阶段：Goal–Plan–Failure–Utility Proof Control

### 目标

完整迁移 `proof_control/` 和 `proof_identity.py`。此阶段必须分 10A–10G 顺序完成，每个子阶段均有测试，不允许直接搬运 7,000 行控制器。

### 10A：状态、模型和语义视图

迁移 models、state、semantic_view、semantic_profile、domains。保持原题 authority、双语审计 sidecar、legacy 语义视图 quarantine 和稳定数学 identity。

### 10B：目标、作用域、推理风险和语义质量

迁移 goal_alignment、scope_guard、inference_risk、proof_roles、semantic_quality。必须覆盖十类推理风险、量词/定义域/强弱命题、minimal sufficiency 和 obligation domain separation。

### 10C：策略蓝图、路线准入、任务与依赖命名空间

迁移 strategy_blueprint、route_target、tasks、dependencies。策略必须先形成可审计蓝图再准入；dependency ref 使用显式 namespace，不得以裸字符串混淆 Claim、Obligation、Checkpoint 和 external theorem。

### 10D：失败、瓶颈、Near Miss、Realizer 与 Induction

迁移 failure_control、bottleneck、near_miss、realizer、induction。区分 execution/plan/strategy 失败；Near Miss 不能冒充证明；abstract-realizer 修复保留；归纳测度必须可执行并有激活条件。

### 10E：Common-mode 与独立假设挑战

迁移 common_mode，包括传递依赖 closure、family identity、live route cutset、多语言文本匹配、Assumption Challenger 和独立审稿。语义冲突必须 fail closed。

### 10F：Falsification、Countermodel、Message Utility 和 Gates

迁移 falsification、message_utility、gates、claim_lifecycle。Countermodel 任务必须真正执行、持久化结果并独立审阅；不可执行任务 deferred；零效用 broadcast 保持局部；continue/synthesis gate 依据可验证证据。

### 10G：幂等 Action Dispatcher、Resume Policy 和 Controller Facade

迁移 action_dispatcher、resume_policy、controller。Controller 只组合子服务；每个动作具有 `action_key` 唯一约束，重复恢复不重复执行；meta pivot exactly once；terminal resume 不产生 provider call。

### 权限边界

Proof Control：

- 无权直接写 Fact；
- 无权直接关闭 Proof Obligation；
- 无权篡改原题或数学 hash；
- 只能产生建议、任务、风险、门禁决定和经授权服务执行的动作；
- off/shadow/active 三模式语义必须保持。

### 阶段门禁

- proof_control 相关 46 个测试文件及其用例全部迁移；
- Python 数学对象 hash 不因启用 Proof Control 改变；
- 所有动作重复执行无副作用；
- common-mode、countermodel、resume 和 terminal zero-call 测试通过；
- Controller 不成为新的上万行 God class。

---

## 第 11 阶段：Inspiration Engine

### 目标

完整迁移 `inspiration/`，保留灵感生成的多机制、独立审稿、预算准入、Outcome Learning 和跨运行学习。

### 必做模块

- Representation Switchboard；
- Verified Local Analogy；
- Auxiliary Construction Inventor；
- Invariant/Monovariant Hypothesis；
- Reverse Goal Analyzer；
- Persistent Meta Strategist；
- Surprise Budget / seeded mutation；
- Novelty Signature、Mechanism Ontology 和 Referee；
- Inspiration Composer；
- Assignment、Context、Trigger Policy；
- Outcome Ledger、Credit Attribution、UCB minimum exploration；
- Verified Experience Distiller、Negative Analogy Library；
- Project-local Cross-run Learning。

### 固定规则

1. `off` 不运行；`shadow` 只记录，不改变调度/图/记忆/预算；`active` 通过全部门禁后才可附着或新建路线。
2. Proposal 作者与 Inspiration Referee 必须不同。
3. Inspiration 永远不能直接晋升 Fact 或关闭 Checkpoint。
4. Scheduler admission 在模型调用前执行；每 trigger 路线 cap 跨机制统一计算。
5. Novelty 表示不同，不表示正确；未知机制标签只有弱相似度权重。
6. Composer 的源提案必须独立审阅、目标邻域连通、机制互补、成本合规且至少一项通过快速证伪。
7. UCB 只在可调度候选池中计算，并保证 minimum exploration；结果 credit 绑定具体 proposal/Fact/citation。
8. 跨运行学习只限项目目录/数据库 tenant，不建立全局用户画像。

### 阶段门禁

- 23 个 Inspiration 测试文件完整迁移；
- shadow 模式状态无业务突变；
- 自审、预算外调用、重复 proposal、越过 cap 和误晋升 Fact 全部被拒绝；
- Outcome/UCB 在固定 seed 下确定性复现。

---

## 第 12 阶段：路线团队、Continuation、深挖、跨路线、综合与数据库驱动运行器

### 目标

在尚未引入 Temporal 之前，先建立可完整运行的 Java 应用服务，迁移 `teams/`、`route_pipeline.py`、`continuation.py`、各 `*_phase.py`、`budget.py`、`deep_exploration.py` 和 `stall_recovery.py`。

### 固定运行阶段

```text
冻结题目
→ Triage
→ 策略生成与多样性选择
→ 路线准入/团队分配
→ 隔离并行探索
→ Working Delta
→ 独立验证
→ Committed Checkpoint
→ Claim 提取/记忆/Proof Graph
→ 跨路线 Broker
→ Inspiration
→ Meta Review
→ WIDEN/DEEPEN/VERIFY/REVISE/SYNTHESIZE/STOP
→ 综合
→ 盲终审
```

### 必做任务

1. Route Team 角色：Prover、Skeptic、Tool Specialist、Referee；按需启用且作者隔离。
2. 首轮路线隔离，只读本策略和相关已验证事实，不共享原始 transcript/reasoning。
3. Continuation 每段限制新步骤/Claim；Working Checkpoint 不是全局证据；只有通过 Reviewer 才 committed。
4. 最新 checkpoint CAS：父节点必须等于当前 latest，segment 只能 +1，problem/path/strategy identity 不变。
5. 允许从父 checkpoint rollback 并分支；Rejected Delta 保留审计但不推进。
6. Deep Exploration 按 evidence、novelty signature、剩余预算和 tier 控制；同 signature 有界，distinct signature 可并行。
7. Stall Recovery 使用认证进展、bottleneck 和 meta pivot；不得用时间到期假装数学进展。
8. Adaptive Budget 保持 breadth/depth/verification/synthesis shares、失败分类和软预算。
9. Cross-route 只经 Broker；Synthesis 只读验证过的依赖闭包、精选 Negative 和匿名包。
10. 实现 `InProcessRunCoordinator`，可在 Mock profile 完成全流程、暂停并从数据库恢复；它是 Temporal 前的行为参照，后期保留用于单元/本地测试。

### 阶段门禁

- 路线、continuation、deep exploration、scheduler、synthesis 测试全部迁移；
- Mock 端到端完成、失败、partial、budget exhausted、暂停/恢复均可复现；
- 无跨路线旁路；
- 重启后只从 committed checkpoint 继续；
- 同一 run 双 coordinator 被租约阻止。

---

## 第 13 阶段：Temporal Durable Workflow、崩溃重放和 Exactly-once 应用语义

### 目标

将 `orchestrator.py` 的阶段次序包装为 Temporal 工作流，而不是把业务逻辑复制到 Workflow 中。

### 固定本地服务与工作流

本地人工运行和需要真实 Service 的集成测试只允许使用第 0 阶段锁定的 `temporalio/temporal@sha256:...`。该镜像的 tag 基线是 CLI `1.8.1`，其内嵌 Server 为 `1.31.2`。它只用于本地开发和 CI，不是生产部署方案；生产只能选择 Temporal Cloud，或另行编写并审批自托管生产 ADR，不能直接复用开发服务。

Codex 必须创建 `compose/temporal-dev.yaml`，其语义固定如下，不得省略安全边界：

```yaml
services:
  temporal-dev:
    image: ${TEMPORAL_DEV_IMAGE:?missing TEMPORAL_DEV_IMAGE}
    command:
      - server
      - start-dev
      - --ip
      - 0.0.0.0
      - --port
      - "7233"
      - --headless
      - --db-filename
      - /var/lib/temporal/temporal.db
      - --namespace
      - mathproofmesh
      - --disable-config-file
      - --disable-config-env
      - --log-format
      - json
      - --log-level
      - warn
    ports:
      - "127.0.0.1:7233:7233"
    volumes:
      - temporal-dev-data:/var/lib/temporal
    read_only: true
    tmpfs:
      - /tmp
    cap_drop:
      - ALL
    security_opt:
      - no-new-privileges:true
    healthcheck:
      test: ["CMD", "temporal", "--disable-config-file", "--disable-config-env", "--address", "127.0.0.1:7233", "operator", "cluster", "health"]
      interval: 2s
      timeout: 2s
      retries: 30
      start_period: 5s
volumes:
  temporal-dev-data: {}
```

启动脚本固定读取 `migration/image-lock.env`：

```text
docker compose --env-file migration/image-lock.env -f compose/temporal-dev.yaml up -d --wait
docker compose --env-file migration/image-lock.env -f compose/temporal-dev.yaml exec -T temporal-dev temporal --disable-config-file --disable-config-env --address 127.0.0.1:7233 operator cluster health
```

停止脚本默认执行 `down` 而不删除卷；只有显式的 `temporal-dev-reset` 命令才能在二次确认后执行 `down -v`。若官方镜像不能在上述 `read_only`、`tmpfs`、`cap_drop` 和 `no-new-privileges` 下运行，阶段保持 `BLOCKED`，先记录其实际写路径和镜像用户；不得静默移除安全参数。

单元测试和 Workflow replay 测试优先使用 `temporal-testing`，不依赖常驻服务。

固定工作流只有：

- `MathProofMeshSolveWorkflow`：运行级状态和阶段次序；
- `RouteExplorationWorkflow`：每条路线的 continuation/验证循环；
- Activities：Preflight、Plan、AgentCall、Compute、Broker、Memory、ProofGraph、Verify、Synthesize、FinalReview、Persist、Report。

v0.8.0 **禁止另建 `FinalReviewWorkflow`**；最终盲审由 `MathProofMeshSolveWorkflow` 调用一个或多个幂等 `FinalReviewActivity` 完成，避免没有收益的额外 Child Workflow。后续只有在独立生命周期、独立 Continue-As-New 或单独取消语义被测试证明必要时，才能通过新 ADR 增加该 Workflow。

### 确定性规则

Workflow 中禁止：

- Spring Repository/JDBC；
- HTTP/LLM；
- 文件和 Python 进程；
- `System.currentTimeMillis`、随机 UUID、无序集合迭代结果；
- 线程、锁、虚拟线程和非 Temporal Future；
- 读取环境变量或动态配置。

全部 I/O 在 Activity；Workflow history 只保存 run/route/checkpoint/action ID 和小型决策，不保存完整 Prompt、Proof、raw response 或秘密。

### 消息接口

- Signal：`pause`、`resume`、`cancel`、`wakeRoute`；
- Update：`increaseBudget`、`submitAuditedDirective`，必须先验证并返回结果；
- Query：`status`、`currentStage`、`routeSummary`、`budgetSummary`。

### 恢复与版本

1. Activity 有明确 start-to-close、schedule-to-close、heartbeat timeout 和 retry policy。
2. 长 Agent/计算 Activity 心跳只包含 ID 和安全进度，不含私有推理。
3. Workflow replay 测试必须通过；代码演化使用 Temporal versioning/patch marker，不得破坏历史。
4. 使用 Continue-As-New 控制 history；数据库 checkpoint 是数学权威，Temporal history 是调度权威。
5. 领域写入用 idempotency/action key、Inbox 和 fencing token；Temporal Activity 重试不能重复产生业务效果。
6. 本地测试使用 `temporal-testing`；真实 Service 测试与本地人工运行使用上述 digest-pinned、headless、SQLite 持久化的受控 dev server，且只绑定 loopback。

### 故障注入

至少覆盖：

- Activity 成功后 ACK 前 Worker 崩溃；
- Provider 响应到达但 DB 提交前崩溃；
- Prompt consumed 后进程崩溃；
- Checkpoint commit 后 Workflow task 失败；
- Continue-As-New 前后重启；
- 同一 Signal/Update 重复发送；
- Worker 更换和跨 Agent failover；
- terminal resume 零 provider call。

### 阶段门禁

- `TestWorkflowEnvironment` 全部测试通过；
- Workflow replay/determinism 检查通过；
- 故障注入不重复应用消息、Checkpoint、Control Action 或 Fact；
- In-process 与 Temporal Mock 行为等价；
- `compose/temporal-dev.yaml` 只能使用 `TEMPORAL_DEV_IMAGE=temporalio/temporal@sha256:...`，服务 headless、只读、无额外 capability，仅映射 `127.0.0.1:7233`，持久化重启和健康检查通过；
- Temporal 服务不暴露公网，开发镜像未被描述或打包为生产部署方案。

---

## 第 14 阶段：REST/SSE、CLI、活动时间线、报告和可观测性

### 目标

迁移 `server.py`、`cli.py`、`activity.py`、`reasoning_trace.py`、`report.py` 和 Mock demo。

### REST 兼容端点

必须保留：

- `GET /health`
- `POST /solve`
- `POST /resume`
- `POST /solve/stream`
- `POST /resume/stream`

新增只读端点：

- `GET /runs/{runId}`
- `GET /runs/{runId}/activity`
- `GET /runs/{runId}/routes`
- `GET /runs/{runId}/proof-graph`
- `GET /runs/{runId}/artifacts/{hash}`（权限、类型和大小受控）

### 安全规则

- 服务默认绑定 `127.0.0.1`；
- `/health` 不触发模型；
- 除 health 外默认 Bearer token；常量时间比较；
- CORS 默认关闭；请求体和并发有界；
- 请求不能覆盖 Provider URL、API key、sandbox image 或文件路径；
- SSE 设置 `X-Accel-Buffering: no`、event ID、heartbeat 和 Last-Event-ID 续读；
- SSE 只发阶段、Agent ID、耗时、状态、脱敏摘要和结果引用，不发 Prompt/raw reasoning/key。

### CLI

Picocli 核心库实现：

- `solve <problem-file> --config --run-id`
- `resume <run-id> --config`
- `demo`
- `probe --config [--completion]`
- `serve --config --host --port`

禁止使用 Picocli Spring Boot starter；命令由 server 模块显式装配。

### 可观测性

- Micrometer 指标：调用、token、成本、延迟、错误、队列、路线、消息、Checkpoint、计算、租约；
- OpenTelemetry trace ID 贯穿 API、Workflow、Activity、Provider 和 DB event；
- Actuator 只在管理端口/loopback 暴露，敏感端点关闭；
- 日志结构化、脱敏，不持久化私有 chain-of-thought。

### 阶段门禁

- 原 server/CLI/activity 测试迁移；
- REST 契约和 SSE 断线续读测试通过；
- 认证、限流、超大 body、路径穿越和日志泄漏测试通过；
- CLI 与 HTTP 对同一 Mock 任务结果一致。

---

## 第 15 阶段：JavaFX 桌面端、DPAPI 和本地打包

### 目标

迁移 `desktop/`，在不重写前端业务 UI 的情况下提供 Java 桌面应用。

### 固定实现

1. JavaFX 25.0.4 WebView 加载复制到新目录的 HTML/JS/CSS；禁止运行时引用原目录。
2. Desktop 启动内嵌 Spring Boot server，绑定随机 loopback 端口，并将一次性会话 token 注入内存；WebView 不读取 API key。
3. UI 只通过本地 HTTP/SSE 调用，不直连 Repository。
4. Windows 使用 JNA 5.19.1 调用 DPAPI；密文与 entropy 元数据本地保存；日志/导出不含明文。
5. macOS/Linux 首版只支持环境变量或明确的外部 secret provider，不把明文写入设置文件。
6. WebView 禁止任意外部导航、文件 URL、下载和 devtools 生产开启；Content Security Policy 限制到本地资源。
7. 运行删除使用安全回收/确认机制；不得允许任意路径删除。
8. 使用 JDK `jpackage` 生成 Windows 安装包/便携目录；构建脚本固定依赖并生成 checksum。

### 阶段门禁

- JavaFX UI smoke、server lifecycle、SSE、resume 和设置测试通过；
- DPAPI round-trip、错误用户/损坏密文、日志泄漏测试通过；
- 安装包在干净 Windows 环境启动；
- 原 UI 关键功能和拓扑/时间线视图保留。

---

## 第 16 阶段：旧 Python Run 只读导入、版本迁移和双实现差分验证

### 目标

支持读取旧 `runs/` 和 v0.7/v0.8.x checkpoint，证明 Java 版本没有在迁移中丢失关键行为。

### 必做任务

1. `LegacyRunImporter` 只读打开用户指定旧 run 目录；先复制到 staging 或逐文件 hash，绝不修改原目录。
2. 校验 problem hash、Artifact hash、Checkpoint 父子链和 latest pointer；不可信路径/外部引用隔离。
3. 迁移顺序：v0.7 → v0.8.0 sidecar → v0.8.1 exactly-once/semantic state → v0.8.2 checkpoint/依赖 namespace。
4. Legacy 未审计 Claim 进入 quarantine，不自动变 Fact；旧 receipt/claim 旁路不得复活。
5. 导入必须幂等：同 run 重复导入结果相同，使用 import manifest/hash 唯一键。
6. 建立 Shadow Comparator：
   - 使用相同 Mock/recorded fixtures；
   - 比较 ProblemContract、策略、消息准入、投递、Memory、Graph、Checkpoint、恢复、usage 和最终状态；
   - 自然语言允许仅在声明的非确定字段上差异；结构、哈希和状态不得差异。
7. 每个 Python 测试文件在 `test-state.csv` 中标记 `ported`、`differential` 或 `not_applicable_with_reason`；每个辅助文件在 `auxiliary-state.csv` 中标记 `copied_verified`、`translated_verified`、`reimplemented_verified` 或 `baseline_only_verified`。任何 `not_applicable`/`baseline_only` 都必须有逐文件理由和验收证据，不能用于省略仍有效功能。
8. 终止 run 恢复必须零 Provider 调用；非终止 run 从 committed checkpoint 继续。

### 阶段门禁

- 所有 v0.x migration/compatibility 测试通过；
- 典型旧 run 导入、重复导入、损坏数据、外部路径和 quarantine 测试通过；
- 142 个源文件/资源全部 `migrated/verified`；
- 167 个测试/测试支持文件全部有明确完成状态，其中 164 个测试模块的 707 个显式测试函数均已映射；
- 92 个配置、文档、基准、打包、脚本和元数据文件全部按辅助映射完成并验证；三份状态表合计 401 行，和冻结源清单逐路径一致；
- Shadow 差分报告无未解释关键差异。

---

## 第 17 阶段：全面加固、性能验证、发布和最终验收

### 目标

将功能完整的迁移版提升到可发布状态，完成安全、故障、性能、运维和文档闭环。

### 全量测试矩阵

- 单元、契约、属性、参数化；
- PostgreSQL Testcontainers；
- Temporal TestWorkflowEnvironment；
- Provider Mock/SSE；
- Python sidecar；
- REST/SSE/CLI；
- JavaFX smoke；
- Legacy import；
- 故障注入；
- 全量 Python 基线复跑和源不可变检查。

### 安全门禁

1. OWASP Dependency-Check：CVSS ≥ 7 阻断；误报抑制必须有 CVE、理由、负责人和到期日。
2. SpotBugs/FindSecBugs：无 High confidence 安全问题。
3. SBOM 和许可证清单完整；禁止未审核强 copyleft/不兼容许可证进入发布包。
4. Secret 扫描无 key/token；测试 fixture 只用明显假值。
5. SSRF、SQL 注入、路径穿越、Zip Slip、反序列化、SSE 注入、日志注入、Prompt/tool injection 和资源耗尽测试通过。
6. Docker/Temporal/Postgres 默认仅 loopback；生产文档要求 TLS/mTLS、鉴权、最小权限和备份。

### 覆盖率门禁

- `contracts` 与核心不变量类：行覆盖 ≥ 90%，分支 ≥ 85%；
- `core` 总体：行 ≥ 85%，分支 ≥ 75%；
- `server/desktop` 可测试业务代码：行 ≥ 70%；
- 哈希、消息门禁、记忆晋升、反例传播、Checkpoint CAS、租约、Outbox/Inbox、Control Action 和 Workflow 决策路径必须 100% 关键场景覆盖。

### 性能/资源基准

在报告中记录硬件和 JVM 参数，并至少测试：

- 10,000 条消息准入/去重/投递，无 O(n²) 退化；
- 100 条并发 Mock Agent 调用，有界并发、无死锁、无泄漏；
- 大型 Proof Graph 依赖闭包、反例传播和 debt 计算；
- 1,000 个 Checkpoint/Outbox 重试；
- Python worker pool 冷启动/热调用；
- SSE 长连接和恢复；
- Temporal 多路线 replay/Continue-As-New。

不设脱离硬件的虚假绝对指标；以第一个通过的基准作为 reference，后续同机回归不得超过 20%，超过必须解释和批准。

### 发布任务

1. 将版本改为 `0.8.0`，禁止 `SNAPSHOT`。
2. `./mvnw clean verify`、SBOM、安全扫描、桌面打包全部通过。
3. 生成 server 可运行包、CLI 启动脚本、桌面安装包、Python sidecar lock、数据库迁移、Docker Compose、checksum。
4. 文档完成：安装、配置、SQL、Temporal、Provider、备份/恢复、升级、导入、故障处理、安全和限制。
5. 生成 `MIGRATION_COMPLETION_REPORT.md`：功能矩阵、source/test/auxiliary 三类覆盖、401 文件闭环、已知限制、差异和证据。
6. 最后一次验证原 Python 全量 759 tests 和源 manifest 不变。

### 最终门禁

全部为 PASS 才可交付：

- 功能、恢复、兼容、安全、性能和发布门禁；
- 无必需 TODO/占位；
- 无真实 key；
- 无原目录变化；
- Java 项目可在干净环境按 README 一次启动；
- Mock 完整求解和旧 run 恢复演示成功；
- `source-state.csv` 为 142/142 verified，`test-state.csv` 为 167/167 verified，`auxiliary-state.csv` 为 92/92 verified，三表路径并集与 401 文件冻结清单完全一致。

---

# 9. PostgreSQL 数据模型规范

## 9.1 通用约定

- 表名 `snake_case`；
- ID 使用 `text`；
- hash 使用 `char(64)` + 小写十六进制 check；
- 时间使用 `timestamptz`；
- 完整契约保存 `payload jsonb`，关键检索字段关系化；
- 可变聚合带 `version bigint`；
- 所有 run-owned 表带 `run_id` FK 和索引；
- 不使用数据库 enum，使用 text check，便于版本迁移；
- 关键唯一键同时包含 `run_id`，防止跨运行误去重；
- Repository 必须使用参数化 SQL。

## 9.2 必建表与关键字段

| 表 | 关键字段与约束 |
|---|---|
| `run` | `run_id PK`, `problem_hash`, `status`, `current_stage`, `config_payload`, `fencing_token`, `version`, timestamps |
| `problem_contract` | `run_id PK/FK`, `integrity_hash`, original/normalized text, kind, language, payload |
| `strategy` | `(run_id,strategy_id) PK`, mechanism signature, status, score, payload |
| `route` | `(run_id,route_id) PK`, strategy_id, status, latest_checkpoint_id, failure/stagnation, version |
| `route_member` | `(run_id,route_id,agent_id,role) PK`, assigned_round |
| `proof_attempt` | attempt identity, route/path, author, status, payload, artifact refs |
| `proof_step` | `(run_id,step_id) PK`, checkpoint/delta, ordinal, normalized hash, payload |
| `claim` | `(run_id,claim_id) PK`, content_hash, status, tier, author, confidence, payload, version |
| `claim_dependency` | typed source/target refs，唯一边，防自环 |
| `message` | `(run_id,message_id) PK`, content_hash, source, type, priority, round/ttl, payload |
| `message_delivery` | `(run_id,delivery_key) PK`, message/target, state, delivered/prompt_consumed/ack timestamps, version |
| `message_receipt` | receipt ID，delivery FK，status，semantic_hash，payload；每 delivery 当前有效 receipt 唯一 |
| `message_utility` | delivery/step/obligation refs，claimed vs verified，verified_by，状态 |
| `memory_item` | `(run_id,memory_id) PK`, claim/message ref, tier, state, content_hash, payload, version |
| `memory_dependency` | typed dependency edge，唯一，防自环 |
| `memory_provenance` | memory/source artifact/agent/route/verification refs |
| `memory_invalidation` | invalidated item, counterexample, reason, propagation batch, timestamp |
| `proof_obligation` | `(run_id,obligation_id) PK`, type/domain/status/priority/debt, owner route, payload, version |
| `proof_graph_edge` | `(run_id,edge_id) PK`, source/target typed refs, relation, status, provenance |
| `proof_checkpoint` | `(run_id,checkpoint_id) PK`, path/strategy/parent/segment, content_hash, status, payload；path+segment 唯一 |
| `working_checkpoint` | candidate delta，parent committed checkpoint，status，payload；不可被 Fact/综合查询读取 |
| `verification_report` | report/stage/verdict/author/reviewer/target, confidence, payload, artifact refs |
| `referee_claim_ledger` | claim/reviewer/verdict/version，唯一审核记录 |
| `meta_review` | run/round/action/failure level，payload |
| `proof_control_state` | run/route/semantic sidecar/control state/version |
| `control_action` | `(run_id,action_key) UNIQUE`, kind/status/target/payload/applied_at |
| `inspiration_proposal` | proposal/mechanism/author/trigger/novelty/review/status/payload |
| `inspiration_outcome` | proposal/route/fact/citation credit，outcome，reward，payload |
| `experiment_spec` | experiment/method/purpose/limits/status/payload |
| `experiment_result` | spec/ref/certificate/evidence/decision/artifact refs |
| `computation_cache` | `(method,canonical_identity_hash,tool_version)` 唯一，结果 artifact，evidence，不跨 tenant 泄露 |
| `artifact` | `content_hash PK`, size, media_type, storage_path, encryption/retention metadata |
| `run_artifact` | `(run_id,content_hash,purpose) PK` |
| `provider_call` | call/idempotency key, agent/provider/request hash, state, usage, cost, request/response artifacts, ambiguity |
| `usage_ledger` | run/agent/stage aggregate and reconciled counters，唯一版本 |
| `event_log` | `(run_id,sequence) PK`, event_id UNIQUE, aggregate/type/payload/hash, append-only |
| `outbox_event` | event_id PK, aggregate/version, payload, available/claimed/published/attempts |
| `inbox_event` | `(consumer_name,event_id) PK`, received/processed/result |
| `run_lease` | `run_id PK`, owner_id, fencing_token, expires_at, heartbeat_at |
| `legacy_import` | source manifest hash、legacy version、target run、status、report artifact，唯一源 hash |

## 9.3 不可变数据库规则

- 已 committed checkpoint 不更新数学 payload；修复建立新节点或分支。
- event_log、provider_call request artifact、verification report 和 invalidation 记录不可覆盖。
- Fact 失效不是删除；保留历史状态和原因。
- raw Prompt/response 默认短期 Artifact，保留策略可配置；秘密和私有 chain-of-thought 不写入。

---

# 10. 必须实现的状态机

## 10.1 Run

```text
created → preflighted → triaged → strategies_ready → exploring
exploring ↔ verifying ↔ meta_review
meta_review → synthesizing → final_review
final_review → completed | partial | failed
任意活动状态 → paused | budget_exhausted | cancelled
paused/budget_exhausted → resume 到最近持久阶段
terminal 状态 resume → 返回原结果，不调用 Provider
```

非法跨级更新失败；所有转换有 event 和 version CAS。

## 10.2 Route

保持 Python `RouteStatus` 全部值：`active`、`waiting`、`repair_once`、`frozen`、`terminal`、`frozen_stalled`、`refuted`、`cooling`、`merged`、`abandoned`、`completed`。转换由显式表控制，不能自由写字符串。

## 10.3 Delivery/Receipt

```text
queued → delivered → prompt_consumed → acknowledged
queued/delivered → expired | rejected | deferred
acknowledged + checkpoint evidence → actually_used=true/false
```

`prompt_consumed` 不得回退；Receipt status 与 Delivery state 分离。

## 10.4 Memory

```text
proposed/insight → under_review → fact
fact → needs_reverify → fact | invalidated
counterexample_candidate → replayed_negative | rejected
```

低权威事件不得提升；反例可导致降级但不删除。

## 10.5 Proof Obligation

```text
open → in_progress → closed
open/in_progress → blocked | deferred | refuted
closed → reopened（仅新反例/依赖失效/审计动作）
```

只有 ProofGraph authority 可关闭/重开。

## 10.6 Checkpoint

```text
working → tentative → verified → committed
working/tentative/verified → rejected
```

只有 committed 可成为 latest 和恢复点；Working 不可用于 Fact/综合。

## 10.7 Provider Call

```text
planned → dispatched → streaming → succeeded
                         ↘ failed | ambiguous | cancelled
succeeded → applied（幂等）
```

ambiguous 不得伪装 failed 或 succeeded；成本风险单独记录。

## 10.8 Control Action

```text
proposed → admitted → executing → applied
proposed/admitted → rejected | deferred
executing → failed_retryable | failed_terminal
```

`action_key` 唯一；恢复重复调度返回已有结果。

---

# 11. Temporal 映射规范

| Python/Java 阶段 | Temporal 形式 | 说明 |
|---|---|---|
| Freeze/Preflight | Activity | 读配置、写 ProblemContract |
| Triage/Strategy | Activities | Provider I/O 在 Activity |
| 每路线探索 | Child Workflow | 路线隔离、独立生命周期 |
| Agent call | Activity | heartbeat、重试和 provider_call ledger |
| Computation | Activity | Java handler/sidecar |
| Broker/Memory/Graph | Activities | 数据库事务与幂等 action key |
| Verification | Activity/Child Workflow | 可并行 Reviewer |
| Scheduler/阶段选择 | Workflow 纯决策 | 输入只来自 Activity 结果 ID/摘要 |
| Synthesis/Final | Activities | 盲包构建与模型调用 |
| Pause/Resume/Budget | Signal/Update | 验证后改变 Workflow 状态 |
| Status | Query | 不产生副作用 |

Workflow ID 固定为 `mpm-run/<run_id>`；路线 Child ID 固定为 `mpm-route/<run_id>/<route_id>`。Task Queue 必须由配置白名单生成，不接受用户任意输入。

---

# 12. API 与配置兼容规范

## 12.1 Solve 请求

只允许：problem、可选稳定 run_id、已存在 profile 名、少量白名单预算覆盖。禁止通过请求传 API key、base_url、文件系统根、sandbox image、SQL/Temporal 地址。

## 12.2 Resume 请求

只允许 run_id 和可选增加预算/已审计 directive。恢复必须先获取 run lease；terminal run 直接返回。

## 12.3 SSE 事件

固定类型：`run_started`、`stage_changed`、`agent_started`、`agent_completed`、`route_updated`、`message`、`checkpoint`、`verification`、`budget`、`warning`、`result`、`error`、`heartbeat`。每个事件带单调 `event_id`，支持 Last-Event-ID。

## 12.4 配置优先级

```text
编译默认值
< application.yaml
< profile YAML
< 管理员环境变量
< CLI 白名单覆盖
```

HTTP 用户参数不覆盖安全基础设施配置。

---

# 13. 安全威胁与控制矩阵

| 威胁 | 必须控制 |
|---|---|
| API key 泄漏 | 环境变量/DPAPI；redacted toString；日志/异常/Artifact 扫描 |
| SSRF | Provider allowlist、HTTPS、DNS/IP 检查、禁跨主机重定向、用户不能给 URL |
| SQL 注入 | JdbcClient 参数化；禁拼接；最小 DB 用户权限 |
| 路径穿越/Symlink | normalize + root containment + no symlink/reparse + content hash |
| 反序列化 | 未知字段失败；无 default typing；多态 allowlist；大小/深度限制 |
| Prompt/tool injection | 模型输出不可信；严格 Schema；server-owned tool registry；无任意工具名 |
| 任意代码 | sandboxed Python 默认关闭；AST 白名单；容器隔离 |
| 资源耗尽 | body/response/queue/cache/graph/compute/token/并发/时间限制 |
| 重放/重复消息 | delivery key、Inbox/Outbox、action key、checkpoint CAS、fencing token |
| 跨 run 数据泄漏 | 所有查询显式 run_id；Repository/ArchUnit/集成测试 |
| 盲审污染 | 匿名 packet builder、字段 denylist、静态/运行时泄漏测试 |
| 恶意 Artifact | MIME/大小/hash，下载 Content-Disposition，禁止 HTML inline 默认 |
| Temporal 敏感历史 | history 仅 ID/摘要；payload codec/加密作为生产扩展；服务不公开 |
| 供应链 | 固定版本、checksum/digest、SBOM、OWASP、许可证和无 Snapshot |
| 日志注入 | 结构化日志、控制字符清理、长度限制、无原始 secret/reasoning |

---

# 14. 功能完整性与差分验收矩阵

| 功能域 | Java 必须达到 | 对照证据 |
|---|---|---|
| Contract/Hash | 结构、validator、JSON/hash 一致 | Schema + golden vectors |
| Config | 默认值、范围、交叉约束、profile 一致 | 原 YAML/测试 |
| Agent/Provider | 协议、SSE、usage、重试、failover | Mock HTTP fixtures |
| Message | admission、TTL、dedup、receipt、utility、resume | Broker 测试 |
| Memory | 三级记忆、晋升、依赖、反例降级 | Memory 测试 |
| Proof Graph | 义务、边、闭包、冲突、debt、freeze | Graph 测试 |
| Computation | handler、证书、evidence gate、安全 | 差分/基准 |
| Verification | structural-first、blind、escalation、formal | Verification 测试 |
| Proof Control | 10A–10G 全部机制、权限、exactly once | 46 个测试文件 |
| Inspiration | 机制、Referee、UCB、Outcome、cross-run | 23 个测试文件 |
| Route/Scheduler | 隔离、team、continuation、deep/stall | 路线/调度测试 |
| Resume | stage/checkpoint/message/action/provider 语义 | v0.x/exactly-once 测试 |
| API/CLI/Desktop | 端点、SSE、命令、UI、secret | 对应测试 |
| End-to-end | Mock 同题状态和关键 Artifact 等价 | Shadow comparator |

---

# 15. Codex 阶段报告模板

每个 `phase-XX.md` 使用以下固定结构：

```markdown
# Phase XX Report

## Status
PASS / BLOCKED

## Source immutability
- before manifest:
- after manifest:
- result:

## Scope completed
- Python source rows migrated:
- Python tests mapped:
- Java files:

## Architecture and security decisions
...

## Commands and results
```text
exact command
exact result
```

## Tests
- unit:
- integration:
- differential:
- security:
- coverage:

## Failures encountered and fixes
...

## Gate checklist
- [x] ...

## Residual issues
Only non-blocking items with owner and target phase.
```

报告不得写“基本通过”“大致完成”等含糊结论。每项只能 PASS、FAIL、BLOCKED 或明确 N/A（含理由）。

---

# 16. 最终交付清单

最终目录至少应包含：

- 完整 Java 源、测试和 Maven Wrapper；
- 受限 Python 计算侧车及锁定依赖；
- PostgreSQL Flyway migrations；
- Docker Compose；
- Temporal 配置/脚本；
- REST/SSE、CLI、JavaFX Desktop；
- 安全、部署、备份、恢复、升级和兼容文档；
- 所有 ADR；
- 0–17 阶段报告；
- source/test/auxiliary 三类迁移状态清单，合计覆盖 401 个非 Git 源文件；
- 依赖树、SBOM、许可证和安全报告；
- 差分验证和性能报告；
- `MIGRATION_COMPLETION_REPORT.md`；
- Server/CLI/Desktop 发布包及 SHA-256。

Codex 的最终完成声明必须逐条引用上述文件和测试证据，不能仅凭“项目可编译”宣布迁移完成。
