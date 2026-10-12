#!/usr/bin/env python3
# Copyright (c) 2026 Yann Blazart, Antoine Sabot-Durand and the Vidocq contributors
# SPDX-License-Identifier: EPL-2.0 OR EUPL-1.2 OR GPL-2.0-or-later
"""Install unmodified official Pages tests and their non-Central JavaTest dependency."""
import hashlib
import io
import os
from pathlib import Path
import subprocess
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parent.parent
DEST = ROOT / "target" / "pages-tck-distribution"


def distribution(url, checksum, override):
    path = Path(os.environ[override]) if override in os.environ else DEST / url.rsplit("/", 1)[1]
    if not path.is_file():
        with urllib.request.urlopen(url, timeout=60) as response:
            data = response.read()
        if hashlib.sha256(data).hexdigest() != checksum:
            raise ValueError("Distribution checksum mismatch: " + url)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
    data = path.read_bytes()
    if hashlib.sha256(data).hexdigest() != checksum:
        raise ValueError("Distribution checksum mismatch: " + str(path))
    return zipfile.ZipFile(io.BytesIO(data))


def install(*arguments):
    subprocess.run([str(ROOT / "mvnw"), "-ntp", "install:install-file", *arguments], cwd=ROOT, check=True)


def main():
    DEST.mkdir(parents=True, exist_ok=True)
    pages = distribution(
        "https://download.eclipse.org/jakartaee/pages/4.0/jakarta-pages-tck-4.0.0.zip",
        "5446aa866601a7c4c425f74054590cfc7f4fc05592a572d399ecc8694e265489", "TCK_PAGES_ZIP")
    jar = DEST / "jakarta-pages-tck-4.0.0.jar"
    jar.write_bytes(pages.read("pages-tck/artifacts/jakarta-pages-tck-4.0.0.jar"))
    pom = DEST / "pages-tck.pom"
    with zipfile.ZipFile(jar) as archive:
        pom.write_bytes(archive.read("META-INF/maven/jakarta.tck/jakarta-pages-tck/pom.xml"))

    # The Pages 4.0 archive omits JavaTest 5.0. Eclipse's earlier distribution
    # carries the exact 5.0 tool; only that dependency is extracted, no 3.1 tests.
    tools = distribution(
        "https://download.eclipse.org/ee4j/jakartaee-tck/jakartaee10/promoted/eftl/jakarta-pages-tck-3.1.0.zip",
        "e9f33d463ca35bdf8a9985cc8871858d7e6ea8a6e90adb00a91a001b1e43e025", "TCK_JAVATEST_ZIP")
    data = tools.read("pages-tck/lib/javatest.jar")
    if hashlib.sha256(data).hexdigest() != "73d0c322e76839c405f2000b568cc8906977b430ba23e83f20d420345e8a13a4":
        raise ValueError("JavaTest tool checksum mismatch")
    tool = DEST / "javatest.jar"
    tool.write_bytes(data)
    install("-Dfile=" + str(tool), "-DgroupId=javatest", "-DartifactId=javatest", "-Dversion=5.0", "-Dpackaging=jar", "-DgeneratePom=true")
    install("-Dfile=" + str(jar), "-DpomFile=" + str(pom))
    print("Official Pages 4.0.0 and JavaTest 5.0 installed; upstream POM preserved.")


if __name__ == "__main__":
    main()
