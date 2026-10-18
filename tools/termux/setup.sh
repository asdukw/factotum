#!/data/data/com.termux/files/usr/bin/bash
# Tappet ⇄ Termux 桥：Termux 侧一键装配。
#
# 在 Termux 里执行。文件由 PC 侧 `adb reverse tcp:8000 tcp:8000` + 一个静态服务器送进来，
# 所以手机上不需要开任何入站端口、也不依赖局域网可达。
#
# 幂等：重复跑只会跳过已完成的步骤。
set -euo pipefail

SRC="${TAPPET_BRIDGE_SRC:-http://127.0.0.1:8000}"
DISTRO="${TAPPET_BRIDGE_DISTRO:-debian}"
PORT="${TAPPET_BRIDGE_PORT:-8899}"
BRIDGE="$HOME/bridge.py"
TOKEN_FILE="$HOME/.tappet-bridge-token"

step() { printf '\n=== %s ===\n' "$*"; }

# 唤醒锁必须在**任何长步骤之前**拿：ColorOS 会在 Termux 切到后台后冻结它，
# 进程一死整条装配就断在半路（实测：切到 QQ 后 apt 卡在 libllvm 下载，日志停在 15:42）。
termux-wake-lock 2>/dev/null || echo "（termux-wake-lock 不可用，切后台可能会被冻结）"

step "1/4 Termux 侧依赖：python / proot-distro"
# 上一次被打断时 dpkg 可能停在半配置状态，先收尾（幂等，没坏就是空操作）。
dpkg --configure -a >/dev/null 2>&1 || true
pkg update -y
pkg install -y python proot-distro

step "2/4 安装 $DISTRO rootfs（首次约 300MB，慢）"
if [ -d "$PREFIX/var/lib/proot-distro/installed-rootfs/$DISTRO" ]; then
  echo "已装过，跳过"
else
  proot-distro install "$DISTRO"
fi

step "3/4 $DISTRO 侧基础包"
proot-distro login "$DISTRO" -- bash -lc \
  'apt-get update && apt-get install -y --no-install-recommends git curl ca-certificates python3'

step "4/4 取 bridge + token + 起服务"
# 两条投放路径：debuggable 的 Termux（GitHub 版）可以直接把 bridge.py 推到位，此时无需下载；
# 非 debug 构建（Play 版）只能靠 adb reverse + 本地静态服务器把它 curl 进来。
if [ ! -f "$BRIDGE" ]; then
  curl -fsSL "$SRC/bridge.py" -o "$BRIDGE"
fi
python -c "import py_compile; py_compile.compile('$BRIDGE', doraise=True)" && echo "bridge.py 语法 OK"
if [ ! -s "$TOKEN_FILE" ]; then
  python -c "import secrets; print(secrets.token_hex(24))" > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE"
fi

# 桥要活过 Termux 切后台：唤醒锁已在开头拿过。
pkill -f "python $BRIDGE" 2>/dev/null || true
nohup python "$BRIDGE" > "$HOME/bridge.log" 2>&1 &
sleep 2

echo "--- 自检 /health ---"
curl -fsS "http://127.0.0.1:$PORT/health" && echo
echo
echo "token: $(cat "$TOKEN_FILE")"
echo "日志:   $HOME/bridge.log"
echo "重启:   pkill -f bridge.py; nohup python $BRIDGE > $HOME/bridge.log 2>&1 &"
