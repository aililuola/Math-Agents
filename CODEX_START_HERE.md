# Codex 执行入口（已包含精确 Python 源快照）

本工作区已经按阶段 00 所需布局准备完毕。不要搜索其他 Git 历史、旧分支、其他 ZIP 或其他工作树，也不要猜测源快照位置。

## 固定路径

将当前目录中同时包含以下内容的目录视为 `WORKSPACE_ROOT`：

- `pyproject.toml`
- `BUILD_INFO.json`
- `src/mathproofmesh/`
- `tests/`
- `JavaMathProofMesh-0.8.0/`

目标 Java 项目固定为：

```text
TARGET_ROOT = WORKSPACE_ROOT/JavaMathProofMesh-0.8.0
```

唯一允许写入的目录是 `TARGET_ROOT`。`WORKSPACE_ROOT` 下原有的 401 个 Python 快照文件只能读取，禁止修改。

## 唯一权威 Python 源 ZIP

阶段 00 要求的原始 Python 源 ZIP 已经包含在：

```text
JavaMathProofMesh-0.8.0/migration/input/Math-Agents-feature-mathproofmesh-v0.8.0-goal-plan-failure-utility-control.zip
```

它的固定 SHA-256 是：

```text
5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2
```

该 ZIP 必须与同目录 `PYTHON_SOURCE_ZIP.sha256` 一致。它不是 Java 迁移规范包，不能用 Java 交付 ZIP 的 `.sha256` 代替。

`SOURCE_SNAPSHOT_SHA256SUMS.txt` 是 ZIP 解压后 401 个源文件的逐文件清单，不是源代码本身。阶段 00 必须同时验证：

1. 上述 Python 源 ZIP 的 SHA-256；
2. 当前 `WORKSPACE_ROOT` 中排除 `.git/**` 和 `JavaMathProofMesh-0.8.0/**` 后的 401 个普通文件；
3. 401 个文件逐路径、逐字节与 `SOURCE_SNAPSHOT_SHA256SUMS.txt` 一致；
4. 阶段前后这些源文件完全不变。

不要再用历史提交、当前其他分支或其他目录替代这一快照；即使路径数量相同，只要文件哈希不一致也不是权威源。

## 执行要求

1. 完整阅读：
   - `CODEX_MASTER_INSTRUCTIONS.md`
   - `MIGRATION_PLAN.md`
   - `PHASE_GATES.yaml`
   - 三份 CSV 映射
   - `SOURCE_SNAPSHOT_SHA256SUMS.txt`
   - `SHA256SUMS.txt`
2. 只执行阶段 00；完成后停止，不得开始阶段 01。
3. JDK 25 必须由 Codex 自行检测和安装：
   - 优先复用合规 JDK 25；
   - Windows 可优先用 Eclipse Temurin 25 的 winget 安装；
   - 无管理员权限时，使用 Eclipse Adoptium 官方 API 下载并校验 SHA-256，安装到 `JavaMathProofMesh-0.8.0/.tools/jdk-25/`；
   - 不得要求用户手动安装 JDK。
4. Python 基线必须在 `TARGET_ROOT/.work/` 和 `TARGET_ROOT/.venv-baseline/` 中隔离执行，严格得到 `759 passed`。
5. 所有缓存、日志、虚拟环境、构建输出和下载都必须留在 `TARGET_ROOT` 内。
6. 任一门禁失败，写 `PHASE 00: BLOCKED` 并停止；不得绕过或伪造。

## 首次命令提示词

把以下内容作为本次 Codex 任务：

```text
读取 JavaMathProofMesh-0.8.0/CODEX_START_HERE.md 以及其中列出的全部迁移规范。当前 WORKSPACE_ROOT 就是同时包含 pyproject.toml、src/mathproofmesh、tests 和 JavaMathProofMesh-0.8.0 的目录。唯一权威 Python 源 ZIP 已位于 JavaMathProofMesh-0.8.0/migration/input/Math-Agents-feature-mathproofmesh-v0.8.0-goal-plan-failure-utility-control.zip，其 SHA-256 必须为 5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2。不要搜索或替代成任何历史提交、其他分支或其他 ZIP。所有写入仅允许发生在 JavaMathProofMesh-0.8.0 内。现在只执行阶段 00，自行安装和配置 JDK 25，完成源快照、759 项 Python 测试、框架和容器预检；阶段 00 完成后立即停止，不得开始阶段 01。
```
