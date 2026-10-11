#!/usr/bin/env python3
"""Build the pending Foy SPI at an immutable, local-only version for Ibarra PR CI."""
from pathlib import Path
import re
import subprocess
import tempfile
import xml.etree.ElementTree as ET

PROJECT = Path(__file__).resolve().parents[1]
NS = {"m": "http://maven.apache.org/POM/4.0.0"}
ET.register_namespace("", NS["m"])
revision = (PROJECT / ".forgejo/foy-spi.ref").read_text().strip()
if not re.fullmatch(r"[0-9a-f]{40}", revision):
    raise SystemExit("Foy prerequisite must be an immutable full commit hash")


def run(*args, cwd):
    subprocess.run(args, cwd=cwd, check=True)


with tempfile.TemporaryDirectory(prefix="ibarra-foy-spi-") as temporary:
    source = Path(temporary) / "foy"
    run("git", "clone", "--no-checkout", "https://codefloe.com/Vidocq/foy.git", str(source), cwd=PROJECT)
    run("git", "fetch", "origin", revision, cwd=source)
    run("git", "checkout", "--detach", revision, cwd=source)
    actual = subprocess.check_output(["git", "rev-parse", "HEAD"], cwd=source, text=True).strip()
    if actual != revision:
        raise SystemExit("Foy prerequisite revision mismatch")
    base = ET.parse(source / "pom.xml").find("m:version", NS).text.removesuffix("-SNAPSHOT")
    version = f"{base}-ibarra-spi.{revision[:12]}"
    run("./mvnw", "-B", "-ntp", "versions:set", f"-DnewVersion={version}",
        "-DprocessAllModules=true", "-DgenerateBackupPoms=false", cwd=source)
    run("./mvnw", "-B", "-ntp", "-pl", "foy-api,foy-core", "-am", "install",
        "-Dmaven.test.skip=true", cwd=source)

# Persist the override: installed POMs and the independent example must resolve
# the same prerequisite, rather than falling back to the pre-SPI public snapshot.
pom = PROJECT / "pom.xml"
tree = ET.parse(pom)
tree.find("m:properties/m:foy.version", NS).text = version
tree.write(pom, encoding="utf-8", xml_declaration=True)
print(f"Installed Foy prerequisite {revision} as {version}")
