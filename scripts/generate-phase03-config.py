#!/usr/bin/env python3
"""Generate the Phase 03 Java configuration records from the frozen Python source.

This generator deliberately reads only the extracted authoritative snapshot.  It
turns Pydantic field metadata into typed Java records, constructor validation,
defaults, and an auditable constraint catalog.  Cross-field validators remain
hand-written in ConfigInvariants.java.
"""

from __future__ import annotations

import inspect
import json
import sys
import types
import typing
from enum import Enum
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parents[1]
SOURCE_ROOT = ROOT / ".work" / "source" / "src"
OUTPUT_PATH = (
    ROOT
    / "mathproofmesh-server"
    / "src"
    / "main"
    / "java"
    / "io"
    / "github"
    / "aililuola"
    / "mathproofmesh"
    / "config"
)
OUTPUT = (
    Path("\\\\?\\" + str(OUTPUT_PATH))
    if sys.platform == "win32"
    else OUTPUT_PATH
)

sys.path.insert(0, str(SOURCE_ROOT))

from pydantic import SecretStr  # noqa: E402
from mathproofmesh import config as source  # noqa: E402
from mathproofmesh.schemas import ComputationPurpose  # noqa: E402


EXPECTED_CLASSES = [
    "PricingConfig",
    "AgentConfig",
    "BudgetConfig",
    "SchedulerConfig",
    "TypedCommunicationConfig",
    "RouteTeamConfig",
    "CrossRouteConfig",
    "BrokerConfig",
    "ProofGraphConfig",
    "TypedMemoryConfig",
    "InspirationConfig",
    "ValidationEscalationConfig",
    "AgentCapabilityConfig",
    "FinalTopologyConfig",
    "GoalAlignmentControlConfig",
    "ScopeGuardControlConfig",
    "CoreDebtControlConfig",
    "RealizerControlConfig",
    "InductionControlConfig",
    "FailureControlConfig",
    "BottleneckControlConfig",
    "CommonModeControlConfig",
    "MessageUtilityControlConfig",
    "NearMissControlConfig",
    "FalsificationFastLaneControlConfig",
    "BlueprintReviewControlConfig",
    "RouteAdmissionControlConfig",
    "ContinueGateControlConfig",
    "SynthesisReadinessControlConfig",
    "ProofControlConfig",
    "TopologyConfig",
    "VerificationConfig",
    "ContinuationConfig",
    "ExplorationTierPolicyConfig",
    "DeepExplorationPolicyConfig",
    "ComputationConfig",
    "RuntimeConfig",
    "SystemConfig",
]

CUSTOM_VALIDATORS = {
    "AgentConfig",
    "BudgetConfig",
    "SchedulerConfig",
    "BrokerConfig",
    "InspirationConfig",
    "AgentCapabilityConfig",
    "TopologyConfig",
    "VerificationConfig",
    "ExplorationTierPolicyConfig",
    "DeepExplorationPolicyConfig",
    "ComputationConfig",
    "RuntimeConfig",
    "SystemConfig",
}


def camel(name: str) -> str:
    first, *rest = name.split("_")
    return first + "".join(item[:1].upper() + item[1:] for item in rest)


def optional_inner(annotation: Any) -> tuple[Any, bool]:
    origin = typing.get_origin(annotation)
    if origin in (typing.Union, types.UnionType):
        args = typing.get_args(annotation)
        without_none = tuple(item for item in args if item is not type(None))
        if len(without_none) + 1 == len(args) and len(without_none) == 1:
            return without_none[0], True
    return annotation, False


def literal_values(annotation: Any) -> tuple[Any, ...]:
    inner, _ = optional_inner(annotation)
    if typing.get_origin(inner) is typing.Literal:
        return typing.get_args(inner)
    return ()


def java_type(annotation: Any) -> str:
    annotation, _ = optional_inner(annotation)
    origin = typing.get_origin(annotation)
    if origin is typing.Literal:
        values = typing.get_args(annotation)
        if all(isinstance(value, bool) for value in values):
            return "Boolean"
        if all(isinstance(value, int) for value in values):
            return "Integer"
        return "String"
    if origin is list:
        return f"List<{java_type(typing.get_args(annotation)[0])}>"
    if origin is dict:
        key, value = typing.get_args(annotation)
        return f"Map<{java_type(key)}, {java_type(value)}>"
    if annotation is str:
        return "String"
    if annotation is int:
        return "Integer"
    if annotation is float:
        return "Double"
    if annotation is bool:
        return "Boolean"
    if annotation is SecretStr:
        return "SecretValue"
    if annotation is ComputationPurpose:
        return "ComputationPurpose"
    if inspect.isclass(annotation) and issubclass(annotation, source.ConfigModel):
        return annotation.__name__
    raise TypeError(f"unsupported annotation: {annotation!r}")


def java_string(value: str) -> str:
    return json.dumps(value, ensure_ascii=True)


def render_value(value: Any, annotation: Any | None = None) -> str:
    if value is None:
        return "null"
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        rendered = repr(value)
        if "." not in rendered and "e" not in rendered.lower():
            rendered += ".0"
        return rendered + "d"
    if isinstance(value, Enum):
        return f"ComputationPurpose.{value.name}"
    if isinstance(value, str):
        return java_string(value)
    if isinstance(value, source.ConfigModel):
        cls = type(value)
        arguments = [
            render_value(getattr(value, name), field.annotation)
            for name, field in cls.model_fields.items()
        ]
        return f"new {cls.__name__}({', '.join(arguments)})"
    if isinstance(value, list):
        if not value:
            return "List.of()"
        item_annotation = None
        if annotation is not None:
            inner, _ = optional_inner(annotation)
            if typing.get_origin(inner) is list:
                item_annotation = typing.get_args(inner)[0]
        return "List.of(" + ", ".join(
            render_value(item, item_annotation) for item in value
        ) + ")"
    if isinstance(value, dict):
        if not value:
            return "Map.of()"
        entries = ", ".join(
            f"Map.entry({java_string(str(key))}, {render_value(item)})"
            for key, item in value.items()
        )
        return f"Map.ofEntries({entries})"
    raise TypeError(f"unsupported default: {value!r}")


def metadata_value(metadata: list[Any], attribute: str) -> Any | None:
    for item in metadata:
        value = getattr(item, attribute, None)
        if value is not None:
            return value
    return None


def constraints_for(class_name: str, field_name: str, field: Any) -> list[dict[str, Any]]:
    constraints: list[dict[str, Any]] = []
    for attribute, kind in (
        ("ge", "minimum"),
        ("gt", "exclusive_minimum"),
        ("le", "maximum"),
        ("min_length", "minimum_length"),
        ("max_length", "maximum_length"),
    ):
        value = metadata_value(field.metadata, attribute)
        if value is not None:
            constraints.append(
                {
                    "record": class_name,
                    "field": field_name,
                    "kind": kind,
                    "value": value,
                }
            )
    values = literal_values(field.annotation)
    if values:
        constraints.append(
            {
                "record": class_name,
                "field": field_name,
                "kind": "one_of",
                "value": list(values),
            }
        )
    inner, _ = optional_inner(field.annotation)
    if typing.get_origin(inner) is list:
        item_values = literal_values(typing.get_args(inner)[0])
        if item_values:
            constraints.append(
                {
                    "record": class_name,
                    "field": field_name,
                    "kind": "items_one_of",
                    "value": list(item_values),
                }
            )
    if typing.get_origin(inner) is dict:
        value_values = literal_values(typing.get_args(inner)[1])
        if value_values:
            constraints.append(
                {
                    "record": class_name,
                    "field": field_name,
                    "kind": "map_values_one_of",
                    "value": list(value_values),
                }
            )
    return constraints


def component_line(field_name: str, field: Any) -> str:
    annotation, nullable = optional_inner(field.annotation)
    del annotation
    arguments = [f'value = "{field_name}"']
    if field.is_required():
        arguments.append("required = true")
    if field_name == "api_key":
        arguments.append("access = JsonProperty.Access.WRITE_ONLY")
    annotations = f"@JsonProperty({', '.join(arguments)})"
    if nullable:
        annotations += " @ConfigNullable"
    return f"    {annotations} {java_type(field.annotation)} {camel(field_name)}"


def default_expression(field: Any) -> str | None:
    if field.is_required():
        return None
    default = field.get_default(call_default_factory=True)
    if default is None:
        return None
    if isinstance(default, source.ConfigModel):
        return f"{type(default).__name__}.defaults()"
    return render_value(default, field.annotation)


def normalization_lines(field_name: str, field: Any) -> list[str]:
    variable = camel(field_name)
    annotation, _ = optional_inner(field.annotation)
    origin = typing.get_origin(annotation)
    lines: list[str] = []
    default = default_expression(field)
    if default is not None:
        lines.extend(
            [
                f"    if ({variable} == null) {{",
                f"      {variable} = {default};",
                "    }",
            ]
        )
    if field.is_required():
        lines.append(
            f'    {variable} = ConfigValidation.required("{field_name}", {variable});'
        )
    if java_type(field.annotation) == "String":
        lines.append(f"    {variable} = ConfigValidation.trim({variable});")
    elif origin is list:
        item = typing.get_args(annotation)[0]
        item_inner, _ = optional_inner(item)
        if item_inner is str or typing.get_origin(item_inner) is typing.Literal:
            lines.append(
                f'    {variable} = ConfigValidation.trimStrings("{field_name}", {variable});'
            )
        else:
            lines.append(
                f'    {variable} = ConfigValidation.immutableList("{field_name}", {variable});'
            )
    elif origin is dict:
        key, value = typing.get_args(annotation)
        value_inner, _ = optional_inner(value)
        if key is str and (
            value_inner is str or typing.get_origin(value_inner) is typing.Literal
        ):
            lines.append(
                f'    {variable} = ConfigValidation.trimStringMap("{field_name}", {variable});'
            )
        else:
            lines.append(
                f'    {variable} = ConfigValidation.immutableMap("{field_name}", {variable});'
            )
    if (
        field_name in {
            "provider_terminal_http_statuses",
            "provider_shared_auth_http_statuses",
        }
    ):
        lines.append(
            f'    {variable} = ConfigValidation.sortedDistinct("{field_name}", {variable});'
        )

    ge = metadata_value(field.metadata, "ge")
    gt = metadata_value(field.metadata, "gt")
    le = metadata_value(field.metadata, "le")
    min_length = metadata_value(field.metadata, "min_length")
    max_length = metadata_value(field.metadata, "max_length")
    if ge is not None:
        lines.append(
            f'    ConfigValidation.minimum("{field_name}", {variable}, {render_value(ge)});'
        )
    if gt is not None:
        lines.append(
            f'    ConfigValidation.exclusiveMinimum("{field_name}", {variable}, '
            f"{render_value(gt)});"
        )
    if le is not None:
        lines.append(
            f'    ConfigValidation.maximum("{field_name}", {variable}, {render_value(le)});'
        )
    if min_length is not None:
        lines.append(
            f'    ConfigValidation.minimumLength("{field_name}", {variable}, {min_length});'
        )
    if max_length is not None:
        lines.append(
            f'    ConfigValidation.maximumLength("{field_name}", {variable}, {max_length});'
        )

    values = literal_values(field.annotation)
    if values:
        rendered = ", ".join(render_value(value) for value in values)
        lines.append(
            f'    ConfigValidation.oneOf("{field_name}", {variable}, {rendered});'
        )
    if origin is list:
        item_values = literal_values(typing.get_args(annotation)[0])
        if item_values:
            rendered = ", ".join(render_value(value) for value in item_values)
            lines.append(
                f'    ConfigValidation.itemsOneOf("{field_name}", {variable}, {rendered});'
            )
    if origin is dict:
        value_values = literal_values(typing.get_args(annotation)[1])
        if value_values:
            rendered = ", ".join(render_value(value) for value in value_values)
            lines.append(
                f'    ConfigValidation.mapValuesOneOf("{field_name}", {variable}, '
                f"{rendered});"
            )
    return lines


def extra_methods(class_name: str) -> str:
    if class_name == "AgentConfig":
        return """

  public SecretValue resolveKey() {
    return resolveKey(System::getenv);
  }

  public SecretValue resolveKey(EnvironmentLookup environment) {
    java.util.Objects.requireNonNull(environment, "environment");
    if (apiKey != null) {
      return apiKey.copy();
    }
    if (apiKeyEnv != null && !apiKeyEnv.isBlank()) {
      String value = environment.lookup(apiKeyEnv);
      if (value != null && !value.isBlank()) {
        return SecretValue.of(value);
      }
      throw new ConfigValidationException(
          "missing API key environment variable for agent '" + id + "': " + apiKeyEnv);
    }
    if ("mock".equals(provider)) {
      return SecretValue.of("mock");
    }
    throw new ConfigValidationException("no API key configured for agent '" + id + "'");
  }
"""
    if class_name == "DeepExplorationPolicyConfig":
        return """

  public ExplorationTierPolicyConfig tierForLimit(Integer outputTokenLimit) {
    int requested = outputTokenLimit == null ? tiers.getFirst().outputTokens() : outputTokenLimit;
    ExplorationTierPolicyConfig selected = tiers.getFirst();
    for (ExplorationTierPolicyConfig tier : tiers) {
      if (tier.outputTokens() <= requested) {
        selected = tier;
      }
    }
    return selected;
  }

  public int tierIndexForLimit(Integer outputTokenLimit) {
    return tiers.indexOf(tierForLimit(outputTokenLimit));
  }
"""
    if class_name == "SystemConfig":
        return """

  public com.fasterxml.jackson.databind.node.ObjectNode redactedTree(
      com.fasterxml.jackson.databind.ObjectMapper mapper) {
    com.fasterxml.jackson.databind.node.ObjectNode root = mapper.valueToTree(this);
    com.fasterxml.jackson.databind.node.ArrayNode redactedAgents =
        (com.fasterxml.jackson.databind.node.ArrayNode) root.path("agents");
    for (int index = 0; index < redactedAgents.size(); index++) {
      com.fasterxml.jackson.databind.node.ObjectNode agent =
          (com.fasterxml.jackson.databind.node.ObjectNode) redactedAgents.get(index);
      String status = agent.path("api_key_env").isTextual()
          ? "configured-via-env"
          : "inline-secret-redacted";
      agent.put("key_status", status);
      agent.remove("api_key");
    }
    return root;
  }
"""
    return ""


def defensive_accessors(cls: type[source.ConfigModel]) -> str:
    methods: list[str] = []
    for field_name, field in cls.model_fields.items():
        annotation, _ = optional_inner(field.annotation)
        origin = typing.get_origin(annotation)
        if origin not in (list, dict):
            continue
        variable = camel(field_name)
        copy_method = "List.copyOf" if origin is list else "Map.copyOf"
        methods.append(
            f'  @JsonProperty("{field_name}")\n'
            f"  @Override\n"
            f"  public {java_type(field.annotation)} {variable}() {{\n"
            f"    return {variable} == null ? null : {copy_method}({variable});\n"
            f"  }}"
        )
    if not methods:
        return ""
    return "\n\n" + "\n\n".join(methods) + "\n"


def generate_record(cls: type[source.ConfigModel]) -> str:
    class_name = cls.__name__
    fields = list(cls.model_fields.items())
    components = ",\n".join(
        component_line(field_name, field) for field_name, field in fields
    )
    parameters = ", ".join(
        f"{java_type(field.annotation)} {camel(field_name)}"
        for field_name, field in fields
    )
    body: list[str] = []
    for field_name, field in fields:
        body.extend(normalization_lines(field_name, field))
    for field_name, _ in fields:
        variable = camel(field_name)
        body.append(f"    this.{variable} = {variable};")
    if class_name in CUSTOM_VALIDATORS:
        body.append("    ConfigInvariants.validate(this);")

    has_required = any(field.is_required() for _, field in fields)
    defaults = ""
    if not has_required:
        nulls = ", ".join("null" for _ in fields)
        defaults = (
            f"\n\n  public static {class_name} defaults() {{\n"
            f"    return new {class_name}({nulls});\n"
            "  }"
        )

    return f"""package io.github.aililuola.mathproofmesh.config;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.github.aililuola.mathproofmesh.contract.ComputationPurpose;
import java.util.List;
import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = false)
public record {class_name}(
{components}
) implements ConfigModel {{

  @JsonCreator
  public {class_name}({parameters}) {{
{chr(10).join(body)}
  }}{defaults}{defensive_accessors(cls)}{extra_methods(class_name)}
}}
"""


def generate_catalog(constraints: list[dict[str, Any]]) -> str:
    rows: list[str] = []
    for item in constraints:
        value = item["value"]
        if isinstance(value, list):
            rendered = "List.of(" + ", ".join(
                render_value(entry) for entry in value
            ) + ")"
        else:
            rendered = f"List.of({render_value(value)})"
        rows.append(
            "        new ConfigFieldConstraint("
            f'{item["record"]}.class, "{item["field"]}", '
            f'ConfigFieldConstraint.Kind.{item["kind"].upper()}, {rendered})'
        )
    joined = ",\n".join(rows)
    return f"""package io.github.aililuola.mathproofmesh.config;

import java.util.List;

public final class ConfigConstraintCatalog {{
  private ConfigConstraintCatalog() {{}}

  public static List<ConfigFieldConstraint> constraints() {{
    return List.of(
{joined}
    );
  }}
}}
"""


def main() -> None:
    classes = [
        value
        for value in vars(source).values()
        if inspect.isclass(value)
        and value.__module__ == source.__name__
        and issubclass(value, source.ConfigModel)
        and value is not source.ConfigModel
    ]
    names = [cls.__name__ for cls in classes]
    if names != EXPECTED_CLASSES:
        raise RuntimeError(f"authoritative config inventory changed: {names!r}")

    OUTPUT.mkdir(parents=True, exist_ok=True)
    constraints: list[dict[str, Any]] = []
    inventory: list[dict[str, Any]] = []
    for cls in classes:
        path = OUTPUT / f"{cls.__name__}.java"
        path.write_text(generate_record(cls), encoding="utf-8", newline="\n")
        for field_name, field in cls.model_fields.items():
            field_constraints = constraints_for(cls.__name__, field_name, field)
            constraints.extend(field_constraints)
            _, nullable = optional_inner(field.annotation)
            inventory.append(
                {
                    "record": cls.__name__,
                    "field": field_name,
                    "java_field": camel(field_name),
                    "java_type": java_type(field.annotation),
                    "required": field.is_required(),
                    "nullable": nullable,
                    "constraints": field_constraints,
                }
            )

    (OUTPUT / "ConfigConstraintCatalog.java").write_text(
        generate_catalog(constraints), encoding="utf-8", newline="\n"
    )
    report_dir = ROOT / "migration" / "reports"
    report_dir.mkdir(parents=True, exist_ok=True)
    (report_dir / "phase-03-config-field-inventory.json").write_text(
        json.dumps(
            {
                "authoritative_source": "src/mathproofmesh/config.py",
                "record_count": len(classes),
                "field_count": len(inventory),
                "constraint_count": len(constraints),
                "fields": inventory,
            },
            ensure_ascii=True,
            indent=2,
            sort_keys=True,
        )
        + "\n",
        encoding="utf-8",
        newline="\n",
    )
    print(
        f"generated {len(classes)} records, {len(inventory)} fields, "
        f"{len(constraints)} constraints"
    )


if __name__ == "__main__":
    main()
