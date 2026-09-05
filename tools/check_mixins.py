#!/usr/bin/env python3
"""CI: проверяем, что цели миксинов реально существуют в Minecraft 26.2.

Зачем
-----
В этом репозитории почти все миксины объявлены с ``require = 0`` (или молча
терпят опечатку): если имя метода или поля написано неверно, Mixin просто не
находит цель, ``@Inject`` не применяется — и модуль «работает», но ничего не
делает. Компилятор этого не видит, игровой запуск в CI тоже. Поэтому имена
целей сверяются напрямую с ``javap`` по деобфусцированному jar Minecraft.

Что проверяется
---------------
* ``@Inject/@Redirect/@ModifyArg/@ModifyVariable/...`` — ``method = "name"``;
* ``@Invoker("name")``;
* ``@Shadow`` поля и методы (цель = класс из ``@Mixin(...)``);
* ``@Accessor("name")``;
* ``@Redirect(target = "owner::method")`` — и владелец, и метод.

Метод считается найденным, если он объявлен в самом классе ИЛИ в любом
родителе по цепочке (Mixin позволяет целиться в унаследованные методы, и это
легальный случай). Дескрипторы из ``method = "name(Lx;)V"`` игнорируются:
проверяется имя.

Запуск: ``python3 tools/check_mixins.py --jar <minecraft.jar>``. В CI jar
ищется в лоом-кэше; при ``--jar auto`` — так же.
"""
from __future__ import annotations

import argparse
import os
import re
import subprocess
import sys
import zipfile
from collections import OrderedDict

MIXIN_DIR = os.path.join("src", "main", "java", "com", "dreamcast", "client", "mixin")

# Аннотации, у которых есть method = ...
METHOD_ANNOTATIONS = (
    "Inject", "Redirect", "ModifyArg", "ModifyArgs", "ModifyConstant",
    "ModifyVariable", "ModifyReturnValue", "WrapOperation", "WrapMethod",
)

METHOD_ANN_RE = re.compile(r"@(Inject|Redirect|ModifyArg|ModifyArgs|ModifyConstant|ModifyVariable|ModifyReturnValue|WrapOperation|WrapMethod)\b")
MIXIN_ANN_RE = re.compile(r"@Mixin\s*\(([^)]*)\)", re.S)
METHOD_ATTR_RE = re.compile(r'method\s*=\s*(\{[^}]*\}|"(?:[^"\\]|\\.)*")', re.S)
TARGET_ATTR_RE = re.compile(r'target\s*=\s*(\{[^}]*\}|"(?:[^"\\]|\\.)*")', re.S)
AT_TARGET_RE = re.compile(r'@At\s*\((?:[^()]|\([^()]*\))*?target\s*=\s*(\{[^}]*\}|"(?:[^"\\]|\\.)*")', re.S)
STRING_RE = re.compile(r'"((?:[^"\\]|\\.)*)"')
JAVAP_MEMBER_RE = re.compile(r"^\s*(?:public|protected|private|static|final|transient|volatile|synchronized|abstract|native)\b.*?[\s<(.]([\w$]+)\s*[(;]")


def declarations(text: str) -> list:
    """Разбивает исходник на строки с номерами — для поиска @Shadow-объявлений."""
    return text.splitlines()


def shadow_members(text: str) -> list:
    """(имя, kind, alias|None, строка) для каждого @Shadow-объявления.

    Разбор построчный: аннотация @Shadow, затем (возможно через @Nullable и
    модификаторы) одна строка объявления — поле с ``;`` или метод с ``(``.
    """
    lines = text.splitlines()
    out = []
    index = 0
    while index < len(lines):
        line = lines[index]
        if "@Shadow" not in line:
            index += 1
            continue
        alias = None
        match = re.search(r'@Shadow\s*\(\s*(?:value\s*=\s*)?"([^"]+)"', line)
        if match:
            alias = match.group(1).split("(")[0]
        elif "@Shadow" in line and "(" in line:
            # @Shadow(aliases = {"a", "b"}) — берём первую алиасу
            block = re.search(r'aliases\s*=\s*\{?\s*"([^"]+)"', line)
            if block:
                alias = block.group(1).split("(")[0]
        # ищем строку объявления (до 4 строк вперёд: модификаторы, @Nullable)
        cursor = index + 1
        declaration = ""
        while cursor < len(lines) and cursor < index + 5:
            candidate = lines[cursor].strip()
            if not candidate or candidate.startswith("@"):
                cursor += 1
                continue
            declaration = candidate
            break
        if declaration:
            kind = "field" if re.search(r";\s*$", declaration) else "method"
            name_match = re.search(r"([\w$]+)\s*[;(]", declaration)
            if name_match:
                out.append((alias or name_match.group(1), kind, alias is not None, index + 1))
        index = cursor + 1 if declaration else index + 1
    return out


def accessor_names(text: str) -> list:
    out = []
    for match in re.finditer(r'@Accessor\s*\(\s*(?:\{\s*)?"([^"]+)"', text):
        out.append((match.group(1), text[:match.start()].count("\n") + 1))
    return out


def invoker_names(text: str) -> list:
    out = []
    for match in re.finditer(r'@Invoker\s*\(\s*"([^"]+)"', text):
        out.append((strip_descriptor(match.group(1)), text[:match.start()].count("\n") + 1))
    return out


def split_owner_member(value: str) -> tuple:
    """``Lnet/minecraft/Foo;::bar(Lx;)V`` → (``net.minecraft.Foo``, ``bar``)."""
    value = value.strip()
    owner = None
    member = None
    if "::" in value:
        left, member = value.rsplit("::", 1)
        owner = left.lstrip("L").split(";")[0].replace("/", ".")
        member = strip_descriptor(member)
    elif value.startswith("L"):
        owner = value.lstrip("L").split(";")[0].replace("/", ".")
    return owner, member


def strip_descriptor(value: str) -> str:
    return value.split("(")[0].strip()


def quoted(block: str) -> list:
    return STRING_RE.findall(block or "")


def resolve_owner(simple: str, imports: dict, pkg: str):
    if "." in simple:
        return simple
    return imports.get(simple, f"{pkg}.{simple}" if pkg else simple)


def parse_mixin_file(path: str) -> dict:
    with open(path, encoding="utf-8") as handle:
        text = handle.read()
    pkg_match = re.search(r"^\s*package\s+([\w.]+)\s*;", text, re.M)
    pkg = pkg_match.group(1) if pkg_match else ""
    imports = {}
    for match in re.finditer(r"^\s*import\s+(?:static\s+)?([\w.]+)\s*;", text, re.M):
        imports[match.group(1).rsplit(".", 1)[1]] = match.group(1)

    owners = []
    for match in MIXIN_ANN_RE.finditer(text):
        body = match.group(1)
        for name in re.findall(r"([\w.]+)\.class", body):
            owners.append(resolve_owner(name, imports, pkg))
        for name in re.findall(r'targets?\s*=\s*[^,}]*"([\w.$]+)"', body):
            owners.append(name)

    targets = []
    for match in METHOD_ANN_RE.finditer(text):
        annotation = match.group(1)
        # окно: от начала аннотации до её закрывающей скобки — ищем method= внутри
        window = text[match.start():match.start() + 1200]
        line = text[:match.start()].count("\n") + 1
        require_soft = "require = 0" in window or "require=0" in window
        block = METHOD_ATTR_RE.search(window)
        if block:
            for name in quoted(block.group(1)):
                targets.append((owners[0] if owners else None, annotation,
                                strip_descriptor(name), line, require_soft))
        if annotation == "Redirect":
            redirect = TARGET_ATTR_RE.search(window)
            if redirect:
                for name in quoted(redirect.group(1)):
                    if "::" in name:
                        owner, member = name.rsplit("::", 1)
                        owner = owner.lstrip("L").split(";")[0].replace("/", ".")
                        targets.append((owner, "Redirect.target", strip_descriptor(member), line, require_soft))
        # @At(value = "INVOKE"/"FIELD", target = "...") — тоже цель: опечатка здесь
        # роняет применение миксина уже в игре, поэтому проверяем наравне с method=
        for at_match in AT_TARGET_RE.finditer(window):
            for name in quoted(at_match.group(1)):
                owner, member = split_owner_member(name)
                if owner:
                    targets.append((owner, "At.target", member, line, require_soft))
                elif member:
                    targets.append((owners[0] if owners else None, "At.target", member, line, require_soft))

    for owner in owners:
        for name, kind, had_alias, line in shadow_members(text):
            targets.append((owner, "Shadow " + kind, name, line, had_alias))
        for name, line in accessor_names(text):
            targets.append((owner, "Accessor", name, line, False))
        for name, line in invoker_names(text):
            targets.append((owner, "Invoker", name, line, False))

    return {"path": path, "owners": [o for o in owners if o], "targets": targets}


def javap_members(jar: str, owner: str, cache: dict) -> tuple[set, bool]:
    """(имена членов класса, найден ли класс вовсе) — с проходом по родителям."""
    key = owner.replace("/", ".")
    if key in cache:
        return cache[key]
    names: set = set()
    exists = False
    chain = [key]
    seen = set()
    depth = 0
    while chain and depth < 8:
        current = chain.pop(0)
        if current in seen:
            continue
        seen.add(current)
        depth += 1
        try:
            out = subprocess.run(
                ["javap", "-p", "-cp", jar, current],
                capture_output=True, text=True, timeout=120,
            )
        except Exception as error:  # noqa: BLE001 - тулза для CI, падать не должна
            print(f"  ! javap {current}: {error}", file=sys.stderr)
            continue
        text = out.stdout or ""
        if not text.strip() or "class not found" in (out.stderr or ""):
            continue
        exists = True
        for line in text.splitlines():
            match = JAVAP_MEMBER_RE.match(line)
            if match:
                names.add(match.group(1))
            # идём вверх по иерархии — Mixin легально целится в унаследованное
            parent = re.search(r"(?:class|interface)\s+[\w$.]+(?:<[^>]*>)?\s+extends\s+([\w$.]+)", line)
            if parent and parent.group(1) not in ("java.lang.Object",):
                chain.append(parent.group(1))
        if "<init>" in text:
            names.add("<init>")
    cache[key] = (names, exists)
    return cache[key]


def find_jar() -> str | None:
    roots = [os.path.expanduser("~/.gradle"), os.path.join(os.getcwd(), ".gradle"), os.getcwd()]
    seen = set()
    for root in roots:
        for dirpath, dirnames, filenames in os.walk(root):
            dirnames[:] = [d for d in dirnames if d not in ("build", ".git", "caches", "daemon", "workers", "notifications")]
            for name in filenames:
                if not name.endswith(".jar") or "source" in name.lower():
                    continue
                path = os.path.join(dirpath, name)
                if path in seen:
                    continue
                seen.add(path)
                try:
                    with zipfile.ZipFile(path) as archive:
                        if "net/minecraft/client/renderer/GameRenderer.class" in archive.namelist():
                            return path
                except Exception:  # noqa: BLE001
                    continue
    # второй проход: уже по caches (там loom-кэш)
    for root in roots:
        for dirpath, dirnames, filenames in os.walk(os.path.join(root, "caches")):
            for name in filenames:
                if not name.endswith(".jar") or "source" in name.lower():
                    continue
                path = os.path.join(dirpath, name)
                try:
                    with zipfile.ZipFile(path) as archive:
                        names = archive.namelist()
                        if "net/minecraft/client/renderer/GameRenderer.class" in names:
                            return path
                except Exception:  # noqa: BLE001
                    continue
    return None


def suggest(name: str, members: set) -> str:
    """Близкие имена членов того же класса: обычно цель не удалили, а переименовали."""
    lower = name.lower()
    scored = []
    for candidate in members:
        current = candidate.lower()
        if current == lower or current.startswith("<"):
            continue
        prefix = len(os.path.commonprefix([lower, current]))
        # renderArmWithItem → submitArmWithItem: общий хвост важнее начала
        suffix = len(os.path.commonprefix([lower[::-1], current[::-1]]))
        score = max(prefix, suffix)
        if score >= 5:
            scored.append((score, candidate))
    scored.sort(reverse=True)
    return ", ".join(candidate for _, candidate in scored[:4])


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--jar", default="auto", help="путь к jar Minecraft или 'auto'")
    parser.add_argument("--dir", default=MIXIN_DIR)
    parser.add_argument("--max-errors", type=int, default=40)
    args = parser.parse_args()

    jar = args.jar
    if jar == "auto":
        jar = find_jar()
        if not jar:
            print("::warning::jar Minecraft не найден — проверка целей миксинов пропущена")
            return 0
        print(f"jar: {jar}")

    if not os.path.isdir(args.dir):
        print(f"::warning::каталог миксинов не найден: {args.dir}")
        return 0

    files = OrderedDict()
    for name in sorted(os.listdir(args.dir)):
        if name.endswith(".java"):
            files[os.path.join(args.dir, name)] = parse_mixin_file(os.path.join(args.dir, name))

    cache: dict = {}
    problems = []
    checked = 0
    for info in files.values():
        for owner, kind, name, line, soft in info["targets"]:
            if not owner:
                continue
            members, exists = javap_members(jar, owner, cache)
            checked += 1
            if not exists:
                if not soft:
                    problems.append((info["path"], line, f"класс-цель не найден: {owner}"))
                continue

            if name.startswith("method_") or name.startswith("field_"):
                continue  # intermediary-имена: их в 26.2 нет, но пусть будет
            if name not in members:
                marker = " (require=0 — тихо не применится)" if soft else ""
                hint = suggest(name, members)
                problems.append((info["path"], line,
                                 f"{kind} {name}: нет в {owner}{marker}"
                                 + (f"; похожее: {hint}" if hint else "")))

    print(f"проверено целей: {checked}, файлов миксинов: {len(files)}")
    if not problems:
        print("все цели миксинов найдены в jar Minecraft")
        return 0
    shown = problems[: args.max_errors]
    for path, line, message in shown:
        safe = message.replace("%", "%25").replace("\r", "")
        print(f"::error file={path},line={line}::{safe}")
    if len(problems) > len(shown):
        print(f"::error::и ещё {len(problems) - len(shown)} несовпадений целей миксинов")
    return 1


if __name__ == "__main__":
    sys.exit(main())
