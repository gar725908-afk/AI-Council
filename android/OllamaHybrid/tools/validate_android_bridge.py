#!/usr/bin/env python3
"""Dependency-free structural checks for the Hybrid Android Bridge scaffold."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parents[1]
errors = []
contracts = ROOT / "app/src/main/java/ai/hybrid/commandcenter/androidbridge/BridgeContracts.kt"
root_probe = ROOT / "app/src/main/java/ai/hybrid/commandcenter/androidbridge/RootAccessProbe.kt"
manifest = ROOT / "app/src/main/AndroidManifest.xml"
spec = ROOT / "docs/ANDROID_BRIDGE_ARCHITECTURE_RU.md"
master = ROOT / "docs/PROJECT_MASTER_SPEC_RU.md"

for path in (contracts, root_probe, manifest, spec, master):
    if not path.exists():
        errors.append(f"Missing required file: {path.relative_to(ROOT)}")

if contracts.exists():
    source = contracts.read_text(encoding="utf-8")
    for required in ("BridgeToolRegistry", "BridgeActionPolicy", "BridgeCapability", "ActionRisk", "ApprovalGrant"):
        if required not in source:
            errors.append(f"Bridge contract missing {required}")
    if "putIfAbsent" not in source:
        errors.append("Registry does not reject duplicate tool IDs")

if root_probe.exists():
    source = root_probe.read_text(encoding="utf-8")
    if 'ProcessBuilder("su", "-c", "id -u")' not in source:
        errors.append("Root probe must use fixed id -u check")
    if "uid == 0" not in source:
        errors.append("Root probe must verify UID 0")
    if "enum class RootDiagnosticCommand" not in source:
        errors.append("Fixed Root diagnostic command enum missing")
    if re.search(r"su\s+-c\s+\$\{|su\s+-c\s+\$[A-Za-z_]", source):
        errors.append("Potential dynamic shell interpolation found")

if manifest.exists():
    try:
        ET.parse(manifest)
    except ET.ParseError as exc:
        errors.append(f"AndroidManifest XML invalid: {exc}")

if spec.exists():
    source = spec.read_text(encoding="utf-8").casefold()
    for phrase in ("не эквивалентен root", "не реализованы", "uid 0", "approvalgrant"):
        if phrase not in source:
            errors.append(f"Architecture spec missing expected boundary: {phrase}")

if errors:
    print("FAIL")
    print("\n".join(f"- {item}" for item in errors))
    sys.exit(1)

print("PASS: bridge files, policy, Root UID check and XML manifest")
print("PASS: architecture docs mark incomplete integrations honestly")
