#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE_SUFFIXES = {".kt", ".java", ".xml", ".kts", ".sh", ".py"}
EXCLUDED_PARTS = {"build", ".gradle"}
LIMIT = 300


def line_count(path: Path) -> int:
    with path.open("r", encoding="utf-8", errors="replace") as handle:
        return sum(1 for _ in handle)


def main() -> None:
    rows = []
    for path in ROOT.rglob("*"):
        if not path.is_file() or path.suffix not in SOURCE_SUFFIXES:
            continue
        if any(part in EXCLUDED_PARTS for part in path.parts):
            continue
        count = line_count(path)
        if count > LIMIT:
            rows.append((count, path.relative_to(ROOT)))

    for count, path in sorted(rows, key=lambda row: (-row[0], str(row[1]))):
        print(f"{count:5d}  {path}")

    print(f"\n{len(rows)} files exceed {LIMIT} lines.")


if __name__ == "__main__":
    main()
