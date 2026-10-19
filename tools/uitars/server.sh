#!/system/bin/sh
# 在设备上拉起 llama-server（幂等）。用法：
#   adb push tools/uitars/server.sh /data/local/tmp/uitars/
#   adb shell "sh /data/local/tmp/uitars/server.sh"
#   adb shell "MODEL=GUI-Owl-1.5-2B-Instruct.Q4_K_M.gguf MMPROJ=GUI-Owl-1.5-2B-Instruct.mmproj-Q8_0.gguf sh /data/local/tmp/uitars/server.sh"
#
# 三个硬约束（都是实测踩出来的）：
#  1. **换掉旧服务用 pidfile，不要用 `pkill -f llama-server`** —— 本脚本经 `sh <路径>` 运行，
#     脚本文本不在 argv 里，所以这里安全；但如果把同样的命令塞进 `adb shell "<文本>"`，
#     那个 shell 的命令行里就含 "llama-server"，pkill 会把它自己杀掉，后面的行一行不跑
#     （AGENTS.md 里 Termux 桥那段记过同一个坑）。
#  2. **必须设 `ADSP_LIBRARY_PATH`** —— HTP 的 skel URI 是硬编码的 file:///libggml-htp-v81.so，
#     由 DSP 侧 fastrpc 按这个变量搜索，不设就 `failed to open session ... 0x80000406`。
#  3. **`IMAGE_MAX_TOKENS` 是这条链路上最重要的旋钮** —— 视觉编码耗时随图像 token 数
#     超线性增长（见 docs/on-device-inference.md 第 6 节）。
set -e

ROOT=/data/local/tmp
BIN=$ROOT/llama-b11729
GGUF=$ROOT/gguf
PIDFILE=$ROOT/uitars-server.pid
LOG=$ROOT/uitars-server.log

MODEL_NAME=${MODEL:-UI-TARS-2B-SFT-Q4_K_M.gguf}
MMPROJ_NAME=${MMPROJ:-mmproj-model-q8_0.gguf}

if [ -f "$PIDFILE" ]; then
  OLD=$(cat "$PIDFILE")
  kill "$OLD" 2>/dev/null || true
  sleep 2
  rm -f "$PIDFILE"
fi

export LD_LIBRARY_PATH=$BIN/lib
export ADSP_LIBRARY_PATH=$BIN/lib
cd "$BIN"

# EXTRA_ARGS 用于临时加参数（例如 --jinja 试 tool-call 解析），不要写死。
# shellcheck disable=SC2086
nohup ./bin/llama-server \
  -m "$GGUF/$MODEL_NAME" \
  --mmproj "$GGUF/$MMPROJ_NAME" \
  -c 8192 -dev HTP0 -ngl 99 \
  --image-max-tokens "${IMAGE_MAX_TOKENS:-1024}" \
  --host 127.0.0.1 --port 8080 \
  ${EXTRA_ARGS:-} \
  > "$LOG" 2>&1 &

echo $! > "$PIDFILE"
echo "started pid=$(cat $PIDFILE) model=$MODEL_NAME image_max_tokens=${IMAGE_MAX_TOKENS:-1024} log=$LOG"
