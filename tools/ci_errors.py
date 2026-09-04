#!/usr/bin/env python3
"""CI: превращает ошибки компиляции из build.log в GitHub annotations.

Каждая annotation несёт 5 строк контекста (сама ошибка + symbol/location),
чтобы причину было видно прямо через API check-runs — артефакты из песочницы
не скачиваются.
"""
import re
import sys

PATTERN = re.compile(r"^(\S+?\.java):(\d+): (\w+):(.*)$")
CROP = 220


def main() -> None:
    try:
        with open("build.log", encoding="utf-8", errors="replace") as handle:
            lines = handle.read().splitlines()
    except OSError:
        return

    out = []
    for i, line in enumerate(lines):
        match = PATTERN.match(line)
        if not match:
            continue
        path, lineno, kind, rest = match.groups()
        context = [line.strip() for line in lines[i:i + 5] if line.strip()]
        message = "%0A".join(part[:CROP] for part in context)
        message = message.replace("%", "%25").replace("\r", "")
        # %0A уже вставлены; повторное экранирование выше ломает их — чиним
        message = message.replace("%250A", "%0A")
        out.append(f"::{kind} file={path},line={lineno}::{message}")
        if len(out) >= 60:
            break

    if out:
        print("\n".join(out))


if __name__ == "__main__":
    try:
        main()
    except Exception as error:  # noqa: BLE001 — CI не должен падать на отчёте
        print(f"::warning::ci_errors: {error}", file=sys.stderr)
