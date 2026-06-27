#!/usr/bin/env python3
"""
Merge Hunspell .dic files or headerless .dic fragments.

The script:

- accepts files with or without a numeric Hunspell entry-count header;
- preserves multiword entries unchanged;
- ignores blank lines and source comments beginning with "#";
- rejects review lines beginning with "*" by default;
- removes only exact duplicate dictionary entries;
- preserves input-file order and line order by default;
- optionally sorts the final entries;
- writes the correct numeric header;
- writes an optional TSV audit file.

Examples
--------
Merge fragments in the specified order:

    python merge_hunspell_dic.py \
        common.dic persons.dic places.dic \
        -o fr-alix.dic

Use a wildcard pattern or a source directory:

    python merge_hunspell_dic.py "src/*.dic" -o build/fr-alix.dic

    python merge_hunspell_dic.py src -o build/fr-alix.dic

Sort entries after merging:

    python merge_hunspell_dic.py src/*.dic \
        -o build/fr-alix.dic \
        --sort

Keep unresolved "*" entries instead of failing:

    python merge_hunspell_dic.py src/*.dic \
        -o build/fr-alix-review.dic \
        --allow-starred

Write an audit report:

    python merge_hunspell_dic.py src/*.dic \
        -o build/fr-alix.dic \
        --audit build/fr-alix-merge.tsv
"""

from __future__ import annotations

import argparse
import csv
import glob
import sys
import unicodedata
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable, Sequence


@dataclass(frozen=True)
class Entry:
    """A dictionary entry and its source location."""

    text: str
    source: Path
    line_number: int


def build_argument_parser() -> argparse.ArgumentParser:
    """Build and return the command-line argument parser."""
    parser = argparse.ArgumentParser(
        description=(
            "Merge Hunspell .dic files or headerless fragments into one "
            "dictionary with a correct entry-count header."
        )
    )
    parser.add_argument(
        "inputs",
        nargs="+",
        help=(
            "Input .dic files, directories, or wildcard patterns, "
            "in merge order. A directory contributes its immediate *.dic files."
        ),
    )
    parser.add_argument(
        "-o",
        "--output",
        required=True,
        type=Path,
        help="Output Hunspell .dic file.",
    )
    parser.add_argument(
        "--allow-starred",
        action="store_true",
        help=(
            "Allow lines beginning with '*'. By default they are treated "
            "as unresolved review entries and cause validation to fail."
        ),
    )
    parser.add_argument(
        "--audit",
        type=Path,
        help="Optional TSV file recording kept and duplicate entries.",
    )
    parser.add_argument(
        "--no-normalize",
        action="store_true",
        help="Do not normalize entries to Unicode NFC.",
    )
    parser.add_argument(
        "--sort",
        action="store_true",
        help="Sort entries by Unicode code-point order after merging.",
    )
    return parser


def expand_inputs(arguments: Sequence[str]) -> list[Path]:
    """
    Expand files, directories and wildcard patterns deterministically.

    Directory arguments contribute their immediate ``*.dic`` files. Wildcard
    matches and directory contents are sorted, while the top-level argument
    order is preserved.
    """
    expanded: list[Path] = []

    for argument in arguments:
        candidate = Path(argument)

        if candidate.is_dir():
            matches = sorted(
                path
                for path in candidate.glob("*.dic")
                if path.is_file()
            )
        elif any(character in argument for character in "*?[]"):
            matches = sorted(
                Path(match)
                for match in glob.glob(argument)
                if Path(match).is_file()
            )
        else:
            matches = [candidate]

        if not matches:
            raise ValueError(f"{argument}: no input dictionary found")

        expanded.extend(matches)

    return expanded


def is_numeric_header(line: str) -> bool:
    """Return whether a line is a valid non-negative integer header."""
    stripped = line.strip()
    return bool(stripped) and stripped.isascii() and stripped.isdecimal()


def read_entries(
    path: Path,
    *,
    allow_starred: bool,
    normalize: bool,
) -> list[Entry]:
    """
    Read one dictionary or fragment.

    A numeric first non-blank, non-comment line is treated as a Hunspell
    count header and is not copied as an entry.

    Returns
    -------
    list[Entry]
        Parsed dictionary entries.
    """
    try:
        raw_lines = path.read_text(encoding="utf-8").splitlines()
    except UnicodeDecodeError as error:
        raise ValueError(f"{path}: file is not valid UTF-8: {error}") from error

    entries: list[Entry] = []
    header_consumed = False
    first_content_seen = False

    for line_number, raw_line in enumerate(raw_lines, start=1):
        line = raw_line.strip()

        if not line:
            continue

        if line.startswith("#"):
            continue

        if not first_content_seen:
            first_content_seen = True
            if is_numeric_header(line):
                header_consumed = True
                continue

        if line.startswith("*") and not allow_starred:
            raise ValueError(
                f"{path}:{line_number}: unresolved review entry begins with '*': "
                f"{line}"
            )

        if normalize:
            line = unicodedata.normalize("NFC", line)

        if "\x00" in line:
            raise ValueError(
                f"{path}:{line_number}: NUL character is not allowed"
            )

        entries.append(
            Entry(
                text=line,
                source=path,
                line_number=line_number,
            )
        )

    if first_content_seen and header_consumed:
        declared = next(
            int(raw_line.strip())
            for raw_line in raw_lines
            if raw_line.strip() and not raw_line.strip().startswith("#")
        )
        if declared != len(entries):
            print(
                f"warning: {path}: header declares {declared} entries, "
                f"but {len(entries)} body entries were read",
                file=sys.stderr,
            )

    return entries


def merge_entries(
    entries: Iterable[Entry],
) -> tuple[list[Entry], list[tuple[Entry, Entry]]]:
    """
    Remove exact duplicate entries while preserving their first occurrence.

    Entries with the same headword but different flags or morphological
    fields are retained.
    """
    first_by_text: dict[str, Entry] = {}
    kept: list[Entry] = []
    duplicates: list[tuple[Entry, Entry]] = []

    for entry in entries:
        first = first_by_text.get(entry.text)
        if first is None:
            first_by_text[entry.text] = entry
            kept.append(entry)
        else:
            duplicates.append((entry, first))

    return kept, duplicates


def write_audit(
    path: Path,
    kept: Sequence[Entry],
    duplicates: Sequence[tuple[Entry, Entry]],
) -> None:
    """Write a TSV audit describing kept and removed duplicate entries."""
    path.parent.mkdir(parents=True, exist_ok=True)

    with path.open("w", encoding="utf-8", newline="") as stream:
        writer = csv.writer(stream, delimiter="\t")
        writer.writerow(
            [
                "action",
                "entry",
                "source",
                "line",
                "first_source",
                "first_line",
            ]
        )

        for entry in kept:
            writer.writerow(
                [
                    "kept",
                    entry.text,
                    str(entry.source),
                    entry.line_number,
                    "",
                    "",
                ]
            )

        for duplicate, first in duplicates:
            writer.writerow(
                [
                    "duplicate_removed",
                    duplicate.text,
                    str(duplicate.source),
                    duplicate.line_number,
                    str(first.source),
                    first.line_number,
                ]
            )


def write_dictionary(
    path: Path,
    entries: Sequence[Entry],
) -> None:
    """Write the merged Hunspell dictionary with the correct count header."""
    path.parent.mkdir(parents=True, exist_ok=True)

    output_lines = [str(len(entries))]
    output_lines.extend(entry.text for entry in entries)

    path.write_text(
        "\n".join(output_lines) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def main() -> int:
    """Merge the requested dictionary files and return a process exit code."""
    parser = build_argument_parser()
    arguments = parser.parse_args()

    all_entries: list[Entry] = []

    try:
        input_paths = expand_inputs(arguments.inputs)
        output_resolved = arguments.output.resolve()

        for input_path in input_paths:
            if not input_path.is_file():
                raise ValueError(f"{input_path}: input file does not exist")

            if input_path.resolve() == output_resolved:
                raise ValueError(
                    f"{input_path}: output file must not also be an input"
                )

            entries = read_entries(
                input_path,
                allow_starred=True,
                normalize=not arguments.no_normalize,
            )
            all_entries.extend(entries)

        kept, duplicates = merge_entries(all_entries)

        if arguments.sort:
            kept.sort(key=lambda entry: entry.text)

        write_dictionary(arguments.output, kept)

        if arguments.audit is not None:
            write_audit(arguments.audit, kept, duplicates)

    except ValueError as error:
        parser.error(str(error))
        return 2

    print(f"input entries:      {len(all_entries)}")
    print(f"duplicates removed: {len(duplicates)}")
    print(f"output entries:     {len(kept)}")
    print(f"output file:        {arguments.output}")

    if arguments.audit is not None:
        print(f"audit file:         {arguments.audit}")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
