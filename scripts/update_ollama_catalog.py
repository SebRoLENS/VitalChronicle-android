#!/usr/bin/env python3
"""Build VitalChronicle's safe on-device Ollama catalog from the public Ollama library.

The generated JSON contains metadata only. VitalChronicle still downloads model blobs
straight from registry.ollama.ai and verifies the exact SHA-256 before loading them.
"""
from __future__ import annotations

import json
import re
import sys
import urllib.parse
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app/src/main/assets/ollama_catalog.json"
USER_AGENT = "VitalChronicle-OllamaCatalog/1.0 (+https://github.com/SebRoLENS/VitalChronicle-android)"
MAX_MODEL_BYTES = 24 * 1024**3
MIN_MODEL_BYTES = 50 * 1024**2

# Stable useful families are always checked. The newest Ollama page is also scanned,
# so future qwen4/gemma5/etc. families are picked up without changing the APK.
SEED_FAMILIES = [
    "qwen3",
    "qwen3.8",
    "gemma3",
    "gemma4",
    "llama3.2",
    "phi4-mini",
    "deepseek-r1",
    "mistral",
    "granite4.2",
    "smollm2",
]
PREFERRED_PREFIXES = (
    "qwen",
    "gemma",
    "llama",
    "phi",
    "mistral",
    "deepseek",
    "granite",
    "smollm",
)
EXCLUDED_FAMILY_BITS = (
    "embedding",
    "embed",
    "rerank",
    "ocr",
    "guardian",
)

# Curated size tags give the user useful choices even if Ollama changes its website
# markup. Unknown/new families always fall back to `latest`, so discovery remains
# dynamic rather than requiring an app update for every model generation.
KNOWN_TAGS: dict[str, list[str]] = {
    "qwen3": ["0.6b", "1.7b", "4b", "8b", "14b", "30b"],
    "qwen3.8": ["27b"],
    "gemma3": ["270m", "1b", "4b", "12b", "27b"],
    "gemma4": ["e2b", "e4b", "12b", "26b", "31b"],
    "llama3.2": ["1b", "3b"],
    "phi4-mini": ["3.8b"],
    "deepseek-r1": ["1.5b", "7b", "8b", "14b", "32b"],
    "mistral": ["7b"],
    "smollm2": ["135m", "360m", "1.7b"],
}

SAFE_COMPONENT = re.compile(r"^[A-Za-z0-9._-]+$")
SIMPLE_TAG = re.compile(
    r"^(?:latest|e?\d+(?:\.\d+)?[mb](?:-a\d+(?:\.\d+)?b)?|e?\d+(?:\.\d+)?[mb]-it-qat)$",
    re.IGNORECASE,
)
SIZE_TOKEN = re.compile(r"(?:^|[-_])((?:e)?\d+(?:\.\d+)?[mb])(?:$|[-_])", re.IGNORECASE)
EXCLUDED_TAG_BITS = (
    "cloud",
    "mlx",
    "bf16",
    "fp16",
    "f16",
    "q8_0",
    "q6_",
    "q5_",
    "q3_",
    "q2_",
    "mxfp",
    "nvfp",
)


def fetch_text(url: str, accept: str = "text/html,application/json") -> str:
    request = urllib.request.Request(
        url,
        headers={"User-Agent": USER_AGENT, "Accept": accept},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read().decode("utf-8")


def discover_families() -> tuple[list[str], dict[str, int]]:
    discovered: list[str] = []
    try:
        html = fetch_text("https://ollama.com/library?sort=newest")
        for family in re.findall(r'/library/([A-Za-z0-9._-]+)', html):
            family = urllib.parse.unquote(family)
            lower = family.lower()
            if not lower.startswith(PREFERRED_PREFIXES):
                continue
            if any(bit in lower for bit in EXCLUDED_FAMILY_BITS):
                continue
            if family not in discovered:
                discovered.append(family)
    except Exception as exc:
        print(f"warning: newest-family discovery failed: {exc}", file=sys.stderr)

    # Reserve room for the stable seed families instead of letting a busy newest
    # page push them out of the catalog entirely.
    combined: list[str] = []
    for family in discovered[:18] + SEED_FAMILIES:
        if SAFE_COMPONENT.fullmatch(family) and family not in combined:
            combined.append(family)

    # Higher rank means more recently surfaced by Ollama. Seed-only families still
    # get a positive rank so they remain useful fallbacks and recommendation options.
    rank = {family: max(20, 2000 - index * 50) for index, family in enumerate(discovered)}
    for index, family in enumerate(SEED_FAMILIES):
        rank.setdefault(family, max(10, 100 - index * 5))
    return combined[:32], rank


def discover_tags(family: str) -> tuple[list[str], bool]:
    tags = list(KNOWN_TAGS.get(family, []))
    lower_family = family.lower()
    supports_thinking = (
        lower_family.startswith("qwen3")
        or lower_family in {"gemma4", "deepseek-r1"}
        or "reasoning" in lower_family
    )
    family_q = urllib.parse.quote(family, safe="._-")

    # `latest` is the future-proof path: a newly released qwen4/gemma5/etc. can be
    # admitted as soon as it appears on Ollama's newest-model page, even if the tag
    # page has changed format. Curated size tags above provide richer choices for
    # established families.
    if "latest" not in tags:
        tags.append("latest")

    # Opportunistically discover additional simple tags, but never depend on this
    # HTML for the catalog to remain functional.
    try:
        html = fetch_text(f"https://ollama.com/library/{family_q}/tags")
        supports_thinking = supports_thinking or bool(
            re.search(r"\bthinking\b", html, flags=re.IGNORECASE)
        )
        escaped = re.escape(family)
        patterns = [
            rf"{escaped}:([A-Za-z0-9._-]+)",
            rf"{escaped}%3A([A-Za-z0-9._-]+)",
            rf"{escaped}&#58;([A-Za-z0-9._-]+)",
            rf"{escaped}\\u003a([A-Za-z0-9._-]+)",
        ]
        for pattern in patterns:
            for raw_tag in re.findall(pattern, html, flags=re.IGNORECASE):
                tag = urllib.parse.unquote(raw_tag).strip()
                lower = tag.lower()
                if not SAFE_COMPONENT.fullmatch(tag):
                    continue
                if any(bit in lower for bit in EXCLUDED_TAG_BITS):
                    continue
                if SIMPLE_TAG.fullmatch(tag) and tag not in tags:
                    tags.append(tag)
    except Exception as exc:
        print(f"warning: {family}: tag metadata page failed: {exc}", file=sys.stderr)

    tags = [tag for tag in tags if SAFE_COMPONENT.fullmatch(tag)]
    tags.sort(key=lambda value: (value.lower() == "latest", tag_size_hint(value), value))
    return tags[:20], supports_thinking


def fetch_manifest(family: str, tag: str) -> dict:
    family_q = urllib.parse.quote(family, safe="._-")
    tag_q = urllib.parse.quote(tag, safe="._-")
    raw = fetch_text(
        f"https://registry.ollama.ai/v2/library/{family_q}/manifests/{tag_q}",
        "application/vnd.oci.image.manifest.v1+json,application/vnd.docker.distribution.manifest.v2+json,application/json",
    )
    return json.loads(raw)


def model_layer(manifest: dict) -> tuple[str, int] | None:
    layers = manifest.get("layers") or []
    exact = [layer for layer in layers if layer.get("mediaType") == "application/vnd.ollama.image.model"]
    candidates = exact or [
        layer for layer in layers
        if str(layer.get("mediaType", "")).startswith("application/vnd.ollama.image.")
    ]
    if not candidates:
        return None
    layer = max(candidates, key=lambda item: int(item.get("size") or 0))
    digest = str(layer.get("digest") or "")
    size = int(layer.get("size") or 0)
    if not digest.startswith("sha256:") or len(digest) != 71:
        return None
    sha = digest.split(":", 1)[1].lower()
    if not re.fullmatch(r"[a-f0-9]{64}", sha):
        return None
    if size < MIN_MODEL_BYTES or size > MAX_MODEL_BYTES:
        return None
    return sha, size


def tag_size_hint(tag: str) -> float:
    match = SIZE_TOKEN.search(tag)
    if not match:
        return 10_000.0
    token = match.group(1).lower().lstrip("e")
    value = float(token[:-1])
    return value / 1000.0 if token.endswith("m") else value


def parameter_count(tag: str) -> str:
    match = SIZE_TOKEN.search(tag)
    if not match:
        return "Unknown"
    return match.group(1).upper()


def minimum_ram_gb(size: int) -> int:
    gib = size / 1024**3
    if gib <= 0.8:
        return 4
    if gib <= 1.8:
        return 6
    if gib <= 3.5:
        return 8
    if gib <= 5.8:
        return 10
    if gib <= 8.5:
        return 14
    if gib <= 12.5:
        return 18
    if gib <= 19.5:
        return 24
    return 32


def pretty_family(family: str) -> str:
    replacements = [
        (r"^qwen", "Qwen "),
        (r"^gemma", "Gemma "),
        (r"^llama", "Llama "),
        (r"^phi", "Phi "),
        (r"^deepseek", "DeepSeek "),
        (r"^mistral", "Mistral "),
        (r"^granite", "Granite "),
        (r"^smollm", "SmolLM "),
    ]
    result = family
    for pattern, replacement in replacements:
        updated = re.sub(pattern, replacement, result, flags=re.IGNORECASE)
        if updated != result:
            return updated.strip()
    return family


def build_catalog() -> list[dict]:
    families, ranks = discover_families()
    result: list[dict] = []
    seen_layers: set[tuple[str, str]] = set()

    for family in families:
        try:
            tags, supports_thinking = discover_tags(family)
        except Exception as exc:
            print(f"warning: {family}: unable to discover tags: {exc}", file=sys.stderr)
            continue

        print(f"{family}: checking {len(tags)} candidate tags")
        for tag in tags:
            try:
                layer = model_layer(fetch_manifest(family, tag))
            except Exception as exc:
                print(f"warning: {family}:{tag}: manifest failed: {exc}", file=sys.stderr)
                continue
            if layer is None:
                continue
            sha, size = layer
            dedupe_key = (family, sha)
            if dedupe_key in seen_layers:
                continue
            seen_layers.add(dedupe_key)
            count = parameter_count(tag)
            result.append(
                {
                    "id": f"{family}:{tag}",
                    "family": family,
                    "tag": tag,
                    "displayName": f"{pretty_family(family)} · {count if count != 'Unknown' else tag}",
                    "parameterCount": count,
                    "downloadBytes": size,
                    "sha256": sha,
                    "minimumRamGb": minimum_ram_gb(size),
                    "supportsThinking": supports_thinking,
                    "catalogRank": ranks.get(family, 10),
                }
            )

    result.sort(key=lambda item: (-int(item["catalogRank"]), int(item["downloadBytes"]), item["id"]))
    return result


def main() -> None:
    models = build_catalog()
    if len(models) < 4:
        raise SystemExit(f"Refusing to replace catalog with only {len(models)} valid models")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "schemaVersion": 1,
        "generatedAt": datetime.now(timezone.utc).isoformat(),
        "source": "https://ollama.com/library",
        "models": models,
    }
    OUTPUT.write_text(json.dumps(payload, indent=2, sort_keys=False) + "\n", encoding="utf-8")
    print(f"wrote {len(models)} models to {OUTPUT.relative_to(ROOT)}")


if __name__ == "__main__":
    main()
