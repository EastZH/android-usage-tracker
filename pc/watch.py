#!/usr/bin/env python3
"""待命监听：手机上一按「导出」，这里立刻把数据拉过来。

    python watch.py            # 启动后挂着，按 Ctrl-C 退出

启动一次就够了，之后只管在手机上点按钮 —— 不用再碰电脑。

工作原理
--------
USB 传输里 PC 是主机端，手机上的 App 没有能力主动发起，所以 App 点按钮时
只做两件事：把导出文件写好、把 `REQUEST` 写成当前时间戳。这个脚本通过 adb
监听那个文件，发现值变了就执行一次拉取。

**刻意只起一个 adb 进程。** 在 PC 上轮询一般是"每 N 秒 spawn 一次 adb"，
一天下来几万次进程创建；这里改成让设备端跑循环、PC 端读流，全程只有一个进程。

不用 Windows 计划任务 —— 你手动启动它，它就只在你在场时工作。
"""

from __future__ import annotations

import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

from sync import REMOTE_EXPORT, ensure_device, find_adb, open_db, pull, run  # noqa: E402

POLL_SECONDS = 2
REMOTE_REQUEST = f"{REMOTE_EXPORT}/REQUEST"
REMOTE_ACK = f"{REMOTE_EXPORT}/ACK"

# 在设备端跑循环，PC 端只是读流 —— 避免每轮都 spawn 一个 adb
WATCH_CMD = (
    f'while true; do cat {REMOTE_REQUEST} 2>/dev/null || echo none; echo "###"; sleep {POLL_SECONDS}; done'
)


def do_pull(adb: str, request_value: str) -> None:
    stamp = time.strftime("%H:%M:%S")
    print(f"\n[{stamp}] 收到请求，开始拉取…")
    try:
        data_dir = HERE / "data"
        pull(adb, data_dir)

        conn = open_db(HERE / "usage-pc.db")
        try:
            from sync import import_day, import_rollup, report

            rollup_csv = data_dir / "export" / "rollup.csv"
            if rollup_csv.exists():
                n = import_rollup(conn, rollup_csv)
                print(f"  日聚合 {n} 行（整表替换）")

            already = {r[0] for r in conn.execute("SELECT file FROM imported")}
            pending = sorted(
                f for f in (data_dir / "export").glob("events-*.csv") if f.name not in already
            )
            if pending:
                total = sum(import_day(conn, f) for f in pending)
                print(f"  事件 {len(pending)} 个归档 / {total} 条")
            else:
                print("  事件无新增归档")

            report(conn)
        finally:
            conn.close()

        # 回执：把刚处理的那个请求值写回手机。手机上据此显示"电脑已拉取" ——
        # 否则用户点了按钮而电脑没在听时，手机端看不出任何区别。
        run(adb, "shell", f"echo {request_value} > {REMOTE_ACK}")

        print(f"[{time.strftime('%H:%M:%S')}] 完成，继续待命…")
    except SystemExit as e:
        print(f"  失败：{e}")
    except Exception as e:  # 网络/IO 抽风不该让监听退出
        print(f"  失败：{type(e).__name__}: {e}")


def main() -> None:
    adb = find_adb()
    ensure_device(adb)
    print(f"adb: {adb}")
    print(f"监听 {REMOTE_REQUEST}（每 {POLL_SECONDS}s 查一次）")
    print("在手机上点「导出」，数据就会自动过来。Ctrl-C 退出。\n")

    while True:
        # 外层的 while 用于 adb 断开后自动重连（拔线、手机重启、投屏软件抢占都会断）
        proc = subprocess.Popen(
            [adb, "shell", WATCH_CMD],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
            errors="replace",
            bufsize=1,
        )
        try:
            last: str | None = None
            for line in proc.stdout:  # type: ignore[union-attr]
                line = line.strip()
                if line == "###":
                    continue
                if not line:
                    continue
                # 首轮把当前值记为基线，避免一启动就误触发
                if last is None:
                    last = line
                    continue
                if line != last:
                    last = line
                    do_pull(adb, line)
        except KeyboardInterrupt:
            proc.terminate()
            print("\n退出。")
            return
        finally:
            proc.terminate()

        print("adb 连接断开，5 秒后重连…")
        time.sleep(5)


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n退出。")
