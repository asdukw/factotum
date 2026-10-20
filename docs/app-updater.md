# 应用内更新：查版本 / 断点续传 / 交安装器

**什么时候读**：改 `update/`、更新弹窗、设置页「版本更新」、发布资产命名之前。

## 1. 三个 object，各管一件事

| 位置 | 管什么 |
|---|---|
| `update/AppUpdater.kt` | 查 GitHub Releases、挑出 apk + `.sha256` 配对的资产、交系统安装器 |
| `update/UpdateDownloader.kt` | 取字节：断点续传、取消、SHA-256 与**签名证书**比对 |
| `update/UpdateManager.kt` | 唯一状态源（`StateFlow`），提醒弹窗与设置页共用 |
| `ui/components/UpdateProgress.kt` | 进度条与文案，两处共用 |

合在一个里 detekt 的「一个 object 最多 20 个函数」会先炸，而那条规则在这里是对的。

## 2. 硬规则

- **状态是单例的**：提醒弹窗与 设置 → 版本更新 以前各写一份状态机，于是同一件事两套行为
  （弹窗看不到进度、设置里不能取消）。现在关掉弹窗**不会中断下载**，「后台继续」就是关掉它。
- **断点续传靠 cacheDir 里的 `.part` + `.etag` 边车**，两者都跨进程存活（实测重启模拟器后
  仍在，接着下）。三条不能改的细节：
  1. 用 **`If-Range` 而不是裸 `Range`** —— 同名文件在服务器上换了内容时，裸 Range 会把两份
     不同的字节拼在一起（哈希最终会拦下，但已经白下了几十 MB）；
  2. **206 的 `Content-Length` 是剩余长度**，总长要加回已落盘的 offset，否则进度条从中间起跳
     然后卡在 100% 之前；
  3. **416 不能直接失败** —— 本地那份比服务器上的还长时（换过 release / 上次写坏了），
     丢掉本地那份、去掉 Range 重新问一次。
- **续传不放松校验**：哈希与签名仍是对**整包**算的，拼接出来的字节必须整份对得上。
- **缺「安装未知应用」是新装用户的默认状态**（`adb install` 装的也一样），不是异常：
  点「更新」应当直接把用户送到系统设置页，而不是回一句「失败」。状态里带着 release，
  授完权回来点一次就接着下，不用重查版本。
- **从 application context 起系统页必须带 `FLAG_ACTIVITY_NEW_TASK`**：`UpdateManager` 拿的是
  application context，`unknownSourcesIntent` 少这个 flag 会 `ActivityNotFoundException`，
  现象是「更新没完成：NO_SETTINGS」（模拟器实测踩过一次）。
- **不静默安装、不后台轮询**（与连接器同一条原则）：只在用户点的时候联网，确认框留给系统安装器。

## 3. 验证

续传的分支逻辑在 `UpdateDownloaderResumeTest` 里**用本地 HTTP 服务器真跑一遍**
（200 / 206 / 416 三种回应各自的处置、取消后半截文件还在）—— 这些不需要设备，
而真跑一次 116MB 的下载既慢又不可重复。

## 4. 排障

| 现象 | 原因 / 处理 |
|---|---|
| 更新时下载失败报 `failed to connect to github.com` | 模拟器 `adb emu network speed` 会把 NAT 弄坏（宿主能连通、模拟器连不上同一个 IP）→ 冷启一次（`-no-snapshot-load`）。`-no-snapshot-save` 只防存，**不防读** |
| 点「更新」回一句「更新没完成：NO_SETTINGS」 | 从 application context 起系统设置页少了 `FLAG_ACTIVITY_NEW_TASK` → `AppUpdater.unknownSourcesIntent` |
| 进度条一直显示「已下载 0 B」 | 服务器没给 `Content-Length`（分块传输）→ 显示不确定进度条，不编一个假百分比。**不要**改成「卡在 0% 的确定进度条」 |
| 取消下载后进度条又从 0% 开始 | 半截文件没被复用：`update-<版本>.apk.part` 与它的 `.etag` 要一起留着；`.etag` 丢了就只能裸 `Range` |
