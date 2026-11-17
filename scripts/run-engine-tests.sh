#!/usr/bin/env bash
# کامپایل و اجرای تست‌های ماژول‌های خالص (engine و canvas) بدون Gradle،
# با کامپایلر Kotlin که داخل توزیع Gradle هست.
# استفاده: scripts/run-engine-tests.sh [مسیر lib گریدل]
set -euo pipefail
GL="${1:-/opt/gradle-8.14.3/lib}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
CP="$(ls "$GL"/kotlin-compiler-embeddable-*.jar "$GL"/kotlin-stdlib-*.jar "$GL"/kotlin-reflect-*.jar \
      "$GL"/kotlin-script-runtime-*.jar "$GL"/kotlin-daemon-embeddable-*.jar "$GL"/trove4j-*.jar \
      "$GL"/kotlinx-coroutines-core-jvm-*.jar "$GL"/annotations-*.jar 2>/dev/null | tr '\n' ':')"
STD="$(ls "$GL"/kotlin-stdlib-*.jar | head -1)"

run_module() {  # $1=module  $2=main class  $3..=ماژول‌های وابسته (سورس آن‌ها هم کامپایل می‌شود)
  local m="$1" main="$2"; shift 2
  local out="${TMPDIR:-/tmp}/indsim-out-$m"
  rm -rf "$out"; mkdir -p "$out"
  echo "== $m =="
  local srcs
  srcs="$(find "$ROOT/$m/src/main" "$ROOT/$m/src/test" -name '*.kt')"
  for dep in "$@"; do srcs="$srcs $(find "$ROOT/$dep/src/main" -name '*.kt')"; done
  java -cp "$CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler -no-stdlib -no-reflect \
    -cp "$STD" -d "$out" $srcs
  java -cp "$out:$STD" "$main"
}

run_module engine com.indsim.engine.EngineTestsKt
run_module canvas com.indsim.canvas.CanvasTestsKt
run_module editor com.indsim.editor.EditorTestsKt engine
