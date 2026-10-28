"""Independent SDK declaration audit; does not compile Kotlin or test a vehicle."""
import json
import re
from pathlib import Path

root = Path(__file__).resolve().parents[2]
records = [json.loads(line) for line in (root / "docs/product/pleos-sdk.spec.jsonl").read_text().splitlines()]
declarations = {item["id"]: item for item in records if item.get("recordType") == "declaration"}
bindings = json.loads((root / "apps/pleos/sdk-bindings.json").read_text())
errors = []
for binding in bindings:
    official = declarations.get(binding["id"])
    if official is None:
        errors.append(f"Unknown declaration ID {binding['id']}")
        continue
    for field in ["qualifiedName", "name", "signatures"]:
        if binding[field] != official[field]:
            errors.append(f"Mismatch {binding['id']}.{field}")
    if binding["permissions"] != official.get("permissions", {}).get("names", []):
        errors.append(f"Permission mismatch {binding['id']}")

sdk_methods = {item["name"] for item in declarations.values()}
sources = list((root / "apps/pleos/app/src/pleos").rglob("*.kt"))
sdk_sources = []
for source in sources:
    content = source.read_text()
    # Android Car UX policy and app-only interfaces are not Pleos SDK contracts.
    if not re.search(r"^import ai[.]pleos[.]playground[.]", content, re.MULTILINE):
        continue
    sdk_sources.append(source)
    # Android framework calls remain Android calls with fully-qualified imports.
    content = re.sub(r"\bandroid[.]os[.]Looper[.]getMainLooper\(\)", "", content)
    if re.search(r"^import android[.]os[.]Looper$", content, re.MULTILINE):
        content = re.sub(r"\bLooper[.]getMainLooper\(\)", "", content)
    # A prefix must end or continue in CamelCase: app diagnostics.requested()
    # is not the SDK's request(), while requestRoute() still needs a declaration.
    for method in re.findall(r"\.((?:get|set|register|unregister|select|request|initialize|release)(?:[A-Z][A-Za-z0-9]*)?)\(", content):
        if method in {"get", "getJSONObject", "getJSONArray", "setLanguage"}:  # Domain map / org.json, not SDK APIs.
            continue
        if method not in sdk_methods:
            errors.append(f"Unprovided SDK method {source.name}:{method}")

# Catch accidental fabricated readback and road-speed/gear APIs at the native seam.
for forbidden in ["getEvChargePercentLimit", "getCurrentSpeed", "getGear"]:
    if any(re.search(rf"\.{forbidden}\(", source.read_text()) for source in sources):
        errors.append(f"Unsupported native API used: {forbidden}")
assert not errors, "\n".join(errors)
assert sources and bindings, "Empty native scaffold"
print(f"PASS: {len(bindings)} exact SDK declarations and {len(sdk_sources)} SDK-importing Kotlin sources checked; {len(sources)} native seam files checked for forbidden APIs")
print("LIMIT: this check audits source contracts only; see separate Gradle/APK/emulator evidence for native execution")
