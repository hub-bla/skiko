#!/usr/bin/env python3

import argparse
from pathlib import Path
from typing import Sequence


def aggregate(inputs_dir: Path, output: Path) -> None:
    if not inputs_dir.is_dir():
        raise FileNotFoundError(f"Directory not found: {inputs_dir}")
    rows = [
        line
        for fragment in sorted(inputs_dir.rglob("*.md"))
        for line in fragment.read_text(encoding="utf-8").splitlines()
        if line.startswith("| ") and not line.startswith("| Platform ")
    ]

    def sort_key(line: str) -> Sequence[str]:
        fields = line.split("|")
        return fields[1].strip(), fields[3].strip(), fields[4].strip()

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        "\n".join(
            [
                "### Combined binary sizes",
                "",
                "| Platform | Arch | Source | File | Size | SHA256 |",
                "|---|---|---|---|---:|---|",
                *sorted(rows, key=sort_key),
                "",
            ]
        ),
        encoding="utf-8",
    )


def main() -> None:
    parser = argparse.ArgumentParser(description="Aggregate binary size reports")
    parser.add_argument("inputs_dir", type=Path)
    parser.add_argument("output", type=Path)
    arguments = parser.parse_args()
    try:
        aggregate(arguments.inputs_dir, arguments.output)
    except OSError as error:
        parser.exit(1, f"error: {error}\n")


if __name__ == "__main__":
    main()