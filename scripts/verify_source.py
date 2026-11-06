#!/usr/bin/env python3
"""Check localized resources and the exported public schema contract."""
from pathlib import Path
import json
import re
import xml.etree.ElementTree as ET
root = Path(__file__).resolve().parents[1]
def strings(module, locale):
    return {item.attrib["name"]: item for item in ET.parse(root / f"{module}/src/main/res/{locale}/strings.xml").getroot() if "name" in item.attrib}
def format_arguments(text):
    return sorted(re.findall(r"%[0-9]+\$[a-z]", text))
for module in ["app", "core/device"]:
    english, chinese = strings(module, "values"), strings(module, "values-zh")
    assert english.keys() == chinese.keys(), f"Locale resource keys differ in {module}"
    for key in english:
        assert english[key].tag == chinese[key].tag, key
        if english[key].tag == "plurals":
            assert english[key].find("item[@quantity='other']") is not None, key
            assert chinese[key].find("item[@quantity='other']") is not None, key
            expected = format_arguments("".join(english[key][0].itertext()))
            for item in list(english[key]) + list(chinese[key]):
                assert format_arguments("".join(item.itertext())) == expected, key
        else:
            assert format_arguments("".join(english[key].itertext())) == format_arguments("".join(chinese[key].itertext())), key
network = ET.parse(root / "app/src/main/res/xml/network_security_config.xml").getroot()
base_config = network.find("base-config")
assert base_config.attrib["cleartextTrafficPermitted"] == "true"
assert [cert.attrib.get("src") for cert in base_config.findall("./trust-anchors/certificates")] == ["system"]
for config in network.findall("domain-config"):
    if config.attrib.get("cleartextTrafficPermitted") == "true":
        for domain in config.findall("domain"):
            assert domain.text in {"localhost", "127.0.0.1", "::1"}
            assert domain.attrib["includeSubdomains"] == "false"

# Dynamic LAN destinations are enforced by the shared HTTP client, not by a fixed XML allowlist.
policy = (root / "core/model/src/main/kotlin/com/droidance/core/model/llm/CleartextNetworkPolicy.kt").read_text()
assert "addInterceptor(DestinationCheck(client.dns))" in policy
assert "addNetworkInterceptor(ConnectionCheck(client.dns))" in policy
assert "requireLocal(url.host, dns)" in policy
assert "route.proxy.type() != Proxy.Type.DIRECT" in policy
assert "route.socketAddress.address?.let(::isLocal) != true" in policy
assert "response.isRedirect" in policy and "requireLocal(target.host, dns)" in policy
app_module = (root / "app/src/main/java/com/droidance/di/AppModule.kt").read_text()
router = (root / "core/model/src/main/kotlin/com/droidance/core/model/llm/LLMRouter.kt").read_text()
assert "CleartextNetworkPolicy.configure(OkHttpClient.Builder())" in app_module
assert "CleartextNetworkPolicy.configure(httpClient.newBuilder())" in router

schemas = list((root / "core/persistence/schemas").rglob("*.json"))
assert schemas, "Room schemas are required"
for path in schemas:
    data = json.loads(path.read_text())
    assert data["database"]["version"] == int(path.stem), path
for source in (root / "core/persistence/src/main").rglob("*Database.kt"):
    content = source.read_text()
    version = re.search(r"version\s*=\s*(\d+)", content)
    package = re.search(r"(?m)^package ([A-Za-z0-9_.]+)", content)
    if version and package:
        directory = root / "core/persistence/schemas" / f"{package[1]}.{source.stem}"
        assert max(int(p.stem) for p in directory.glob("*.json")) == int(version[1]), source
assert not any("fallbackToDestructiveMigration" in source.read_text() for source in (root / "app/src/main").rglob("*.kt"))
print("Locale, local transport policy wiring and exported Room schema contracts verified")
