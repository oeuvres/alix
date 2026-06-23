#!/usr/bin/env python3

import argparse
import re
from pathlib import Path

from icu import Collator, Locale, UCollAttribute, UCollAttributeValue


def entry(line: str) -> str:
    """
    Extract the dictionary entry from a Hunspell dictionary line.

    The entry ends at the first unescaped slash or whitespace character.

    Args:
        line: Complete Hunspell dictionary line.

    Returns:
        The entry used as the collation key.
    """
    escaped = False

    for index, char in enumerate(line):
        if escaped:
            escaped = False
            continue

        if char == "\\":
            escaped = True
            continue

        if char == "/" or char.isspace():
            return line[:index]

    return line


def sort_dictionary(path: Path, output: Path) -> None:
    """
    Sort a Hunspell dictionary using French ICU collation.

    A numeric Hunspell entry-count header is preserved. Lines having the same
    entry remain in their original relative order.

    Args:
        path: Input dictionary path.
        output: Output dictionary path.
    """
    text = path.read_text(encoding="utf-8-sig")
    lines = text.splitlines()

    header = None
    if lines and re.fullmatch(r"\d+", lines[0].strip()):
        header = lines.pop(0)

    collator = Collator.createInstance(Locale("fr_FR"))
    collator.setAttribute(
        UCollAttribute.NORMALIZATION_MODE,
        UCollAttributeValue.ON,
    )

    lines.sort(key=lambda line: collator.getSortKey(entry(line)))

    result = lines
    if header is not None:
        result = [header, *lines]

    output.write_text("\n".join(result) + "\n", encoding="utf-8")


def main() -> None:
    """
    Parse command-line arguments and sort the requested dictionary.
    """
    parser = argparse.ArgumentParser(
        description="Sort a Hunspell dictionary using French ICU collation."
    )
    parser.add_argument("input", type=Path, help="Input .dic file")
    parser.add_argument(
        "output",
        nargs="?",
        type=Path,
        help="Output .dic file; defaults to replacing the input file",
    )
    args = parser.parse_args()

    output = args.output or args.input
    sort_dictionary(args.input, output)


if __name__ == "__main__":
    main()