#!/usr/bin/env sh
set -eu

ROOT=$(CDPATH= cd -- "$(dirname -- "$0")/.." && pwd)
OUTPUT="$ROOT/target/release"
BUNDLE="$OUTPUT/JavaMathProofMesh-0.8.0"

"$ROOT/mvnw" -B -ntp -o -pl :mathproofmesh-server -am package -DskipTests
case "$OUTPUT" in
  "$ROOT"/target/*) ;;
  *) echo "release output must remain below target" >&2; exit 1 ;;
esac
rm -rf -- "$OUTPUT"
mkdir -p \
  "$BUNDLE/bin" "$BUNDLE/lib" "$BUNDLE/sidecar" "$BUNDLE/db" \
  "$BUNDLE/compose" "$BUNDLE/config" "$BUNDLE/docs" "$BUNDLE/reports" \
  "$BUNDLE/desktop" "$BUNDLE/examples"

cp "$ROOT/target/modules/mathproofmesh-server/mathproofmesh-server-0.8.0-exec.jar" "$BUNDLE/lib/"
cp "$ROOT/target/modules/mathproofmesh-server/mathproofmesh-server-0.8.0-cli.jar" "$BUNDLE/lib/"
cp "$ROOT"/python-compute-service/*.py "$BUNDLE/sidecar/"
cp "$ROOT/python-compute-service/pyproject.toml" \
  "$ROOT/python-compute-service/requirements.in" \
  "$ROOT/python-compute-service/requirements.lock" \
  "$ROOT/python-compute-service/build-requirements.lock" \
  "$ROOT/python-compute-service/README.md" "$BUNDLE/sidecar/"
cp "$ROOT"/mathproofmesh-server/src/main/resources/db/migration/*.sql "$BUNDLE/db/"
cp "$ROOT/compose.yaml" "$ROOT/compose/temporal-dev.yaml" \
  "$ROOT/migration/image-lock.env" "$BUNDLE/compose/"
cp -R "$ROOT/config/." "$BUNDLE/config/"
cp -R "$ROOT/docs/." "$BUNDLE/docs/"
cp -R "$ROOT/migration/reports/." "$BUNDLE/reports/"
cp -R "$ROOT/examples/." "$BUNDLE/examples/"
cp "$ROOT/README.md" "$ROOT/LICENSE" "$ROOT/NOTICE" \
  "$ROOT/.env.local.example" "$ROOT/PYTHON_SOURCE_MIGRATION_MAP.csv" \
  "$ROOT/PYTHON_TEST_MIGRATION_MAP.csv" "$ROOT/OPS_CONFIG_DOC_MIGRATION_MAP.csv" \
  "$ROOT/SOURCE_SNAPSHOT_SHA256SUMS.txt" "$BUNDLE/"
if [ -f "$ROOT/MIGRATION_COMPLETION_REPORT.md" ]; then
  cp "$ROOT/MIGRATION_COMPLETION_REPORT.md" "$BUNDLE/"
fi

cat >"$BUNDLE/bin/mathproofmesh" <<'EOF'
#!/usr/bin/env sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$HERE/../lib/mathproofmesh-server-0.8.0-cli.jar" "$@"
EOF
cat >"$BUNDLE/bin/mathproofmesh-server" <<'EOF'
#!/usr/bin/env sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec "${JAVA_HOME:+$JAVA_HOME/bin/}java" -jar "$HERE/../lib/mathproofmesh-server-0.8.0-exec.jar" --spring.config.additional-location="optional:file:$HERE/../config/application.yaml" "$@"
EOF
cat >"$BUNDLE/bin/mathproofmesh-sidecar" <<'EOF'
#!/usr/bin/env sh
set -eu
HERE=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
exec python3 "$HERE/../sidecar/service.py"
EOF
chmod 0755 "$BUNDLE/bin/mathproofmesh" "$BUNDLE/bin/mathproofmesh-server" \
  "$BUNDLE/bin/mathproofmesh-sidecar"

cat >"$BUNDLE/release-manifest.json" <<'EOF'
{
  "schema_version": "1.0",
  "product": "JavaMathProofMesh",
  "version": "0.8.0",
  "java": "25",
  "server_jar": "lib/mathproofmesh-server-0.8.0-exec.jar",
  "cli_jar": "lib/mathproofmesh-server-0.8.0-cli.jar",
  "python_sidecar_lock": "sidecar/requirements.lock",
  "desktop_artifacts": []
}
EOF
(cd "$BUNDLE" && find . -type f ! -path './SHA256SUMS.txt' -print0 | sort -z |
  xargs -0 sha256sum | sed 's#  \./#  #' >SHA256SUMS.txt)
(cd "$OUTPUT" && jar --create --file JavaMathProofMesh-0.8.0.zip \
  -C "$OUTPUT" JavaMathProofMesh-0.8.0)
(cd "$OUTPUT" && sha256sum JavaMathProofMesh-0.8.0.zip >SHA256SUMS.txt)
echo "RELEASE PACKAGE: $OUTPUT/JavaMathProofMesh-0.8.0.zip"
