#!/usr/bin/env python3
"""
Convert music filenames to kebab-case.

  "Anfa After Hours.mp3"     -> "anfa-after-hours.mp3"
  "Anfa After Hours (1).mp3" -> "anfa-after-hours-1.mp3"

Usage:
  python scripts/kebab-music.py            # dry-run (default)
  python scripts/kebab-music.py --apply    # actually rename
"""

import re
import sys
from pathlib import Path

MUSIC_DIR = Path(__file__).resolve().parent.parent / "music"


def to_kebab(stem: str) -> str:
    # "Foo Bar (1)" -> "Foo Bar -1"
    s = re.sub(r"\s*\((\d+)\)", r"-\1", stem)
    # spaces / underscores -> hyphens
    s = re.sub(r"[\s_]+", "-", s)
    # lowercase, collapse repeated hyphens, trim
    s = re.sub(r"-+", "-", s.lower()).strip("-")
    return s


def main() -> int:
    apply = "--apply" in sys.argv
    if not MUSIC_DIR.is_dir():
        print(f"music dir not found: {MUSIC_DIR}", file=sys.stderr)
        return 1

    plans: list[tuple[Path, Path]] = []
    for src in sorted(MUSIC_DIR.iterdir()):
        if not src.is_file():
            continue
        new_stem = to_kebab(src.stem)
        dst = src.with_name(new_stem + src.suffix.lower())
        if dst.name == src.name:
            continue
        plans.append((src, dst))

    if not plans:
        print("nothing to rename")
        return 0

    # detect conflicts: target already exists (case-insensitive on Windows)
    existing_lower = {p.name.lower() for p in MUSIC_DIR.iterdir()}
    src_lower = {s.name.lower() for s, _ in plans}
    target_counts: dict[str, int] = {}
    for _, dst in plans:
        target_counts[dst.name.lower()] = target_counts.get(dst.name.lower(), 0) + 1

    conflicts = []
    for src, dst in plans:
        # collision with another rename target
        if target_counts[dst.name.lower()] > 1:
            conflicts.append((src, dst, "duplicate target"))
            continue
        # collision with existing file that is NOT itself being renamed
        if dst.name.lower() in existing_lower and dst.name.lower() not in src_lower:
            conflicts.append((src, dst, "target already exists"))

    if conflicts:
        print("CONFLICTS — refusing to rename:")
        for src, dst, why in conflicts:
            print(f"  {src.name!r} -> {dst.name!r}  ({why})")
        print()

    print(f"{'APPLY' if apply else 'DRY-RUN'} ({len(plans)} renames):")
    for src, dst in plans:
        marker = "!" if any(c[0] == src for c in conflicts) else " "
        print(f"  {marker} {src.name}  ->  {dst.name}")

    if conflicts and apply:
        print("\naborting: resolve conflicts first", file=sys.stderr)
        return 2

    if apply:
        # two-phase rename to avoid case-only collisions on Windows
        for src, dst in plans:
            tmp = src.with_name(src.name + ".__renaming__")
            src.rename(tmp)
        for src, dst in plans:
            tmp = src.with_name(src.name + ".__renaming__")
            tmp.rename(dst)
        print("done")
    else:
        print("\n(dry-run; pass --apply to perform renames)")
    return 0


if __name__ == "__main__":
    sys.exit(main())
