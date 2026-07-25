# MathProofMesh 0.8.0 Windows 桌面版

桌面版使用 PyInstaller 打包 Python 运行时和 MathProofMesh 引擎，并通过
pywebview + WebView2 提供本地窗口。应用只监听随机的 `127.0.0.1` 端口，
不会对局域网开放服务。

## 构建

在项目根目录运行：

```powershell
.\packaging\windows\build.ps1 -Clean
```

构建脚本会运行桌面回归测试、生成图标、构建便携目录、执行资源健康检查和
隐藏窗口冒烟测试。如果已安装 Inno Setup 6，或
`build\tools\InnoSetup\ISCC.exe` 存在，还会生成中文/英文安装向导。

主要输出：

- `dist\MathProofMesh\MathProofMesh.exe`：便携目录入口，需与同目录
  `_internal` 一起分发。
- `packaging\windows\output\MathProofMesh-0.8.0-Setup-x64.exe`：推荐分发的
  安装程序。

## 用户数据

默认数据目录为 `%LOCALAPPDATA%\MathProofMesh`。运行记录、设置和日志不会
写入安装目录。选择“记住密钥”时，API Key 使用当前 Windows 用户的 DPAPI
加密后保存；不选择时仅保留到本次应用退出。Docker 只在启用相应求解功能时
需要，普通冒烟验证档位不强制依赖 Docker。

Windows 10/11 需要 Microsoft Edge WebView2 Runtime。Windows 11 通常已内置；
缺失时应用会显示启动错误，并在用户数据目录的 `logs` 下保留日志。
