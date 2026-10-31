# CI 与发版

**什么时候读**：动 `.github/workflows/`、发 `v*` tag、改签名材料之前。

## 1. 六个 workflow

| workflow | 触发 | 干什么 |
|---|---|---|
| `ci.yml` | push master / PR | 先判变更范围；代码改动跑静态检查、单测 + androidTest 编译、离线自检，纯文档改动跳过重活 |
| `assemble.yml` | 装配相关路径变动时 | 拉 libnode / rg / sherpa / proot → 装配 dsh → NDK 出 debug / release 包 → **验产物结构**（native 库在不在、运行时资产是不是真 zip） |
| `codeql.yml` | `android/**` 或本文件变动 / 每周 | 语义级安全扫描 |
| `release.yml` | 推 `v*` tag / 手动补发 | **先过门禁** → 装配 → 签名出包 → 验签 + 扫包内密钥；构建与发布拆成两个 job，构建在 `release` environment 里等人工批准 |
| `rg.yml` | 手动触发 | 交叉编译固定版本 ripgrep，校验后发布不可变辅助资产，不参与应用 Latest |
| `workflow-lint.yml` | workflow / action / CI helper 变更及手动触发 | 离线回归、actionlint + ShellCheck、zizmor |

- **CI 绿 ≠ 能用**：无障碍执行层、内嵌 Node 启动、真实任务只能在真机上验证。
  日常 CI 不装 NDK；`assemble.yml` 与 `release.yml` 按版本安装，利用 runner 已预装的同版本包，
  不向 root-owned SDK 目录恢复 NDK/CMake 缓存。
- **出包之后要验产物本身**，不能只看退出码：v0.1.0 第一次出的 APK 构建成功、装到设备上才炸
  （Linux 的 GNU tar 写不了 zip，产物是改了后缀的 tar），第二次又缺 `libc++_shared.so`。
  `assemble.yml` 就是为这两次教训加的。
- Lint / detekt 的结果以 SARIF 传到仓库 Security 标签页，不用点进 CI 日志翻。
- **required check 与文档 PR**：`master` 的 ruleset 要求 PR + `ci.yml` 的四个 job（`changes`
  与三个重活 job）。workflow 级 `paths-ignore` 会让 required check 永远「等待上报」；所以范围判断放在 `changes` job 里，
  重活 job 用 `if` 跳过 —— skipped 的 required check 算通过。改这里时别把 `paths-ignore` 加回来。
- **Actions 全部钉到 commit SHA**（保留 `# vN` 注释，Dependabot 可以继续升）；`setup-gradle`
  显式开 `validate-wrappers: true`，PR 改 `gradle-wrapper.jar` 会在 CI 红。
- **release 的写权限只在 publish job**：build 只读并产出 artifact，publish 才拿 `contents: write`；
  `rg.yml` 同样拆成 build / publish。
- **assemble 与 release 共用装配入口**：`.github/actions/setup-build` 负责 JDK / Node / Android /
  Gradle、NDK/CMake 安装、fetch 缓存、dsh 与声明的 proot 载荷装配；支持 `source-directory`
  指向目标源码。产物校验统一走可信版本的 `tools/verify-apk.sh`（release 加 `--scan-secrets`），
  要求 native 库与资产非空、APK 与内层 dsh ZIP 完整；当前源码必须带齐 proot 库、rootfs 和 marker。
  两条路都钉 `ubuntu-24.04`，`apksigner` 用显式安装的
  build-tools 36.0.0，不再用 `sort -V` 猜最新。

### 分支保护与 push protection

- **`master` 的 Ruleset 管理员确实能直推**（bypass actor 是 `RepositoryRole 5` = Maintain，
  admin 覆盖它，`bypass_mode: always`）。实测输出是 `Bypassed rule violations ...`；
  直推的是**合并后的结果**，不是开发中的半成品。规则名称是 `master protection`
  （`gh api repos/asdukw/zoneu/rulesets` 可见，**不是** classic branch protection），
  要求 PR、禁止 force push/删除，required checks 是 `ci.yml` 的四个 job（`changes` + 三个重活 job）。
- 被拒时先看清是哪一道：**PR 规则**还是 **push protection** —— 两者都报
  “repository rule violations”，很容易看错。后者是 GitHub 认为提交里有密钥，
  处置见 `docs/email-connector.md` §5 与下面的 `GH013` 一行。

## 2. 发版顺序（不能反）

`release.yml` 在 tag 上一次性把包与说明发出去：

1. 改 `android/app/build.gradle.kts` 的 `versionCode` / `versionName` —— **包内版本号来自这里，
   不是来自 tag**，漏改会打出「tag 叫 `v0.6.0`、装到设备上却报 `0.5.0`」的包；
2. 写 `docs/releases/<tag>.md` 并更新 `CHANGELOG.md`，**先提交再打 tag** —— `release.yml`
   只读仓库里已有的那份说明文件，没有就退回 `--generate-notes`；
3. 打 `v*` tag → 出签名 APK，附 `zoneu-<tag>-arm64-v8a.apk` 与 `.sha256`。
   build job 会停在 `release` environment 等人工批准；批准后才读签名密钥、出包。

- **手动补发与源码身份**：构建目标用完整 `refs/tags/<tag>` 解析为固定提交，publish 使用同一提交，
  发布前核验远端 tag 未移动。编排与校验辅助器来自触发工作流的 `github.workflow_sha`，
  目标源码和装配脚本来自所选标签，不用旧标签的 workflow / APK 验证器。
  当前构建使用 `ZONEU_*`；现有六个 `FACTOTUM_*` environment secrets 保留为兼容输入，
  同后缀的 `ZONEU_*` secret 若已配置则优先。helper 在构建边界同时导出 FACTOTUM/TAPPET
  别名，不改历史源码或应用身份。资产名由已构建 APK 的 applicationId 决定：
  `com.zoneu.app` 使用 `zoneu-*`，Factotum/Tappet 历史包保留 `factotum-*` 发布合同；
  build 输出与 sidecar 将同一身份交给 publish 核验。未声明 proot 的历史源码显式使用 `--proot=absent`；
  当前声明该能力的源码仍必须完整装配，不能借兼容模式绕过检查。
- 已有 draft release 的重试在资产上传成功后公开发布；已有公开 release 仍按应用包重发约定处理。
- **辅助 rg 资产不可变**：发布显式指定仓库并设置 `--latest=false`；同版本重跑比较远端真实资产字节，
  相同才幂等，不同则失败而不覆盖。需要变更二进制时使用新的版本标识，并同步消费者钉住的 SHA。
- 发布物附带 build provenance（`actions/attest-build-provenance`）：来源依赖明确记录实际标签提交，
  工作流 / builder 保留真实触发工作流版本，不把 master dispatch 伪装成 tag 触发。验证：
  `gh attestation verify <apk> --repo asdukw/zoneu`。建议在仓库设置里开启 **Immutable
  releases**；开启后资产不可覆盖，重发要先删 release（当前的 `--clobber` 会被拒）。
- publish job 的 summary 里会写 APK 大小与 SHA-256；`.sha256` 同时作为 release 资产发布。

## 3. 签名材料

| 签名材料 | 位置 |
|---|---|
| release keystore | `C:\Users\asdukw\keys\factotum-release.jks`（**仓库外**，RSA 4096 / 10000 天） |
| 本机配置 | `android/local.properties`（不被跟踪）的 `zoneu.release.storeFile/storePassword/keyAlias/keyPassword` |
| CI | **environment secret**（环境 `release`）：`FACTOTUM_KEYSTORE_BASE64` / `FACTOTUM_KEYSTORE_PASSWORD` / `FACTOTUM_KEY_ALIAS` / `FACTOTUM_KEY_PASSWORD`；Gmail 的两个 OAuth 凭据也在同一环境 |

- **keystore 丢了就再也发不出能覆盖安装的更新**（换签名 = 另一个 App，设备上旧包的库读不到）。
  与 SQLCipher 口令同性质：**先备份再折腾**，别只留一份在开发机上。
- 本机也能出签名包（读取顺序：环境变量 > local.properties；各自先新 `ZONEU_*` /
  `zoneu.*`，再兼容 `FACTOTUM_*` / `factotum.*`）。旧 keystore 文件名与签名 alias 不改。
- 仓库改名不自动迁移 Android 安装身份：`com.zoneu.app` 是独立 App，不能覆盖升级
  `com.factotum.app`，也不继承其数据库、凭据或授权。旧应用保持原状。
- **environment secret 的边界**：仓库级 secret 对同仓库任何 workflow 可见；`release` environment
  只允许 `v*` tag（以及手动补发用的 `master`）部署，并要求人工批准。把四个签名 secret 与两个
  Gmail secret 从仓库级迁到环境级，再删掉仓库级同名项：

  ```bash
  gh secret set --env release FACTOTUM_KEYSTORE_BASE64
  # ...其余五个同理
  gh secret delete FACTOTUM_KEYSTORE_BASE64
  # ...其余五个同理
  ```

  迁移完成前仓库级 secret 仍然可用（GitHub 会同时提供给带 environment 的 job），所以不会把
  发版打断；但只有删掉仓库级那一份，PR workflow 才真正读不到它们。

### 可选的 Gradle configuration cache

`setup-gradle` 接收可选的 `GRADLE_ENCRYPTION_KEY` secret，用于跨 runner 安全保存 / 恢复配置缓存。
需要启用时由维护者生成并配置 base64 AES key；release job 从其 `release` environment 读取可用值。
未配置或不可信 fork 无 secret 时正常退回无配置缓存，不影响检查与构建。
这不是签名所需的第七项必填 secret；不得为启用缓存扩大 fork 的 secret 访问权限。

## 4. 发之前至少跑

```powershell
.\gradlew.bat spotlessCheck detekt :app:lintDebug --console=plain
.\gradlew.bat :app:testDebugUnitTest --console=plain
.\tools\check-sensitive.ps1 -SelfTest
.\tools\check-sensitive.ps1 -ApkPath <包>
```

## 5. 排障

| 现象 | 原因 / 处理 |
|---|---|
| 推送被拒 `GH013 Repository rule violations` | 先分清是 PR 规则还是 **push protection**（两者措辞一样）→ 后者是 GitHub 认为提交里有密钥，点它给的 unblock 链接或把值改成构建期注入 |
| 发布出的包连不上 Gmail | `release` environment 里的 `FACTOTUM_GMAIL_CLIENT_ID/SECRET` 没配（或还留在仓库级）→ 构建期注入是空的，包里就没有预置值 |
| release 包里 `assets/dsh-runtime.zip` 其实是 tar | 打包工具写不了 zip（GNU tar 不支持）→ 用 .NET `ZipFile`；打完必须验头四字节是 `PK` |
| 想确认发布物里没有密钥 | `tools/check-sensitive.ps1 -ApkPath <包>`（或 `strings` 扫 `classes*.dex`，grep 到空） |
