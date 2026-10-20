#!/usr/bin/env bash
# 统一校验 APK：签名、native 库、dsh 运行时是不是真 zip。release 额外扫 dex 里的密钥形状。
#
# assemble 与 release 必须走同一份检查 —— v0.6.0 发出去的包缺 librg.so，就是因为当时
# assemble 只验 debug 包、release 那条路没人验。
set -euo pipefail

APK="${1:?usage: verify-apk.sh <apk> [--scan-secrets]}"
SCAN="${2:-}"

[ -f "$APK" ] || { echo "::error::APK 不存在：$APK"; exit 1; }
SIGNER="${ANDROID_HOME:?ANDROID_HOME 未设置}/build-tools/36.0.0/apksigner"
[ -x "$SIGNER" ] || { echo "::error::找不到 apksigner：$SIGNER"; exit 1; }

echo "APK: $APK"
ls -l "$APK"
echo "--- APK 里的 lib/ ---"
unzip -l "$APK" | awk '{print $4}' | grep '^lib/' || echo "(一个都没有)"
echo "--- 构建前的 jniLibs ---"
ls -l android/app/src/main/jniLibs/*/ || echo "(jniLibs 是空的)"

"$SIGNER" verify --print-certs "$APK"
# 未签名的包装不上，而构建输出里可能仍是「成功」。
"$SIGNER" verify "$APK" > /dev/null

# 这三个都是「缺了不会让构建失败、但装到设备上功能静默坏掉」的东西，所以逐个查。
for entry in lib/arm64-v8a/libnode.so lib/arm64-v8a/libc++_shared.so lib/arm64-v8a/librg.so lib/arm64-v8a/libsherpa-onnx-jni.so; do
  unzip -l "$APK" "$entry" > /dev/null || { echo "::error::APK 里缺 $entry"; exit 1; }
done

# 运行时资产必须是真 zip（头两个字节 PK）；v0.1.0 那次是 tar 冒充 zip，而构建是成功的。
if [ "$(unzip -p "$APK" assets/dsh-runtime.zip | head -c 2)" != "PK" ]; then
  echo "::error::assets/dsh-runtime.zip 不是 zip —— 装到设备上会解压失败"
  exit 1
fi

if [ "$SCAN" = "--scan-secrets" ]; then
  TMP=$(mktemp -d)
  trap 'rm -rf "$TMP"' EXIT
  unzip -p "$APK" 'classes*.dex' > "$TMP/dex.bin"
  # 只报「有/没有」，不把匹配到的字符串打进日志 —— 那等于把密钥写进 CI 记录。
  if grep -aoE 'oc_sk_[A-Za-z0-9_]{16,}|sk-[A-Za-z0-9]{32,}|ghp_[A-Za-z0-9]{20,}' "$TMP/dex.bin" > /dev/null; then
    echo "::error::包内出现疑似密钥形状，已中止发布"
    exit 1
  fi
fi
