#!/usr/bin/env python3
"""按「细分口径」统计模块耦合，定位跨层 import。

口径（与 ARCHITECTURE.md「耦合热点」表的粗口径**不同**，两者不可直接对比）：
  模块 = 一二级包目录，下列二级包单列，其余二级目录并入一级包：
    ui/screens  ui/viewModel  ui/components  ui/navigation  ui/glass  ui/theme
    ui/pagingSource  ui/composable  ui/interaction  ui/models  ui/state
    cache/migration  cache/atom
    database/model  database/dao  database/converter
    retrofit/model  retrofit/service  retrofit/converter  retrofit/interceptor
    download/{coordinator,molecule,atom,export,model}
    reader/{atom,molecule,coordinator}
    favorites/{data,model,sync,usecase,presentation}
    core/{model,network}  data/comic  repository/impl
  Ce = 该模块 import 到的模块数
  Ca = 依赖它的模块数
  I  = Ce / (Ce + Ca)

用法：
  python3 scripts/check-coupling.py              # 全量耦合表
  python3 scripts/check-coupling.py ui/screens   # 只看指定模块的出边/入边明细
"""
import os
import re
import sys
from collections import defaultdict

ROOT = os.path.join(
    os.path.dirname(os.path.abspath(__file__)),
    "..", "app", "src", "main", "java", "com", "par9uet", "jm",
)

SINGLE = {
    ("ui", "screens"), ("ui", "viewModel"), ("ui", "components"), ("ui", "navigation"),
    ("ui", "glass"), ("ui", "theme"), ("ui", "pagingSource"), ("ui", "composable"),
    ("ui", "interaction"), ("ui", "models"), ("ui", "state"),
    ("cache", "migration"), ("cache", "atom"),
    ("database", "model"), ("database", "dao"), ("database", "converter"),
    ("retrofit", "model"), ("retrofit", "service"), ("retrofit", "converter"),
    ("retrofit", "interceptor"),
    ("download", "coordinator"), ("download", "molecule"), ("download", "atom"),
    ("download", "export"), ("download", "model"),
    ("reader", "atom"), ("reader", "molecule"), ("reader", "coordinator"),
    ("favorites", "data"), ("favorites", "model"), ("favorites", "sync"),
    ("favorites", "usecase"), ("favorites", "presentation"),
    ("core", "model"), ("core", "network"), ("data", "comic"), ("repository", "impl"),
}

IMPORT_RE = re.compile(r"^\s*import\s+(com\.par9uet\.jm\.[A-Za-z0-9_.]+)\s*$")


def module_of(parts):
    if not parts:
        return "(root)"
    if len(parts) >= 2 and (parts[0], parts[1]) in SINGLE:
        return f"{parts[0]}/{parts[1]}"
    return parts[0]


def scan():
    imports = defaultdict(lambda: defaultdict(int))
    files = defaultdict(int)
    lines = defaultdict(int)
    for dirpath, _, filenames in os.walk(ROOT):
        for fn in filenames:
            if not fn.endswith(".kt"):
                continue
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, ROOT)
            src = module_of(rel.split(os.sep))
            files[src] += 1
            with open(path, encoding="utf-8", errors="replace") as f:
                for line in f:
                    lines[src] += 1
                    m = IMPORT_RE.match(line)
                    if not m:
                        continue
                    fqn = m.group(1).split(".")
                    dst = module_of(fqn[3:]) if len(fqn) > 3 else None
                    if dst and dst != src:
                        imports[src][dst] += 1
    dependents = defaultdict(set)
    for src, dsts in imports.items():
        for dst in dsts:
            dependents[dst].add(src)
    return imports, dependents, files, lines


def main():
    imports, dependents, files, lines = scan()
    target = sys.argv[1] if len(sys.argv) > 1 else None

    if target:
        print(f"[{target}]  files={files.get(target, 0)} lines={lines.get(target, 0)}"
              f" Ce={len(imports.get(target, {}))}")
        for dst, cnt in sorted(imports.get(target, {}).items(), key=lambda kv: -kv[1]):
            print(f"    -> {dst:<24} {cnt:>4}")
        srcs = sorted(dependents.get(target, ()))
        print(f"    Ca={len(srcs)}: {srcs}")
        return

    rows = []
    for mod in sorted(set(lines)):
        ce = len(imports.get(mod, {}))
        ca = len(dependents.get(mod, ()))
        rows.append((mod, files[mod], lines[mod], ce, ca,
                     ce / (ce + ca) if ce + ca else 0.0))
    print(f"{'module':<24}{'files':>6}{'lines':>8}{'Ce':>5}{'Ca':>5}{'I':>7}")
    print("-" * 55)
    for mod, f, l, ce, ca, i in sorted(rows, key=lambda r: -r[5]):
        print(f"{mod:<24}{f:>6}{l:>8}{ce:>5}{ca:>5}{i:>7.2f}")
    total_files = sum(files.values())
    total_lines = sum(lines.values())
    print(f"\n合计 {total_files} 个 Kotlin 文件 / {total_lines} 行")


if __name__ == "__main__":
    main()
