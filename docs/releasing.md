# CI 与发版

**什么时候读**：动 `.github/workflows/`、发 `v*` tag、改签名材料之前。

## 1. 四个 workflow

| workflow | 触发 | 干什么 |
|---|---|---|
| `ci.yml` | push master / PR | 先判变更范围；代码改动跑静态检查、单测 + androidTest 编译、离线自检，纯文档改动跳过重活 |
| `assemble.yml` | 装配相关路径变动时 | 拉 libnode → 装配 dsh → NDK 出 debug 包 → **验产物结构**（native 库在不在、运行时资产是不是真 zip） |
| `codeql.yml` | `android/**` 或本文件变动 / 每周 | 语义级安全扫描 |
| `release.yml` | 推 `v*` tag / 手动补发 | **先过门禁** → 装配 → 签名出包 → 验签 + 扫包内密钥；构建与发布拆成两个 job，构建在 `release` environment 里等人工批准 |

- **CI 绿 ≠ 能用**：无障碍执行层、内嵌 Node 启动、真实任务只能在真机上验证。
  日常 CI 不装 NDK（那 ~1.5GB 只有 `assemble.yml` 与 `release.yml` 需要，按版本缓存）。
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

### 分支保护与 push protection

- **`master` 的 Ruleset 管理员确实能直推**（bypass actor 是 `RepositoryRole 5` = Maintain，
  admin 覆盖它，`bypass_mode: always`）。实测输出是 `Bypassed rule violations ...`；
  直推的是**合并后的结果**，不是开发中的半成品。规则名称是 `master protection`
  （`gh api repos/asdukw/tappet/rulesets` 可见，**不是** classic branch protection），
  要求 PR、禁止 force push/删除，required checks 是 `ci.yml` 的四个 job（`changes` + 三个重活 job）。
- 被拒时先看清是哪一道：**PR 规则**还是 **push protection** —— 两者都报
  “repository rule violations”，很容易看错。后者是 GitHub 认为提交里有密钥，
  处置见 `docs/email-connector.md` §4.4 与下面的 `GH013` 一行。

## 2. 发版顺序（不能反）

`release.yml` 在 tag 上一次性把包与说明发出去：

1. 改 `android/app/build.gradle.kts` 的 `versionCode` / `versionName` —— **包内版本号来自这里，
   不是来自 tag**，漏改会打出「tag 叫 `v0.6.0`、装到设备上却报 `0.5.0`」的包；
2. 写 `docs/releases/<tag>.md` 并更新 `CHANGELOG.md`，**先提交再打 tag** —— `release.yml`
   只读仓库里已有的那份说明文件，没有就退回 `--generate-notes`；
3. 打 `v*` tag → 出签名 APK，附 `tappet-<tag>-arm64-v8a.apk` 与 `.sha256`。
   build job 会停在 `release` environment 等人工批准；批准后才读签名密钥、出包。

## 3. 签名材料

| 签名材料 | 位置 |
|---|---|
| release keystore | `C:\Users\asdukw\keys\tappet-release.jks`（**仓库外**，RSA 4096 / 10000 天） |
| 本机配置 | `android/local.properties`（不被跟踪）的 `tappet.release.storeFile/storePassword/keyAlias/keyPassword` |
| CI | **environment secret**（环境 `release`）：`TAPPET_KEYSTORE_BASE64` / `TAPPET_KEYSTORE_PASSWORD` / `TAPPET_KEY_ALIAS` / `TAPPET_KEY_PASSWORD`；Gmail 的两个 OAuth 凭据也在同一环境 |

- **keystore 丢了就再也发不出能覆盖安装的更新**（换签名 = 另一个 App，设备上旧包的库读不到）。
  与 SQLCipher 口令同性质：**先备份再折腾**，别只留一份在开发机上。
- 本机也能出签名包（读取顺序：环境变量 > local.properties）。
- **environment secret 的边界**：仓库级 secret 对同仓库任何 workflow 可见；`release` environment
  只允许 `v*` tag（以及手动补发用的 `master`）部署，并要求人工批准。把四个签名 secret 与两个
  Gmail secret 从仓库级迁到环境级，再删掉仓库级同名项：

  ```bash
  gh secret set --env release TAPPET_KEYSTORE_BASE64
  # ...其余五个同理
  gh secret delete TAPPET_KEYSTORE_BASE64
  # ...其余五个同理
  ```

  迁移完成前仓库级 secret 仍然可用（GitHub 会同时提供给带 environment 的 job），所以不会把
  发版打断；但只有删掉仓库级那一份，PR workflow 才真正读不到它们。

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
| 发布出的包连不上 Gmail | `release` environment 里的 `TAPPET_GMAIL_CLIENT_ID/SECRET` 没配（或还留在仓库级）→ 构建期注入是空的，包里就没有预置值 |
| release 包里 `assets/dsh-runtime.zip` 其实是 tar | 打包工具写不了 zip（GNU tar 不支持）→ 用 .NET `ZipFile`；打完必须验头四字节是 `PK` |
| 想确认发布物里没有密钥 | `tools/check-sensitive.ps1 -ApkPath <包>`（或 `strings` 扫 `classes*.dex`，grep 到空） |
