#!/usr/bin/env python3
"""
Sort a Hunspell dictionary using French ICU collation.

Multiword expressions are handled according to Hunspell dictionary syntax:
ordinary spaces may belong to the entry, while a whitespace-delimited field
such as ``po:NOUN`` or ``st:lemma`` begins the morphological description.

Examples
--------
The collation keys extracted from these lines are:

    en fait de po:ADP
        -> en fait de

    château fort/S. po:NOUN
        -> château fort

    mot po:NOUN
        -> mot

    mot composé
        -> mot composé
"""

from __future__ import annotations

import argparse
import re
from pathlib import Path


MORPH_FIELD = re.compile(r"^[^\s:]{2}:")


def entry(line: str) -> str:
    """
    Extract the lexical entry used as the collation key.

    Hunspell permits spaces inside dictionary entries. A whitespace-delimited
    token beginning with a two-character morphological tag and ``:`` marks the
    start of the morphological description. An unescaped slash before that
    boundary introduces affix flags.

    A leading ``*`` is treated as the project's review marker and is excluded
    from the sort key, so unresolved entries remain beside their lexical
    neighbours.

    Args:
        line: Complete Hunspell dictionary line.

    Returns:
        The unescaped lexical entry without affix flags or morphology.
    """
    lexical_line = line.strip()

    if lexical_line.startswith("*"):
        lexical_line = lexical_line[1:]

    morphology_start = len(lexical_line)

    for match in re.finditer(r"\S+", lexical_line):
        if MORPH_FIELD.match(match.group()):
            morphology_start = match.start()
            break

    lexical_part = lexical_line[:morphology_start].rstrip()
    word_chars: list[str] = []
    escaped = False

    for char in lexical_part:
        if escaped:
            word_chars.append(char)
            escaped = False
            continue

        if char == "\\":
            escaped = True
            continue

        if char == "/":
            break

        word_chars.append(char)

    if escaped:
        word_chars.append("\\")

    return "".join(word_chars).rstrip()


def sort_dictionary(path: Path, output: Path) -> None:
    """
    Sort a Hunspell dictionary using French ICU collation.

    A numeric Hunspell entry-count header is preserved. Python's sort is
    stable, so lines with the same lexical entry retain their original
    relative order.

    Args:
        path: Input dictionary path.
        output: Output dictionary path.
    """
    try:
        from icu import (
            Collator,
            Locale,
            UCollAttribute,
            UCollAttributeValue,
        )
    except ImportError as error:
        raise RuntimeError(
            "PyICU is required. Install it with: pip install PyICU"
        ) from error

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

    result = lines if header is None else [header, *lines]

    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(
        "\n".join(result) + "\n",
        encoding="utf-8",
        newline="\n",
    )


def main() -> None:
    """Parse command-line arguments and sort the requested dictionary."""
    parser = argparse.ArgumentParser(
        description=(
            "Sort a Hunspell dictionary with French ICU collation while "
            "preserving multiword entries."
        )
    )
    parser.add_argument("input", type=Path, help="Input .dic file")
    parser.add_argument(
        "output",
        nargs="?",
        type=Path,
        help="Output .dic file; defaults to replacing the input file",
    )
    args = parser.parse_args()

    try:
        sort_dictionary(args.input, args.output or args.input)
    except RuntimeError as error:
        parser.error(str(error))


if __name__ == "__main__":
    main()
