#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
分析 uiautomator dump 出的 XML，输出界面字段清单。

用法：
    python tools/analyze_ui.py docs/samples/chat-page.xml

输出：
  * 所有属性及其出现次数
  * resource-id 汇总（次数 / 类名 / 样本文本）
  * text 与 content-desc 分组
  * NAF 节点（微信私有属性，标记"无障碍不友好"）
  * clickable 且有文本的节点
"""

import collections
import io
import sys
import xml.etree.ElementTree as ET


def main() -> None:
    if len(sys.argv) < 2:
        print("usage: python tools/analyze_ui.py <page.xml>")
        sys.exit(1)

    path = sys.argv[1]
    nodes = list(ET.parse(path).getroot().iter("node"))
    out = io.open(path + ".analysis.txt", "w", encoding="utf-8")

    def w(s: str = "") -> None:
        out.write(s + "\n")
        print(s)

    w(f"file: {path}")
    w(f"total nodes: {len(nodes)}")

    # 1. 属性统计
    attrs = collections.Counter()
    for n in nodes:
        for k in n.attrib:
            attrs[k] += 1
    w("\n=== 属性出现次数 ===")
    for k, c in attrs.most_common():
        w(f"  {k}: {c}")

    # 2. resource-id 汇总
    rid_count = collections.Counter()
    rid_class = {}
    rid_sample = {}
    for n in nodes:
        r = n.get("resource-id", "")
        if not r:
            continue
        rid_count[r] += 1
        rid_class.setdefault(r, n.get("class", ""))
        t = n.get("text", "") or n.get("content-desc", "")
        if t and r not in rid_sample:
            rid_sample[r] = t[:30]
    w("\n=== resource-id 汇总 ===")
    for r, c in rid_count.most_common(50):
        w(f"  {r}  x{c}  [{rid_class[r].split('.')[-1]}]  {rid_sample.get(r, '')!r}")

    # 3. text 分组
    w("\n=== text 节点分组 ===")
    g = collections.defaultdict(list)
    for n in nodes:
        t = n.get("text", "")
        if t:
            g[n.get("resource-id", "").split("/")[-1]].append(t)
    for k, v in g.items():
        w(f"  {k}: {len(v)} 条 -> {v[:8]}")

    # 4. content-desc 分组
    w("\n=== content-desc 节点分组 ===")
    g2 = collections.defaultdict(list)
    for n in nodes:
        d = n.get("content-desc", "")
        if d:
            g2[n.get("resource-id", "").split("/")[-1]].append(d)
    for k, v in g2.items():
        w(f"  {k}: {len(v)} 条 -> {v[:8]}")

    # 5. NAF
    w("\n=== NAF 节点（微信私有属性）===")
    naf = [n for n in nodes if "NAF" in n.attrib]
    if not naf:
        w("  (none)")
    for n in naf:
        w(
            f"  NAF={n.get('NAF')} rid={n.get('resource-id', '').split('/')[-1]} "
            f"class={n.get('class', '').split('.')[-1]} "
            f"text={n.get('text', '')!r} cd={n.get('content-desc', '')!r}"
        )

    # 6. clickable 且可见文本
    w("\n=== clickable 且有文本/描述的节点 ===")
    for n in nodes:
        if n.get("clickable") == "true":
            t = n.get("text", "") or n.get("content-desc", "")
            if t:
                w(f"  {n.get('resource-id', '').split('/')[-1]:>10} {t!r}")

    out.close()
    print(f"\n分析结果已写入: {path}.analysis.txt")


if __name__ == "__main__":
    main()
