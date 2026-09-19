#!/usr/bin/env python3
"""把手机上的用量数据同步到本机。

    python sync.py                # 拉取 + 导入
    python sync.py --pull-only    # 只拉文件，不导入
    python sync.py --report       # 只看现状，不拉取

数据落在脚本旁边的 data/ 和 usage-pc.db。

设计要点
--------
* **日聚合是全量快照** —— 每次整表替换，天然幂等，不需要记同步状态。
* **事件按天归档，文件不可变** —— 只导入 `imported` 表里没记录过的文件。
  这样完全绕开了事件去重问题（手机端时间戳只到秒，同一秒可能有多条同包同类型
  记录，靠内容做唯一键会误删）。
* 手机端原始事件**只留 30 天**，所以这个脚本至少每 30 天要跑一次，
  否则那段时间的事件归档就补不回来了（日聚合不受影响，它是永久的）。
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import shutil
import sqlite3
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
REMOTE_EXPORT = "/sdcard/Android/data/com.east.time/files/export"

def find_adb() -> str:
    """找一个可用的 adb。

    优先 `ADB` 环境变量，其次 PATH，最后几个常见的 SDK 安装位置。

    ⚠️ 如果机器上装了多个 adb（比如某些"手机当副屏"软件会自带一个），
    **务必确保本脚本和它们用的是同一个客户端版本** —— adb 客户端与服务器
    版本不一致时会互相杀掉重启，表现为随机报
    `adb server version (N) doesn't match this client (M)`。
    这种时候用 `ADB=/path/to/that/adb` 指定同一个即可。
    """
    override = os.environ.get("ADB")
    if override:
        return override

    hit = shutil.which("adb")
    if hit:
        return hit

    candidates = []
    for env in ("ANDROID_HOME", "ANDROID_SDK_ROOT"):
        root = os.environ.get(env)
        if root:
            candidates.append(Path(root) / "platform-tools" / "adb")
            candidates.append(Path(root) / "platform-tools" / "adb.exe")
    local = os.environ.get("LOCALAPPDATA")
    if local:
        candidates.append(Path(local) / "Android" / "Sdk" / "platform-tools" / "adb.exe")

    for c in candidates:
        if c.exists():
            return str(c)

    sys.exit(
        "找不到 adb。请把它加到 PATH，或用环境变量指定：\n"
        "  set ADB=D:\\path\\to\\adb.exe"
    )


def run(adb: str, *args: str) -> subprocess.CompletedProcess:
    return subprocess.run([adb, *args], capture_output=True, text=True, errors="replace")


def ensure_device(adb: str) -> None:
    r = run(adb, "devices")
    lines = [l for l in r.stdout.splitlines()[1:] if l.strip()]
    if not any("\tdevice" in l for l in lines):
        sys.exit(
            "没有已连接的设备。检查：\n"
            "  1. USB 线插着（投屏用的那根就行）\n"
            "  2. SuperDisplay 在运行\n"
            f"adb devices 输出：\n{r.stdout}"
        )


def pull(adb: str, dest: Path) -> None:
    dest.mkdir(parents=True, exist_ok=True)
    r = run(adb, "pull", REMOTE_EXPORT, str(dest))
    if r.returncode != 0:
        sys.exit(f"拉取失败：{r.stdout}{r.stderr}")
    print(f"  {r.stdout.strip().splitlines()[-1] if r.stdout.strip() else 'done'}")


def open_db(path: Path) -> sqlite3.Connection:
    c = sqlite3.connect(path)
    c.executescript(
        """
        CREATE TABLE IF NOT EXISTS rollup (
            date          TEXT    NOT NULL,
            package       TEXT    NOT NULL,
            foreground_ms INTEGER NOT NULL,
            PRIMARY KEY (date, package)
        );
        CREATE TABLE IF NOT EXISTS events (
            ts          INTEGER NOT NULL,
            user_id     INTEGER NOT NULL,
            package     TEXT    NOT NULL,
            class       TEXT,
            event_type  TEXT    NOT NULL,
            source_file TEXT    NOT NULL
        );
        CREATE INDEX IF NOT EXISTS idx_events_ts  ON events(ts);
        CREATE INDEX IF NOT EXISTS idx_events_pkg ON events(package);
        CREATE TABLE IF NOT EXISTS imported (
            file        TEXT    PRIMARY KEY,
            imported_at INTEGER NOT NULL,
            rows        INTEGER NOT NULL
        );
        """
    )
    return c


def import_rollup(conn: sqlite3.Connection, csv_path: Path) -> int:
    """全量快照 → 整表替换。不做增量合并，因为快照本身就是完整且权威的。"""
    rows = []
    with csv_path.open(newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows.append((r["date"], r["packageName"], int(r["foregroundMs"])))
    with conn:
        conn.execute("DELETE FROM rollup")
        conn.executemany("INSERT INTO rollup VALUES (?,?,?)", rows)
    return len(rows)


def import_day(conn: sqlite3.Connection, csv_path: Path) -> int:
    rows = []
    with csv_path.open(newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows.append(
                (
                    int(r["tsMillis"]),
                    int(r["userId"]),
                    r["packageName"],
                    r["className"] or None,
                    r["eventType"],
                    csv_path.name,
                )
            )
    # 这天的数据此前可能是以 today-YYYY-MM-DD.csv 进过库的（当天同步过），
    # 归档文件一到就必须先把那份可变版本删掉，否则同一天的数据会出现两遍。
    date = csv_path.name.removeprefix("events-").removesuffix(".csv")
    with conn:
        conn.execute("DELETE FROM events WHERE source_file = ?", (f"today-{date}.csv",))
        conn.execute("DELETE FROM imported WHERE file = ?", (f"today-{date}.csv",))
        conn.executemany("INSERT INTO events VALUES (?,?,?,?,?,?)", rows)
        conn.execute(
            "INSERT INTO imported VALUES (?,?,?)", (csv_path.name, int(time.time()), len(rows))
        )
    return len(rows)


def import_today(conn: sqlite3.Connection, csv_path: Path) -> int:
    """今天的文件是**可变的**，每次整块替换 —— 与归档文件的"只导一次"模型不同。"""
    rows = []
    with csv_path.open(newline="", encoding="utf-8") as f:
        for r in csv.DictReader(f):
            rows.append(
                (
                    int(r["tsMillis"]),
                    int(r["userId"]),
                    r["packageName"],
                    r["className"] or None,
                    r["eventType"],
                    csv_path.name,
                )
            )
    with conn:
        # 只清 today-* 的行，不碰 events-* 归档；顺带清掉跨天遗留的旧 today 文件
        conn.execute("DELETE FROM events WHERE source_file LIKE 'today-%'")
        conn.executemany("INSERT INTO events VALUES (?,?,?,?,?,?)", rows)
        conn.execute("DELETE FROM imported WHERE file LIKE 'today-%'")
        conn.execute(
            "INSERT INTO imported VALUES (?,?,?)", (csv_path.name, int(time.time()), len(rows))
        )
    return len(rows)


def report(conn: sqlite3.Connection) -> None:
    days, pkgs = conn.execute("SELECT COUNT(DISTINCT date), COUNT(*) FROM rollup").fetchone()
    ev, = conn.execute("SELECT COUNT(*) FROM events").fetchone()
    span = conn.execute("SELECT MIN(date), MAX(date) FROM rollup").fetchone()
    print(f"  日聚合   {days} 天 / {pkgs} 行" + (f"   {span[0]} → {span[1]}" if span[0] else ""))
    print(f"  事件     {ev} 条")
    if ev:
        mn, mx = conn.execute("SELECT MIN(ts), MAX(ts) FROM events").fetchone()
        f = lambda ms: time.strftime("%Y-%m-%d %H:%M", time.localtime(ms / 1000))
        print(f"           覆盖 {f(mn)} → {f(mx)}")
    today_n, = conn.execute("SELECT COUNT(*) FROM events WHERE source_file LIKE 'today-%'").fetchone()
    if today_n:
        print(f"   其中今天 {today_n} 条（可变，每次同步整块替换）")

    top = conn.execute(
        """SELECT package, SUM(foreground_ms) FROM rollup
           GROUP BY package ORDER BY 2 DESC LIMIT 5"""
    ).fetchall()
    if top:
        print("  累计用时 Top5：")
        for pkg, ms in top:
            print(f"    {ms / 3600000:7.1f} 小时  {pkg}")


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--pull-only", action="store_true", help="只拉文件，不导入")
    ap.add_argument("--report", action="store_true", help="只看现状，不拉取")
    args = ap.parse_args()

    data_dir = HERE / "data"
    export_dir = data_dir / "export"
    db_path = HERE / "usage-pc.db"

    if not args.report:
        adb = find_adb()
        print(f"adb: {adb}")
        ensure_device(adb)
        print("拉取导出文件…")
        pull(adb, data_dir)

        meta_path = export_dir / "meta.json"
        if meta_path.exists():
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            print(
                f"  手机端导出：{meta.get('exportedAt')}  "
                f"schema v{meta.get('schemaVersion')}  "
                f"归档 {meta.get('archivedDays')} 天"
            )

    if args.pull_only:
        return

    if not export_dir.exists():
        sys.exit(f"找不到 {export_dir}，先不加 --pull-only 跑一次")

    conn = open_db(db_path)
    try:
        rollup_csv = export_dir / "rollup.csv"
        if rollup_csv.exists():
            n = import_rollup(conn, rollup_csv)
            print(f"  导入日聚合 {n} 行（整表替换）")

        already = {r[0] for r in conn.execute("SELECT file FROM imported")}
        pending = sorted(
            f for f in export_dir.glob("events-*.csv") if f.name not in already
        )
        if pending:
            total = sum(import_day(conn, f) for f in pending)
            print(f"  导入事件 {len(pending)} 个归档 / {total} 条")
        else:
            print("  事件无新增归档")

        # 今天的数据每次整块替换（文件可变，不适用"导过就不再导"）
        today_files = sorted(export_dir.glob("today-*.csv"))
        if today_files:
            n = import_today(conn, today_files[-1])
            print(f"  今天明细 {today_files[-1].name} / {n} 条（整块替换）")

        print(f"\n本机数据库 {db_path}")
        report(conn)
    finally:
        conn.close()


if __name__ == "__main__":
    main()
