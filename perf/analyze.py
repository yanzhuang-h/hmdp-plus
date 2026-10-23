#!/usr/bin/env python
# -*- coding: utf-8 -*-
"""分析 JMeter jtl 结果，输出 QPS / P50 / P95 / P99 / 错误率 表格。"""
import os
import sys
import pandas as pd

PERF_DIR = os.path.dirname(os.path.abspath(__file__))


def analyze(tag, label=None):
    path = os.path.join(PERF_DIR, f"{tag}.jtl")
    if not os.path.exists(path):
        return None
    df = pd.read_csv(path, low_memory=False)
    if df.empty:
        return None
    df["elapsed"] = pd.to_numeric(df["elapsed"], errors="coerce")
    df["timeStamp"] = pd.to_numeric(df["timeStamp"], errors="coerce")
    df = df.dropna(subset=["elapsed", "timeStamp"])
    n = len(df)
    span = (df["timeStamp"].max() - df["timeStamp"].min()) / 1000.0
    qps = n / span if span > 0 else 0
    elapsed = df["elapsed"]
    err = 0
    if "success" in df.columns:
        err = (~df["success"].astype(str).str.lower().isin(["true", "1"])).mean() * 100
    return {
        "tag": tag,
        "label": label or tag,
        "samples": n,
        "duration_s": round(span, 1),
        "qps": round(qps, 1),
        "avg": round(elapsed.mean(), 1),
        "p50": round(elapsed.quantile(0.50), 1),
        "p95": round(elapsed.quantile(0.95), 1),
        "p99": round(elapsed.quantile(0.99), 1),
        "max": int(elapsed.max()),
        "err_pct": round(err, 3),
    }


def main():
    targets = [
        ("smoke", "冒烟 10线程"),
        ("diag", "诊断 50线程"),
        ("t100", "100线程"),
        ("t200", "200线程"),
        ("t400", "400线程"),
        ("shop_cache", "店铺详情(缓存) 100线程"),
        ("seckill", "秒杀下单 100线程"),
    ]
    rows = [r for r in (analyze(t, l) for t, l in targets) if r]
    if not rows:
        print("没有找到任何 jtl 结果文件")
        return 1

    df = pd.DataFrame(rows)
    print("\n========== 压测结果汇总 ==========\n")
    print(df.to_string(index=False))

    out = os.path.join(PERF_DIR, "summary.csv")
    df.to_csv(out, index=False, encoding="utf-8-sig")
    print(f"\n已保存: {out}")

    # 生成 Markdown 表格，便于贴进报告
    md = os.path.join(PERF_DIR, "summary.md")
    with open(md, "w", encoding="utf-8") as f:
        f.write("| 场景 | 样本数 | 时长(s) | QPS | Avg(ms) | P50 | P95 | P99 | Max | 错误率 |\n")
        f.write("|---|---|---|---|---|---|---|---|---|---|\n")
        for r in rows:
            f.write(
                f"| {r['label']} | {r['samples']} | {r['duration_s']} | {r['qps']} | "
                f"{r['avg']} | {r['p50']} | {r['p95']} | {r['p99']} | {r['max']} | {r['err_pct']}% |\n"
            )
    print(f"已保存: {md}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
