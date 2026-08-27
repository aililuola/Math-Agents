"""Mechanically add defensive collection/JSON accessors to phase-02 records."""

from __future__ import annotations

import re
from pathlib import Path


START = "  // BEGIN GENERATED DEFENSIVE ACCESSORS\n"
END = "  // END GENERATED DEFENSIVE ACCESSORS\n"
COMPONENT = re.compile(
    r"^\s+(?:@JsonProperty\(.*\)|@JsonIgnore)"
    r"(?: @ContractNonNull)? (?P<type>.+) (?P<name>[A-Za-z_$][\w$]*),?$"
)


def project_root() -> Path:
    return Path(__file__).absolute().parents[2]


def accessor_value(java_type: str, name: str) -> str | None:
    if java_type in {"List<JsonNode>", "List<ObjectNode>"}:
        return f"ImmutableCollections.copyJsonList({name})"
    if java_type == "Map<String, JsonNode>":
        return f"ImmutableCollections.copyJsonMap({name})"
    if java_type == "Map<String, List<String>>":
        return f"ImmutableCollections.copyStringListMap({name})"
    if java_type.startswith("List<"):
        return f"List.copyOf({name})"
    if java_type.startswith("Map<"):
        return f"Map.copyOf({name})"
    if java_type in {"ObjectNode", "JsonNode"}:
        return f"{name}.deepCopy()"
    return None


def replace_json_normalization(
    text: str, java_type: str, name: str
) -> str:
    if java_type in {"List<JsonNode>", "List<ObjectNode>"}:
        replacements = {
            f"ImmutableCollections.listOrEmpty({name})":
                f"ImmutableCollections.jsonListOrEmpty({name})",
            f"ImmutableCollections.nullableList({name})":
                f"ImmutableCollections.nullableJsonList({name})",
            f"ImmutableCollections.requiredList(":
                f"ImmutableCollections.requiredJsonList(",
        }
    elif java_type == "Map<String, JsonNode>":
        replacements = {
            f"ImmutableCollections.mapOrEmpty({name})":
                f"ImmutableCollections.jsonMapOrEmpty({name})",
            f"ImmutableCollections.nullableMap({name})":
                f"ImmutableCollections.nullableJsonMap({name})",
            f"ImmutableCollections.requiredMap(":
                f"ImmutableCollections.requiredJsonMap(",
        }
    elif java_type == "Map<String, List<String>>":
        replacements = {
            f"ImmutableCollections.mapOrEmpty({name})":
                f"ImmutableCollections.stringListMapOrEmpty({name})",
            f"ImmutableCollections.nullableMap({name})":
                f"ImmutableCollections.nullableStringListMap({name})",
            f"ImmutableCollections.requiredMap(":
                f"ImmutableCollections.requiredStringListMap(",
        }
    else:
        return text
    for old, new in replacements.items():
        if old.startswith("ImmutableCollections.required"):
            pattern = re.compile(
                re.escape(old)
                + r'("[^"]+",\s*'
                + re.escape(name)
                + r"\))"
            )
            text = pattern.sub(lambda match: new + match.group(1), text)
        else:
            text = text.replace(old, new)
    return text


def update_record(path: Path) -> bool:
    text = path.read_text(encoding="utf-8")
    header = re.search(
        r"public record \w+\((.*?)\n\) implements StrictContract",
        text,
        re.DOTALL,
    )
    if header is None:
        return False
    components: list[tuple[str, str]] = []
    for line in header.group(1).splitlines():
        match = COMPONENT.match(line)
        if match:
            components.append((match.group("type"), match.group("name")))
    methods: list[str] = []
    for java_type, name in components:
        value = accessor_value(java_type, name)
        if value is None:
            continue
        text = replace_json_normalization(text, java_type, name)
        methods.append(
            f"  public {java_type} {name}() {{\n"
            f"    return {name} == null ? null : {value};\n"
            "  }\n"
        )
    if not methods:
        return False
    block = START + "\n".join(methods) + END
    if START in text:
        text = re.sub(
            re.escape(START) + r".*?" + re.escape(END),
            block,
            text,
            flags=re.DOTALL,
        )
    else:
        closing = text.rfind("}")
        text = text[:closing] + "\n" + block + text[closing:]
    path.write_text(text, encoding="utf-8", newline="\n")
    return True


def main() -> None:
    source_root = (
        project_root()
        / "mathproofmesh-contracts"
        / "src"
        / "main"
        / "java"
        / "io"
        / "github"
        / "aililuola"
        / "mathproofmesh"
        / "contracts"
    )
    changed = sum(update_record(path) for path in source_root.glob("*.java"))
    print(f"updated defensive accessors in {changed} phase-02 records")


if __name__ == "__main__":
    main()
