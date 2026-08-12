from __future__ import annotations

import ast
import hashlib
import json
import re
import shutil
import zipfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any


EXPECTED_ZIP_SHA256 = (
    "5e09eb8a704ea5f841078bafcc1c5e72e4d17a76b2fef7a344e41d55e013c1b2"
)
ARCHIVE_PREFIX = (
    "Math-Agents-feature-mathproofmesh-v0.8.0-goal-plan-failure-utility-control/"
)
PACKAGE = "io.github.aililuola.mathproofmesh.contract"


@dataclass(frozen=True)
class PythonField:
    name: str
    required: bool
    default: ast.expr | None
    default_factory: ast.expr | None
    excluded: bool


def project_root() -> Path:
    # Keep a subst drive intact on Windows so generated source paths stay short.
    return Path(__file__).absolute().parents[2]


def read_authoritative_source(root: Path) -> str:
    archive = root / "migration" / "input" / (
        "Math-Agents-feature-mathproofmesh-v0.8.0-goal-plan-failure-utility-control.zip"
    )
    digest = hashlib.sha256(archive.read_bytes()).hexdigest()
    if digest != EXPECTED_ZIP_SHA256:
        raise RuntimeError(f"authoritative ZIP hash mismatch: {digest}")
    with zipfile.ZipFile(archive) as source_zip:
        return source_zip.read(ARCHIVE_PREFIX + "src/mathproofmesh/schemas.py").decode(
            "utf-8"
        )


def python_fields(source: str) -> dict[str, dict[str, PythonField]]:
    tree = ast.parse(source)
    result: dict[str, dict[str, PythonField]] = {}
    for node in tree.body:
        if not isinstance(node, ast.ClassDef):
            continue
        fields: dict[str, PythonField] = {}
        for item in node.body:
            if not isinstance(item, ast.AnnAssign) or not isinstance(
                item.target, ast.Name
            ):
                continue
            default: ast.expr | None = None
            factory: ast.expr | None = None
            excluded = False
            required = item.value is None
            if isinstance(item.value, ast.Call) and call_name(item.value.func) == "Field":
                required = True
                for keyword in item.value.keywords:
                    if keyword.arg == "default":
                        default = keyword.value
                        required = False
                    elif keyword.arg == "default_factory":
                        factory = keyword.value
                        required = False
                    elif keyword.arg == "exclude":
                        excluded = literal_value(keyword.value) is True
            elif item.value is not None:
                default = item.value
                required = False
            fields[item.target.id] = PythonField(
                name=item.target.id,
                required=required,
                default=default,
                default_factory=factory,
                excluded=excluded,
            )
        result[node.name] = fields
    return result


def call_name(node: ast.expr) -> str:
    if isinstance(node, ast.Name):
        return node.id
    if isinstance(node, ast.Attribute):
        return node.attr
    return ""


def literal_value(node: ast.expr) -> Any:
    try:
        return ast.literal_eval(node)
    except (ValueError, TypeError):
        return None


def camel(name: str) -> str:
    parts = name.split("_")
    value = parts[0] + "".join(part[:1].upper() + part[1:] for part in parts[1:])
    if value in {
        "abstract",
        "assert",
        "boolean",
        "break",
        "byte",
        "case",
        "catch",
        "char",
        "class",
        "const",
        "continue",
        "default",
        "do",
        "double",
        "else",
        "enum",
        "extends",
        "final",
        "finally",
        "float",
        "for",
        "goto",
        "if",
        "implements",
        "import",
        "instanceof",
        "int",
        "interface",
        "long",
        "native",
        "new",
        "package",
        "private",
        "protected",
        "public",
        "record",
        "return",
        "sealed",
        "short",
        "static",
        "strictfp",
        "super",
        "switch",
        "synchronized",
        "this",
        "throw",
        "throws",
        "transient",
        "try",
        "void",
        "volatile",
        "while",
        "yield",
    }:
        return value + "Value"
    return value


def quote(value: str) -> str:
    return json.dumps(value, ensure_ascii=True)


def unwrap_nullable(schema: dict[str, Any]) -> tuple[dict[str, Any], bool]:
    alternatives = schema.get("anyOf") or schema.get("oneOf")
    if not alternatives:
        return schema, False
    non_null = [item for item in alternatives if item.get("type") != "null"]
    if len(non_null) == 1 and len(non_null) != len(alternatives):
        return non_null[0], True
    return schema, False


def java_type(schema: dict[str, Any]) -> tuple[str, bool]:
    schema, nullable = unwrap_nullable(schema)
    ref = schema.get("$ref")
    if ref:
        return ref.rsplit("/", 1)[-1], nullable
    schema_type = schema.get("type")
    if schema_type == "string":
        return "String", nullable
    if schema_type == "integer":
        return "Integer", nullable
    if schema_type == "number":
        return "Double", nullable
    if schema_type == "boolean":
        return "Boolean", nullable
    if schema_type == "array":
        item_type, _ = java_type(schema.get("items", {}))
        return f"List<{item_type}>", nullable
    if schema_type == "object":
        additional = schema.get("additionalProperties")
        if isinstance(additional, dict):
            value_type, _ = java_type(additional)
            return f"Map<String, {value_type}>", nullable
        return "ObjectNode", nullable
    return "JsonNode", nullable


def java_default(
    field: PythonField, java_type_name: str, schema: dict[str, Any]
) -> str | None:
    expression = field.default
    if expression is not None:
        if isinstance(expression, ast.Constant):
            value = expression.value
            if value is None:
                return "null"
            if isinstance(value, bool):
                return str(value).lower()
            if isinstance(value, str):
                return quote(value)
            if isinstance(value, float):
                return repr(value) + "d"
            return str(value)
        if isinstance(expression, ast.UnaryOp) and isinstance(expression.op, ast.USub):
            return "-" + str(literal_value(expression.operand))
        if isinstance(expression, ast.Attribute):
            return ast.unparse(expression)
        raise RuntimeError(
            f"unsupported default for {field.name}: {ast.dump(expression)}"
        )

    factory = field.default_factory
    if factory is None:
        return None
    if isinstance(factory, ast.Name):
        if factory.id == "list":
            return "List.of()"
        if factory.id == "dict":
            return (
                "JsonNodeFactory.instance.objectNode()"
                if java_type_name == "ObjectNode"
                else "Map.of()"
            )
        if factory.id == "utc_now_iso":
            return "PythonIsoTimestampCodec.now()"
        return f"new {factory.id}()"
    if isinstance(factory, ast.Lambda):
        body = factory.body
        if isinstance(body, ast.Call) and call_name(body.func) == "new_id":
            prefix = literal_value(body.args[0])
            return f"PythonCompatibleIdGenerator.newId({quote(prefix)})"
        if isinstance(body, ast.List):
            values = ", ".join(ast.unparse(item) for item in body.elts)
            return f"List.of({values})"
    raise RuntimeError(
        f"unsupported default factory for {field.name}: {ast.dump(factory)}"
    )


def normalization_lines(
    source_name: str,
    java_name: str,
    java_type_name: str,
    schema: dict[str, Any],
    required: bool,
    default: str | None,
) -> list[str]:
    lines: list[str] = []
    if default is not None and default != "null":
        lines.append(f"if ({java_name} == null) {{")
        lines.append(f"  {java_name} = {default};")
        lines.append("}")

    base_schema, nullable = unwrap_nullable(schema)
    if java_type_name == "String":
        lines.append(f"{java_name} = ContractStrings.trim({java_name});")
        if required:
            lines.append(
                f"{java_name} = ContractStrings.required({quote(source_name)}, {java_name});"
            )
        minimum = base_schema.get("minLength")
        maximum = base_schema.get("maxLength")
        if minimum is not None:
            lines.append(
                f"ContractValues.minimumLength({quote(source_name)}, {java_name}, {minimum});"
            )
        if maximum is not None:
            lines.append(
                f"ContractValues.maximumLength({quote(source_name)}, {java_name}, {maximum});"
            )
        allowed = base_schema.get("enum")
        if allowed:
            values = ", ".join(quote(str(item)) for item in allowed)
            lines.append(
                f"ContractValues.oneOf({quote(source_name)}, {java_name}, {values});"
            )
    elif java_type_name in {"List<JsonNode>", "List<ObjectNode>"}:
        if nullable:
            lines.append(
                f"{java_name} = ImmutableCollections.nullableJsonList({java_name});"
            )
        elif required:
            lines.append(
                f"{java_name} = ImmutableCollections.requiredJsonList("
                f"{quote(source_name)}, {java_name});"
            )
        else:
            lines.append(
                f"{java_name} = ImmutableCollections.jsonListOrEmpty({java_name});"
            )
        minimum = base_schema.get("minItems")
        maximum = base_schema.get("maxItems")
        if minimum is not None:
            lines.append(
                f"ContractValues.minimumSize({quote(source_name)}, {java_name}, {minimum});"
            )
        if maximum is not None:
            lines.append(
                f"ContractValues.maximumSize({quote(source_name)}, {java_name}, {maximum});"
            )
    elif java_type_name.startswith("List<"):
        if nullable:
            lines.append(f"{java_name} = ImmutableCollections.nullableList({java_name});")
        elif required:
            lines.append(
                f"{java_name} = ImmutableCollections.requiredList({quote(source_name)}, {java_name});"
            )
        else:
            lines.append(f"{java_name} = ImmutableCollections.listOrEmpty({java_name});")
        minimum = base_schema.get("minItems")
        maximum = base_schema.get("maxItems")
        if minimum is not None:
            lines.append(
                f"ContractValues.minimumSize({quote(source_name)}, {java_name}, {minimum});"
            )
        if maximum is not None:
            lines.append(
                f"ContractValues.maximumSize({quote(source_name)}, {java_name}, {maximum});"
            )
    elif java_type_name == "Map<String, JsonNode>":
        if nullable:
            lines.append(
                f"{java_name} = ImmutableCollections.nullableJsonMap({java_name});"
            )
        elif required:
            lines.append(
                f"{java_name} = ImmutableCollections.requiredJsonMap("
                f"{quote(source_name)}, {java_name});"
            )
        else:
            lines.append(
                f"{java_name} = ImmutableCollections.jsonMapOrEmpty({java_name});"
            )
    elif java_type_name == "Map<String, List<String>>":
        if nullable:
            lines.append(
                f"{java_name} = ImmutableCollections.nullableStringListMap({java_name});"
            )
        elif required:
            lines.append(
                f"{java_name} = ImmutableCollections.requiredStringListMap("
                f"{quote(source_name)}, {java_name});"
            )
        else:
            lines.append(
                f"{java_name} = ImmutableCollections.stringListMapOrEmpty({java_name});"
            )
    elif java_type_name.startswith("Map<"):
        if nullable:
            lines.append(f"{java_name} = ImmutableCollections.nullableMap({java_name});")
        elif required:
            lines.append(
                f"{java_name} = ImmutableCollections.requiredMap({quote(source_name)}, {java_name});"
            )
        else:
            lines.append(f"{java_name} = ImmutableCollections.mapOrEmpty({java_name});")
    elif java_type_name == "ObjectNode":
        if required:
            lines.append(
                f"{java_name} = ContractValues.requiredObject({quote(source_name)}, {java_name});"
            )
        elif not nullable:
            lines.append(f"{java_name} = ContractValues.objectOrEmpty({java_name});")
        else:
            lines.append(f"{java_name} = ContractValues.copyObject({java_name});")
    elif java_type_name == "JsonNode":
        if required:
            lines.append(
                f"{java_name} = ContractValues.required({quote(source_name)}, {java_name});"
            )
        lines.append(f"{java_name} = ContractValues.copyJson({java_name});")
    else:
        if required:
            lines.append(
                f"{java_name} = ContractValues.required({quote(source_name)}, {java_name});"
            )

    if java_type_name in {"Integer", "Double"}:
        minimum = base_schema.get("minimum")
        maximum = base_schema.get("maximum")
        exclusive_minimum = base_schema.get("exclusiveMinimum")
        exclusive_maximum = base_schema.get("exclusiveMaximum")
        if minimum is not None:
            lines.append(
                f"ContractValues.minimum({quote(source_name)}, {java_name}, {minimum});"
            )
        if maximum is not None:
            lines.append(
                f"ContractValues.maximum({quote(source_name)}, {java_name}, {maximum});"
            )
        if exclusive_minimum is not None:
            lines.append(
                f"ContractValues.exclusiveMinimum({quote(source_name)}, {java_name}, {exclusive_minimum});"
            )
        if exclusive_maximum is not None:
            lines.append(
                f"ContractValues.exclusiveMaximum({quote(source_name)}, {java_name}, {exclusive_maximum});"
            )

    constant = base_schema.get("const")
    if constant is not None:
        if isinstance(constant, bool):
            expected = str(constant).lower()
            lines.append(
                f"ContractValues.constant({quote(source_name)}, {java_name}, {expected});"
            )
        elif isinstance(constant, str):
            lines.append(
                f"ContractValues.constant({quote(source_name)}, {java_name}, {quote(constant)});"
            )
    return lines


def generate_enum(name: str, members: list[dict[str, str]]) -> str:
    constants = ",\n".join(
        f"  {item['name']}({quote(item['value'])})" for item in members
    )
    return f"""package {PACKAGE};

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum {name} {{
{constants};

  private final String value;

  {name}(String value) {{
    this.value = value;
  }}

  @JsonValue
  public String value() {{
    return value;
  }}

  @JsonCreator
  public static {name} fromValue(String value) {{
    for ({name} candidate : values()) {{
      if (candidate.value.equals(value)) {{
        return candidate;
      }}
    }}
    throw new ContractValidationException("unknown {name} value: " + value);
  }}
}}
"""


def generate_record(
    name: str,
    schema: dict[str, Any],
    fields: dict[str, PythonField],
) -> str:
    properties: dict[str, dict[str, Any]] = schema.get("properties", {})
    required_names = set(schema.get("required", []))
    components: list[str] = []
    validations: list[str] = []
    constructor_args: list[str] = []
    accessors: list[str] = []

    for source_name, property_schema in properties.items():
        python_field = fields[source_name]
        java_name = camel(source_name)
        java_type_name, nullable = java_type(property_schema)
        required = source_name in required_names or python_field.required
        default = java_default(python_field, java_type_name, property_schema)
        annotations: list[str] = []
        if python_field.excluded:
            annotations.append("@JsonIgnore")
        else:
            required_suffix = ", required = true" if required else ""
            annotations.append(
                f"@JsonProperty(value = {quote(source_name)}{required_suffix})"
            )
            if not nullable and java_type_name != "JsonNode":
                annotations.append("@ContractNonNull")
        annotation_text = " ".join(annotations)
        components.append(f"    {annotation_text} {java_type_name} {java_name}")
        constructor_args.append("null")
        if java_type_name in {"List<JsonNode>", "List<ObjectNode>"}:
            accessor_value = f"ImmutableCollections.copyJsonList({java_name})"
        elif java_type_name == "Map<String, JsonNode>":
            accessor_value = f"ImmutableCollections.copyJsonMap({java_name})"
        elif java_type_name == "Map<String, List<String>>":
            accessor_value = f"ImmutableCollections.copyStringListMap({java_name})"
        elif java_type_name.startswith("List<"):
            accessor_value = f"List.copyOf({java_name})"
        elif java_type_name.startswith("Map<"):
            accessor_value = f"Map.copyOf({java_name})"
        elif java_type_name in {"ObjectNode", "JsonNode"}:
            accessor_value = f"{java_name}.deepCopy()"
        else:
            accessor_value = ""
        if accessor_value:
            accessors.append(
                f"  public {java_type_name} {java_name}() {{\n"
                f"    return {java_name} == null ? null : {accessor_value};\n"
                "  }\n"
            )
        validations.extend(
            normalization_lines(
                source_name,
                java_name,
                java_type_name,
                property_schema,
                required,
                default,
            )
        )

    component_text = ",\n".join(components)
    body_lines = "\n".join(f"    {line}" for line in validations)
    all_default = not required_names and all(not field.required for field in fields.values())
    no_arg = ""
    if all_default:
        no_arg = (
            f"\n  public {name}() {{\n"
            f"    this({', '.join(constructor_args)});\n"
            "  }\n"
        )
    accessor_text = "\n" + "\n".join(accessors) if accessors else ""

    return f"""package {PACKAGE};

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.List;
import java.util.Map;

public record {name}(
{component_text}
) implements StrictContract {{

  public {name} {{
{body_lines}
  }}
{no_arg}{accessor_text}}}
"""


def main() -> None:
    root = project_root()
    source = read_authoritative_source(root)
    fields_by_class = python_fields(source)
    baseline = root / "migration" / "baseline"
    enum_rows = json.loads((baseline / "enum-literals.json").read_text(encoding="utf-8"))
    schema_index = json.loads(
        (baseline / "schemas" / "index.json").read_text(encoding="utf-8")
    )
    output = (
        root
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
    output.mkdir(parents=True, exist_ok=True)

    generated_names: set[str] = set()
    enum_count = 0
    for row in enum_rows:
        qualified_name = row.get("qualified_name", "")
        if not qualified_name.startswith("mathproofmesh.schemas."):
            continue
        name = qualified_name.rsplit(".", 1)[-1]
        (output / f"{name}.java").write_text(
            generate_enum(name, row["members"]), encoding="utf-8", newline="\n"
        )
        generated_names.add(name)
        enum_count += 1

    model_count = 0
    for row in schema_index:
        qualified_name = row.get("qualified_name", "")
        if not qualified_name.startswith("mathproofmesh.schemas."):
            continue
        name = qualified_name.rsplit(".", 1)[-1]
        if name == "StrictModel":
            continue
        payload = json.loads(
            (baseline / "schemas" / row["file"]).read_text(encoding="utf-8")
        )
        schema = payload["schema"]
        schema_fields = fields_by_class[name]
        if set(schema.get("properties", {})) != set(schema_fields):
            raise RuntimeError(f"field inventory mismatch for {name}")
        (output / f"{name}.java").write_text(
            generate_record(name, schema, schema_fields),
            encoding="utf-8",
            newline="\n",
        )
        generated_names.add(name)
        model_count += 1

    inventory = {
        "authoritative_zip_sha256": EXPECTED_ZIP_SHA256,
        "enums": enum_count,
        "models": model_count,
        "total_contract_types": enum_count + model_count,
        "types": sorted(generated_names),
    }
    inventory_path = root / "migration" / "phase-02-contract-inventory.json"
    inventory_path.write_text(
        json.dumps(inventory, indent=2, sort_keys=True) + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        f"generated {enum_count} enums and {model_count} records "
        f"from authoritative ZIP"
    )


if __name__ == "__main__":
    main()
