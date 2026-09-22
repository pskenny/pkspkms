#!/usr/bin/env python3
# /// script
# requires-python = ">=3.10"
# dependencies = []
# ///
"""Profile a vault the way the PKSPKMS loader sees it.

Mirrors RepositoryFileLoader's walk: all file types, hidden files included,
directories excluded only by name (.trash, .obsidian, Archive, Calibre, Sort,
Templates).

Also reports a link graph mirroring PKSPKMS's resolution semantics (alias/
heading stripping, path -> name.ext -> name lookup, links inside code fences
counted — known quirk B34). Note: pkspkms's LINKS table stores each edge
twice (outgoing + wikilink, BUGS B29), so its logged link count is ~2x the
distinct edges reported here.

Usage: uv run vault_stats.py <vault-dir> [--top N] [--json]
"""

import argparse
import json
import os
import re
import statistics
import sys
from collections import Counter
from pathlib import Path

EXCLUDED_DIRS = {".trash", ".obsidian", "Archive", "Calibre", "Sort", "Templates", "pkspkms-cache"}
UTF8_BOM = b"\xef\xbb\xbf"
LARGE_FILE_BYTES = 1024 * 1024
MD_EXT = ".md"

# Mirrors the readers in io.processor.markdown (code-fence links included, B34)
WIKILINK_RE = re.compile(r"\[\[(.*?)]]")
EMBED_RE = re.compile(r"!\[\[(.*?)]]")
MD_LINK_RE = re.compile(r"\[(.*?)\]\((.*?)\)")


def human(n: int) -> str:
    value = float(n)
    for unit in ("B", "KB", "MB", "GB", "TB"):
        if value < 1024 or unit == "TB":
            return f"{value:,.1f} {unit}" if unit != "B" else f"{int(value)} B"
        value /= 1024
    return f"{n} B"


def collect(vault: Path) -> list[dict]:
    files = []
    for root, dirs, names in os.walk(vault):
        dirs[:] = [d for d in dirs if d not in EXCLUDED_DIRS]

        for name in names:
            path = Path(root) / name
            try:
                size = path.stat().st_size
            except OSError:
                continue

            entry = {
                "path": str(path.relative_to(vault)),
                "ext": path.suffix.lower() or "(none)",
                "size": size,
                "bom": False,
            }

            if entry["ext"] == MD_EXT:
                try:
                    with path.open("rb") as fh:
                        entry["bom"] = fh.read(3) == UTF8_BOM
                except OSError:
                    pass

            files.append(entry)
    return files


def ext_summary(sizes: list[int]) -> dict:
    return {
        "count": len(sizes),
        "total": sum(sizes),
        "avg": round(statistics.mean(sizes)) if sizes else 0,
        "median": round(statistics.median(sizes)) if sizes else 0,
        "max": max(sizes, default=0),
    }


def percentile(sizes: list[int], pct: float) -> int:
    if len(sizes) < 2:
        return max(sizes, default=0)
    # Interpolated quantiles can exceed max on small samples; clamp to the data
    value = round(statistics.quantiles(sizes, n=100)[int(pct) - 1])
    return min(value, max(sizes))


def link_target(raw: str) -> str:
    # Mirror pkspkms B18 semantics: alias (|) stripped first, then heading (#)
    return raw.split("|", 1)[0].split("#", 1)[0].strip()


def analyze_links(vault: Path, files: list[dict], top: int) -> dict:
    """Link graph mirroring pkspkms resolution: path -> name.ext -> name."""
    by_path, by_name_ext, by_name = {}, {}, {}
    for f in files:
        by_path[f["path"]] = f["path"]
        by_name_ext[os.path.basename(f["path"])] = f["path"]
        by_name[os.path.splitext(os.path.basename(f["path"]))[0]] = f["path"]

    def resolve(target: str):
        for table in (by_path, by_name_ext, by_name):
            if target in table:
                return table[target]
        return None

    wikilinks = embeds = md_links = 0
    out_edges = {}       # source -> set of resolved targets
    in_edges = Counter()  # target -> distinct sources
    broken = []          # (target, source) unresolved
    self_links = 0
    notes = {f["path"] for f in files if f["ext"] == MD_EXT}

    for f in files:
        if f["ext"] != MD_EXT:
            continue
        try:
            content = (vault / f["path"]).read_text(encoding="utf-8", errors="replace")
        except OSError:
            continue

        out = out_edges.setdefault(f["path"], set())

        for raw in EMBED_RE.findall(content):
            embeds += 1
        for raw in WIKILINK_RE.findall(content):
            wikilinks += 1
            target = resolve(link_target(raw))
            if target is None:
                broken.append((link_target(raw), f["path"]))
            elif target == f["path"]:
                self_links += 1
            else:
                if target not in out:
                    in_edges[target] += 1
                out.add(target)
        for _text, dest in MD_LINK_RE.findall(content):
            if dest.lower().startswith(("http://", "https://")):
                continue
            md_links += 1
            target = resolve(dest.strip())
            if target is None:
                broken.append((dest.strip(), f["path"]))
            elif target == f["path"]:
                self_links += 1
            else:
                if target not in out:
                    in_edges[target] += 1
                out.add(target)

    edges = sum(len(t) for t in out_edges.values())
    linking_out = sum(1 for s in out_edges if s in notes and out_edges[s])
    linked_in = len(in_edges)
    orphans = sorted(p for p in notes if not out_edges.get(p) and p not in in_edges)

    def target_is_note(path: str) -> bool:
        return path.endswith(MD_EXT)

    media_edges = sum(1 for targets in out_edges.values() for t in targets if not target_is_note(t))

    return {
        "notes": len(notes),
        "edges": edges,
        "wikilinks": wikilinks,
        "embeds": embeds,
        "markdown_links": md_links,
        "media_edges": media_edges,
        "broken_targets": len({t for t, _ in broken}),
        "broken_occurrences": len(broken),
        "self_links": self_links,
        "avg_out_per_note": round(edges / len(notes), 2) if notes else 0,
        "avg_out_per_linking_note": round(edges / linking_out, 2) if linking_out else 0,
        "avg_backlinks_per_linked_note": round(edges / linked_in, 2) if linked_in else 0,
        "orphan_count": len(orphans),
        "orphans": orphans[:top],
        "top_backlinked": in_edges.most_common(top),
        "most_out_links": sorted(
            ((src, len(t)) for src, t in out_edges.items() if t),
            key=lambda kv: kv[1], reverse=True)[:top],
        "broken_sample": broken[:top],
    }


def aggregate(files: list[dict], top: int) -> dict:
    by_ext = {}
    for f in files:
        by_ext.setdefault(f["ext"], []).append(f["size"])

    ext_rows = sorted(
        ({"ext": ext, **ext_summary(sizes)} for ext, sizes in by_ext.items()),
        key=lambda r: r["total"],
        reverse=True,
    )

    md_sizes = by_ext.get(MD_EXT, [])
    markdown = {**ext_summary(md_sizes), "p90": percentile(md_sizes, 90)}
    bom_count = sum(1 for f in files if f["bom"])

    largest = sorted(files, key=lambda f: f["size"], reverse=True)[:top]
    total = sum(f["size"] for f in files)

    return {
        "file_count": len(files),
        "total_bytes": total,
        "large_file_count": sum(1 for f in files if f["size"] >= LARGE_FILE_BYTES),
        "extensions": ext_rows,
        "markdown": markdown,
        "largest_files": [
            {"path": f["path"], "ext": f["ext"], "size": f["size"]} for f in largest
        ],
        "bom_markdown_count": bom_count,
    }


def print_report(data: dict, vault: str) -> None:
    print(f"Vault: {vault}")
    print(f"Walk mirrors pkspkms loader; excluded dirs: {', '.join(sorted(EXCLUDED_DIRS))}")
    print()

    print("Overview")
    print(f"  Files: {data['file_count']}")
    print(f"  Total size: {human(data['total_bytes'])}")
    print(f"  Files >= 1 MB: {data['large_file_count']}")
    print()

    print("By extension (largest total first)")
    print(f"  {'ext':<12}{'count':>7}{'total':>12}{'avg':>12}{'median':>12}{'max':>12}")
    for row in data["extensions"]:
        print(
            f"  {row['ext']:<12}{row['count']:>7}"
            f"{human(row['total']):>12}{human(row['avg']):>12}"
            f"{human(row['median']):>12}{human(row['max']):>12}"
        )
    print()

    md = data["markdown"]
    if md["count"]:
        print("Markdown detail")
        print(f"  count={md['count']}  total={human(md['total'])}  avg={human(md['avg'])}")
        print(f"  median={human(md['median'])}  p90={human(md['p90'])}  max={human(md['max'])}")
        print()

    g = data["link_graph"]
    print("Link graph (mirrors pkspkms resolution; pkspkms LINKS rows ~2x edges, BUGS B29)")
    print(f"  Notes: {g['notes']} | Distinct edges: {g['edges']} "
          f"(wikilinks {g['wikilinks']}, embeds {g['embeds']}, md links {g['markdown_links']})")
    print(f"  Media edges: {g['media_edges']} | Broken targets: {g['broken_targets']} "
          f"({g['broken_occurrences']} occurrences) | Self-links: {g['self_links']}")
    print(f"  Avg out-links per note: {g['avg_out_per_note']} "
          f"(per linking note: {g['avg_out_per_linking_note']})")
    print(f"  Avg backlinks per linked note: {g['avg_backlinks_per_linked_note']}")
    print(f"  Orphan notes (no in/out links): {g['orphan_count']}")
    if g["orphans"]:
        print(f"    e.g. {', '.join(g['orphans'][:5])}")
    if g["top_backlinked"]:
        print("  Top backlinked: "
              + ", ".join(f"{p} ({n})" for p, n in g["top_backlinked"][:5]))
    if g["most_out_links"]:
        print("  Most out-links: "
              + ", ".join(f"{p} ({n})" for p, n in g["most_out_links"][:5]))
    if g["broken_sample"]:
        print("  Broken sample: "
              + ", ".join(f"[[{t}]] from {s}" for t, s in g["broken_sample"][:5]))
    print()

    print(f"Largest {len(data['largest_files'])} files")
    for f in data["largest_files"]:
        print(f"  {human(f['size']):>12}  {f['ext']:<8}{f['path']}")
    print()

    print(f"BOM-prefixed markdown (frontmatter silently lost, see BUGS.md B36): {data['bom_markdown_count']}")


def main() -> int:
    parser = argparse.ArgumentParser(description="Profile a vault like the PKSPKMS loader does")
    parser.add_argument("vault", type=Path, help="Vault directory to profile")
    parser.add_argument("--top", type=int, default=10, help="How many largest files to show (default 10)")
    parser.add_argument("--json", action="store_true", help="Emit machine-readable JSON")
    args = parser.parse_args()

    if not args.vault.is_dir():
        parser.error(f"not a directory: {args.vault}")

    files = collect(args.vault)
    data = aggregate(files, args.top)
    data["link_graph"] = analyze_links(args.vault, files, args.top)

    if args.json:
        print(json.dumps(data, indent=2))
    else:
        print_report(data, str(args.vault))
    return 0


if __name__ == "__main__":
    sys.exit(main())
