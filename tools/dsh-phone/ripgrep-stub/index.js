// 顶掉真的 `@vscode/ripgrep`。
//
// **为什么需要这个桩**：`@deepseek-ai/dsh-tool-fs-search`（模型的 `glob` / `grep`）这样拿二进制：
//
//     const dependency = (await import("@vscode/ripgrep")).rgPath
//
// 而 `@vscode/ripgrep` **没有 android 平台产物**：它的 12 个 optionalDependencies 只有
// darwin / win32 / linux，而 `bundle-dsh.ps1` 用 `--os=android --cpu=arm64` 装 ——
// 装出来的包里连 `bin/` 目录都不存在，`rgPath` 解析必然失败。与当初 sharp / attachment-local
// 是同一类问题，解法也同一套：**换一个能在 Android 上跑的等价物**，只不过这次不是重写，
// 而是把「二进制在哪」这一个事实换成我们自己的。
//
// 二进制由 `.github/workflows/rg.yml` 交叉编译（默认特性、无 pcre2、自包含），
// 由 `tools/fetch-rg.ps1` 按 pinned 版本拉进 `jniLibs/<abi>/librg.so`，
// 由 `NodeRuntime` 注入 `FACTOTUM_RG_PATH` 指向 nativeLibraryDir 下的它。
//
// 路径走 env 而不是写死：nativeLibraryDir 的绝对路径在安装时才确定，构建期算不出来。
const rgPath = process.env.FACTOTUM_RG_PATH

if (typeof rgPath !== 'string' || rgPath === '') {
  // 故意**在 import 时就炸**而不是等调用：`rgPath` 是模块的导出值，不是函数，
  // 拿不到就说明这个运行时没有配好 —— 早点报错，别让模型收到一个「glob 找不到文件」的
  // 假答案（那看起来像工作区是空的）。
  throw new Error(
    'FACTOTUM_RG_PATH 未设置：这个运行时没有带 ripgrep。' +
      '装配时先跑 tools/fetch-rg.ps1（它会从 rg-<version> release 拉产物），' +
      '并确认 NodeRuntime 注入了这个变量。',
  )
}

export { rgPath }
export default { rgPath }
