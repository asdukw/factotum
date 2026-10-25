#!/usr/bin/env bash
# 统一校验 APK：签名、非空 native / proot 载荷、完整 dsh ZIP。release 额外扫 dex 密钥形状。
# 默认要求 proot；仅未声明该能力的历史 tag 可显式 --proot=absent（并验证没有注入载荷）。
# 可从可信 checkout 调用本脚本；APK 路径和诊断用 jniLibs 路径均相对调用方 cwd。
set -euo pipefail

APK="${1:?usage: verify-apk.sh <apk> [--allow-unsigned] [--scan-secrets] [--proot=required|absent]}"
shift
ALLOW_UNSIGNED=false
SCAN_SECRETS=false
PROOT=required
for arg in "$@"; do
  case "$arg" in
    --allow-unsigned) ALLOW_UNSIGNED=true ;;
    --scan-secrets) SCAN_SECRETS=true ;;
    --proot=required) PROOT=required ;;
    --proot=absent) PROOT=absent ;;
    *) echo "::error::未知参数：$arg"; exit 2 ;;
  esac
done

[ -f "$APK" ] || { echo "::error::APK 不存在：$APK"; exit 1; }
SIGNER="${ANDROID_HOME:?ANDROID_HOME 未设置}/build-tools/36.0.0/apksigner"
[ -x "$SIGNER" ] || { echo "::error::找不到 apksigner：$SIGNER"; exit 1; }
TMP=$(mktemp -d)
trap 'rm -rf "$TMP"' EXIT

echo "APK: $APK"
ls -l "$APK"
# unsigned 也必须是真实、未截断的 APK ZIP，不能依赖 signer 替结构验收兜底。
unzip -tq "$APK" > /dev/null || { echo "::error::APK 不是完整 ZIP"; exit 1; }
unzip -Z1 "$APK" > "$TMP/entries"
echo "--- APK 里的 lib/ ---"
grep '^lib/' "$TMP/entries" || echo "(一个都没有)"
echo "--- 构建前的 jniLibs ---"
ls -l android/app/src/main/jniLibs/*/ || echo "(jniLibs 是空的)"

if [ "$ALLOW_UNSIGNED" = false ]; then
  "$SIGNER" verify --print-certs "$APK"
  # 未签名的包装不上，而构建输出里可能仍是「成功」。
  "$SIGNER" verify "$APK" > /dev/null
else
  echo "允许未签名（assemble 的 release 变体）"
fi

require_payload() {
  local entry="$1" count size
  # unzip 的 pattern 匹配不等于精确路径；也不能把同名重复条目当成一份合法载荷。
  count=$(grep -Fxc "$entry" "$TMP/entries" || true)
  [ "$count" -eq 1 ] || { echo "::error::APK 必须恰有一份 $entry"; exit 1; }
  # 完整消费流，不能用 head 提前关管道（pipefail 会把 unzip 的 SIGPIPE 当校验失败）。
  size=$(unzip -p "$APK" "$entry" | wc -c)
  [ "$size" -gt 0 ] || { echo "::error::APK 载荷为空：$entry"; exit 1; }
}

# 缺了不会让构建失败、但装到设备上功能会坏掉的 native 载荷，逐项精确检查。
for entry in lib/arm64-v8a/libnode.so lib/arm64-v8a/libc++_shared.so lib/arm64-v8a/librg.so lib/arm64-v8a/libsherpa-onnx-jni.so; do
  require_payload "$entry"
done

for entry in lib/arm64-v8a/libproot.so lib/arm64-v8a/libprootloader.so lib/arm64-v8a/libtalloc.so lib/arm64-v8a/libandroidshmem.so assets/proot-rootfs.bin assets/proot-runtime.json; do
  if [ "$PROOT" = required ]; then
    require_payload "$entry"
  elif grep -Fx "$entry" "$TMP/entries" > /dev/null; then
    echo "::error::未声明 proot 的历史源码不应注入 $entry"
    exit 1
  fi
done

require_payload assets/dsh-runtime.zip
# 不只看 PK 开头：假 ZIP / 被截断或 CRC 损坏的内层 ZIP 都必须失败。
unzip -p "$APK" assets/dsh-runtime.zip > "$TMP/dsh-runtime.zip"
unzip -tq "$TMP/dsh-runtime.zip" > /dev/null || {
  echo "::error::assets/dsh-runtime.zip 不是完整 ZIP —— 装到设备上会解压失败"
  exit 1
}

if [ "$SCAN_SECRETS" = true ]; then
  unzip -p "$APK" 'classes*.dex' > "$TMP/dex.bin"
  # 只报「有/没有」，不把匹配到的字符串打进日志 —— 那等于把密钥写进 CI 记录。
  if grep -aoE 'oc_sk_[A-Za-z0-9_]{16,}|sk-[A-Za-z0-9]{32,}|ghp_[A-Za-z0-9]{20,}' "$TMP/dex.bin" > /dev/null; then
    echo "::error::包内出现疑似密钥形状，已中止发布"
    exit 1
  fi
fi
