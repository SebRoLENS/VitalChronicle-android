#!/usr/bin/env python3
"""Regression checks for Android accelerator discovery and safe fallback policy."""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSET = ROOT / "app/src/main/assets/accelerator_catalog.json"
REGISTRY = ROOT / "app/src/main/java/io/github/sebrolens/vitalchronicle/android/AccelerationBackends.kt"
MODEL_CARD = ROOT / "app/src/main/java/io/github/sebrolens/vitalchronicle/android/OllamaModelCard.kt"
NATIVE_BUILD = ROOT / "llama-android/build.gradle.kts"
NATIVE_ADAPTER = ROOT / "llama-android/src/main/cpp/vital_ai_chat.cpp"
APP_BUILD = ROOT / "app/build.gradle.kts"
ANDROID_WORKFLOW = ROOT / ".github/workflows/android.yml"


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AssertionError(message)


def main() -> int:
    catalog = json.loads(ASSET.read_text(encoding="utf-8"))
    require(catalog.get("schemaVersion") == 1, "accelerator catalog schema must be version 1")
    drivers = catalog.get("drivers")
    require(isinstance(drivers, list) and drivers, "accelerator catalog must contain drivers")

    by_id = {item["id"]: item for item in drivers}
    require(len(by_id) == len(drivers), "accelerator driver ids must be unique")
    for required in (
        "hexagon-gguf",
        "vulkan-generic",
        "aicore-gemini-nano",
        "litert-compiled-model",
        "cpu-kleidiai",
    ):
        require(required in by_id, f"missing accelerator driver {required}")

    vulkan = by_id["vulkan-generic"]
    require(vulkan["kind"] == "GPU", "Vulkan must be classified as GPU")
    require(vulkan["scope"] == "GENERIC", "Vulkan must be the generic GGUF accelerator")
    require(vulkan["runtime"] == "GGUF" and vulkan["backend"] == "VULKAN", "Vulkan GGUF backend mismatch")
    require(vulkan.get("requiresPackagedBackend") is True, "Vulkan availability must require native APK backend")

    hexagon = by_id["hexagon-gguf"]
    require(hexagon["kind"] == "NPU" and hexagon["scope"] == "SPECIFIC", "Hexagon must be device-specific NPU")
    require(hexagon.get("requiresPackagedBackend") is True, "specific NPU must not be advertised without native backend")

    cpu = by_id["cpu-kleidiai"]
    require(cpu["kind"] == "CPU" and cpu["runtime"] == "GGUF", "CPU fallback definition mismatch")
    require(cpu.get("requiresPackagedBackend") is False, "portable CPU fallback must remain available")

    # A remote catalog is metadata only. New executable backends always arrive
    # through VitalChronicle's signed APK updater, never from this JSON.
    forbidden_fields = {"downloadUrl", "libraryUrl", "binaryUrl", "apkUrl", "sha256"}
    for item in drivers:
        require(not forbidden_fields.intersection(item), f"driver {item['id']} contains executable-download metadata")

    registry = REGISTRY.read_text(encoding="utf-8")
    require("REMOTE_CATALOG_URL" in registry and "accelerator_catalog.json" in registry, "remote driver refresh is missing")
    require("requiresPackagedBackend" in registry, "runtime must verify that native backend is packaged")
    require("requiresAppUpdate" in registry, "future native drivers must surface app-update requirement")
    require("Reserved for compatible LiteRT models" in registry, "LiteRT must not be misrepresented as a GGUF runtime")

    native_build = NATIVE_BUILD.read_text(encoding="utf-8")
    require('"-DGGML_BACKEND_DL=ON"' in native_build, "dynamic ggml backend loading must stay enabled")
    require('"-DGGML_VULKAN=ON"' in native_build, "Vulkan GGUF backend must be built")

    adapter = NATIVE_ADAPTER.read_text(encoding="utf-8")
    require("params.n_gpu_layers = 999" in adapter, "llama.cpp must request accelerator offload")
    require("retrying CPU-only backend" in adapter and "params.n_gpu_layers = 0" in adapter, "accelerator failure must retry CPU")
    require("ggml_backend_dev_count" in adapter and "std::stable_sort" in adapter, "runtime must rank actual loaded accelerator devices")
    require('"hexagon"' in adapter and '"htp"' in adapter and '"npu"' in adapter and '"tpu"' in adapter, "specific NPU/TPU backends must rank first")
    require('"opencl"' in adapter and '"adreno"' in adapter, "specific GPU backends must be recognized")
    require('"vulkan"' in adapter and "return 200" in adapter, "generic Vulkan must remain the fallback accelerator")

    model_card = MODEL_CARD.read_text(encoding="utf-8")
    require("Acceleration drivers" in model_card, "AI model cards must expose driver availability")
    require("availabilityLabel" in model_card and "requiresAppUpdate" in model_card, "driver state labels are missing from model cards")

    app_build = APP_BUILD.read_text(encoding="utf-8")
    require('versionName = "0.5.0"' in app_build, "feature release must be Android 0.5.0")

    workflow = ANDROID_WORKFLOW.read_text(encoding="utf-8")
    require("libggml-vulkan.so" in workflow, "CI must verify Vulkan is physically packaged in the APK")
    require("test_accelerator_catalog.py" in workflow, "CI must execute accelerator regression test")

    print(f"accelerator catalog validation passed: {len(drivers)} drivers")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
