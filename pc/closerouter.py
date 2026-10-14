# -*- coding: utf-8 -*-
"""
CloseRouter 电脑助手（closerouter.exe）
全自动：找设备 → 从手机读端口 → 建 adb forward → 测通端点 → 打印使用说明 → 守护重连。
不让用户输任何命令。纯标准库。
"""
import os
import subprocess
import sys
import tempfile
import time
import shutil
import urllib.error
import urllib.request

# Windows 中文控制台是 GBK：用原生编码输出（中文正常），无法编码的字符替换而不是崩
for stream in (sys.stdout, sys.stderr):
    try:
        stream.reconfigure(errors="replace")
    except Exception:
        pass

DEFAULT_PORT = 8788
PORT_FILE = "/sdcard/Android/data/com.closerouter.app/files/port.txt"
PKG = "com.closerouter.app"


def log(msg):
    print(msg, flush=True)


def adb_dir():
    """exe 同目录的 adb/ 优先，其次 PyInstaller 解压的临时目录"""
    here = os.path.dirname(os.path.abspath(sys.executable if getattr(sys, "frozen", False) else __file__))
    for cand in (os.path.join(here, "adb"), os.path.join(getattr(sys, "_MEIPASS", here), "adb")):
        if os.path.exists(os.path.join(cand, "adb.exe")):
            return cand
    return None


def extract_adb():
    """把内嵌 adb 解压到临时目录（避免杀软锁 exe 同目录）"""
    src = adb_dir()
    if not src:
        return None
    dst = os.path.join(tempfile.gettempdir(), "closerouter_adb")
    if not os.path.exists(os.path.join(dst, "adb.exe")):
        os.makedirs(dst, exist_ok=True)
        for f in ("adb.exe", "AdbWinApi.dll", "AdbWinUsbApi.dll"):
            s = os.path.join(src, f)
            if os.path.exists(s):
                shutil.copy2(s, os.path.join(dst, f))
    return dst


def run_adb(adb, args, timeout=15):
    return subprocess.run(
        [adb] + args,
        capture_output=True, text=True, timeout=timeout,
        creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0),
    )


def wait_device(adb):
    log("[1/5] 等待手机连接（请插线并开启 USB 调试）…")
    deadline = time.time() + 30
    while time.time() < deadline:
        try:
            r = run_adb(adb, ["devices"], timeout=8)
            lines = [l for l in r.stdout.splitlines() if l.strip() and "List of devices" not in l]
            for l in lines:
                parts = l.split()
                if len(parts) >= 2 and parts[1] == "device":
                    log(f"      发现设备: {parts[0]}")
                    return parts[0]
                if len(parts) >= 2 and parts[1] == "unauthorized":
                    log("      设备未授权：请在手机上点「允许 USB 调试」")
        except Exception:
            pass
        time.sleep(2)
    return None


def read_port(adb, serial):
    try:
        r = run_adb(adb, ["-s", serial, "shell", "cat", PORT_FILE], timeout=8)
        p = int((r.stdout or "").strip())
        if 1024 <= p <= 65535:
            return p
    except Exception:
        pass
    return DEFAULT_PORT


def setup_forward(adb, serial, port):
    run_adb(adb, ["-s", serial, "forward", "--remove", f"tcp:{port}"], timeout=8)
    r = run_adb(adb, ["-s", serial, "forward", f"tcp:{port}", f"tcp:{port}"], timeout=8)
    return r.returncode == 0


def forward_alive(adb, serial, port):
    try:
        r = run_adb(adb, ["-s", serial, "forward", "--list"], timeout=8)
        return f"tcp:{port} tcp:{port}" in (r.stdout or "")
    except Exception:
        return False


def test_endpoint(port):
    try:
        with urllib.request.urlopen(f"http://127.0.0.1:{port}/v1/models", timeout=5) as r:
            return r.status == 200
    except urllib.error.HTTPError:
        return True  # 收到任何 HTTP 响应都说明链路已通
    except Exception:
        return False


def main():
    log("=" * 46)
    log("  CloseRouter 电脑助手")
    log("=" * 46)

    adb_path_dir = extract_adb()
    if not adb_path_dir:
        log("[X] 未找到内嵌 adb，exe 可能损坏，请重新从手机导出")
        pause_exit(1)
    adb = os.path.join(adb_path_dir, "adb.exe")

    serial = wait_device(adb)
    if not serial:
        log("[X] 30 秒内未发现设备，请检查数据线 / USB 调试后重试")
        pause_exit(1)

    port = read_port(adb, serial)
    log(f"[2/5] 手机端端口: {port}")

    log("[3/5] 建立 USB 转发…")
    if not setup_forward(adb, serial, port):
        log("[X] adb forward 失败")
        pause_exit(1)

    log("[4/5] 测试端点连通性…")
    ok = False
    for _ in range(3):
        if test_endpoint(port):
            ok = True
            break
        time.sleep(1)
    if not ok:
        log("[X] 连不通 http://127.0.0.1:%d/v1 —— 请确认手机上的 CloseRouter 已点「启动」" % port)
        pause_exit(1)

    log("")
    log("  √ 已连接 CloseRouter")
    log(f"  端点: http://127.0.0.1:{port}/v1")
    log("  密钥: 任意    模型: 任意")
    log(f'  Codex 配置示例: base_url = "http://127.0.0.1:{port}/v1"')
    log("")
    log("[5/5] 守护中：重插线会自动重连，Ctrl+C 退出")

    try:
        while True:
            time.sleep(5)
            try:
                r = run_adb(adb, ["devices"], timeout=8)
                if serial not in (r.stdout or "") or not forward_alive(adb, serial, port):
                    log("  …连接断开，尝试重连…")
                    s2 = wait_device(adb)
                    if s2:
                        serial = s2
                        port = read_port(adb, serial)
                        if setup_forward(adb, serial, port) and test_endpoint(port):
                            log(f"  √ 已重连（端口 {port}）")
                        else:
                            log("  …转发已重建但端点未通，继续重试…")
            except Exception:
                pass
    except KeyboardInterrupt:
        pass
    finally:
        run_adb(adb, ["-s", serial, "forward", "--remove", f"tcp:{port}"], timeout=8)
        log("已退出，USB 转发已清理")


def pause_exit(code):
    try:
        input("按回车退出…")
    except Exception:
        pass
    sys.exit(code)


if __name__ == "__main__":
    main()
