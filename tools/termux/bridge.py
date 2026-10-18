#!/data/data/com.termux/files/usr/bin/python3
"""Tappet ⇄ Termux shell 桥（v0，无状态）。

跑在 Termux 侧，把「执行一条命令并拿回 stdout / stderr / 退出码」暴露成 127.0.0.1 上的 HTTP。
Tappet 的 dsh 插件直接 fetch 它 —— 与 Kotlin 侧的 DeviceBridgeServer 是同一个模式
（只监听 127.0.0.1 + token），只是这条通向的是 Linux 用户态而不是无障碍执行层。

接口：
    GET  /health                    → {"ok": true, "distro": "debian", "version": 1}（**不鉴权**）
    GET  /ping                      → {"ok": true}（鉴权；用来区分「桥在跑」与「桥在跑但不是我这把 token」）
    POST /run  {"cmd": "...", "timeout_ms": 60000}
         → {"exit": 0, "stdout": "...", "stderr": "...", "timeout": false, "duration_ms": 123}

v0 的已知边界（先证明链路，再解决这些）：
  * **无状态**：每条命令都是一次独立的 `proot-distro login`，cwd / 环境变量 / 后台进程都不保留。
    跑 dev server 这类常驻进程需要 v1 的长驻会话（FIFO 或 tmux 通道）。
  * 输出上限 64KiB，超出时保留头尾各 32KiB 并在中间插标记（构建日志的错在尾部，列表的头在头部）。
  * 没有并发保护：同时来两条命令就是两个 proot，互不干扰但也互不可见。
"""

from __future__ import annotations

import json
import os
import signal
import subprocess
import sys
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HOST = "127.0.0.1"
PORT = int(os.environ.get("TAPPET_BRIDGE_PORT", "8899"))
DISTRO = os.environ.get("TAPPET_BRIDGE_DISTRO", "debian")
TOKEN_FILE = os.path.expanduser(os.environ.get("TAPPET_BRIDGE_TOKEN_FILE", "~/.tappet-bridge-token"))
# Tappet 侧拉起桥时用 pidfile 换掉旧进程：按 pid 杀，而不是 pkill -f —— 后者会匹配到
# 「启动脚本自己的命令行」（脚本正文里就写着 bridge.py），把自己也杀掉。
PID_FILE = os.path.expanduser(os.environ.get("TAPPET_BRIDGE_PID_FILE", "~/.tappet-bridge.pid"))
MAX_OUT = 64 * 1024
DEFAULT_TIMEOUT_MS = 60_000
MAX_TIMEOUT_MS = 600_000


def load_token() -> str:
    # 环境变量优先：Tappet 经 RUN_COMMAND 拉起时把 token 直接放在 env 里，不必先落盘。
    from_env = os.environ.get("TAPPET_BRIDGE_TOKEN", "").strip()
    if from_env:
        return from_env
    try:
        with open(TOKEN_FILE, encoding="utf-8") as f:
            token = f.read().strip()
    except FileNotFoundError:
        sys.exit(f"缺少 token：既没有 TAPPET_BRIDGE_TOKEN，也没有 {TOKEN_FILE}；先跑 tools/termux/setup.sh")
    # 空 token 必须硬停：它会让鉴权退化成「谁都能连」，而现象是「桥在跑但没人拦」——
    # 正是这个闸门存在的理由，宁可起不来。
    if not token:
        sys.exit(f"token 文件是空的：{TOKEN_FILE}；重跑 tools/termux/setup.sh 或让 Tappet 重新拉起桥")
    return token


def clip(raw: bytes) -> str:
    """头尾各留一半 —— 构建日志的错在尾部，目录列表的头在头部。"""
    if len(raw) <= MAX_OUT:
        return raw.decode("utf-8", "replace")
    half = MAX_OUT // 2
    head = raw[:half].decode("utf-8", "replace")
    tail = raw[-half:].decode("utf-8", "replace")
    dropped = len(raw) - MAX_OUT
    return f"{head}\n\n…[已截断 {dropped} 字节]…\n\n{tail}"


def run_command(cmd: str, timeout_ms: int) -> dict:
    argv = ["proot-distro", "login", DISTRO, "--", "bash", "-lc", cmd]
    started = time.monotonic()
    # start_new_session：超时能整组杀掉。proot 会 ptrace 它的子进程，只杀直接子进程会留下 tracee。
    proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.PIPE, start_new_session=True)
    timed_out = False
    try:
        out, err = proc.communicate(timeout=timeout_ms / 1000)
    except subprocess.TimeoutExpired:
        timed_out = True
        try:
            os.killpg(os.getpgid(proc.pid), signal.SIGKILL)
        except ProcessLookupError:
            pass
        out, err = proc.communicate()
    return {
        "exit": proc.returncode,
        "stdout": clip(out or b""),
        "stderr": clip(err or b""),
        "timeout": timed_out,
        "duration_ms": int((time.monotonic() - started) * 1000),
    }


class Handler(BaseHTTPRequestHandler):
    server_version = "tappet-termux-bridge/0.1"

    def _send(self, code: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _authorized(self) -> bool:
        return self.headers.get("X-Tappet-Token") == self.server.token  # type: ignore[attr-defined]

    def do_GET(self) -> None:  # noqa: N802 —— BaseHTTPRequestHandler 的约定命名
        if self.path == "/health":
            # 不鉴权：这是「端口上有没有人」的探测，拉起方在拿到 token 之前就要问它。
            self._send(200, {"ok": True, "distro": DISTRO, "version": 1})
        elif self.path == "/ping":
            # 鉴权：用来区分「桥在跑」与「桥在跑，但用的是别人的 token」—— 后者要重启桥，
            # 否则 Tappet 的每条命令都会 401，而现象看起来像「桥坏了」。
            if not self._authorized():
                self._send(401, {"error": "bad token"})
                return
            self._send(200, {"ok": True})
        else:
            self._send(404, {"error": "not found"})

    def do_POST(self) -> None:  # noqa: N802
        if self.path != "/run":
            self._send(404, {"error": "not found"})
            return
        if not self._authorized():
            self._send(401, {"error": "bad token"})
            return
        try:
            length = int(self.headers.get("Content-Length") or 0)
            req = json.loads(self.rfile.read(length) or b"{}")
        except (ValueError, json.JSONDecodeError) as exc:
            self._send(400, {"error": f"bad request: {exc}"})
            return

        cmd = req.get("cmd")
        if not isinstance(cmd, str) or not cmd.strip():
            self._send(400, {"error": "cmd 必填"})
            return
        timeout_ms = min(int(req.get("timeout_ms") or DEFAULT_TIMEOUT_MS), MAX_TIMEOUT_MS)
        self._send(200, run_command(cmd, timeout_ms))

    def log_message(self, format: str, *args) -> None:  # noqa: A002 —— 与基类签名一致
        # 只记方法与路径，不记命令正文（与仓库的「屏幕/正文不落日志」同一条规矩）。
        sys.stderr.write("%s %s\n" % (self.address_string(), format % args))


def main() -> None:
    token = load_token()
    server = ThreadingHTTPServer((HOST, PORT), Handler)
    server.token = token  # type: ignore[attr-defined]
    with open(PID_FILE, "w", encoding="utf-8") as f:
        f.write(str(os.getpid()))
    sys.stderr.write(f"tappet-termux-bridge 监听 {HOST}:{PORT}，distro={DISTRO}，pid={os.getpid()}\n")
    try:
        server.serve_forever()
    finally:
        # 正常退出就把 pidfile 收掉；被 SIGKILL 时留着也没关系 —— 拉起方 kill 一个不存在的
        # pid 只会失败一次，随后照常启动新桥。
        try:
            os.unlink(PID_FILE)
        except FileNotFoundError:
            pass


if __name__ == "__main__":
    main()
