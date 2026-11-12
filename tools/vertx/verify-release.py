#!/usr/bin/env python3
"""Validate the complete fork Maven runtime closure before the first upload."""
from pathlib import Path
import re
import sys
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def main():
    version = sys.argv[1]
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?", version) or version.endswith("SNAPSHOT"):
        raise ValueError("Expected an immutable semantic release version")
    root = Path(__file__).resolve().parents[2]
    modules = ("quill-engine", "quill-core", "quill-sql", "quill-zio", "quill-vertx-zio")
    expected = {f"{module}_{scala}": module for module in modules for scala in ("2.12", "2.13")}
    expected["quill-vertx-db2-client"] = "quill-vertx-db2-client"
    ns = {"m": "http://maven.apache.org/POM/4.0.0"}

    def field(element, name, default=None):
        return element.findtext("m:" + name, default=default, namespaces=ns)

    for artifact, module in sorted(expected.items()):
        matches = list((root / module / "target").rglob(f"{artifact}-{version}.pom"))
        if len(matches) != 1:
            raise ValueError(f"Expected one prepared POM for {artifact}, found {len(matches)}")
        pom = matches[0]
        metadata = ET.parse(pom).getroot()
        if (field(metadata, "groupId"), field(metadata, "artifactId"), field(metadata, "version")) != ("io.github.ffebr", artifact, version):
            raise ValueError(f"Incorrect coordinates: {pom}")
        for dep in metadata.findall("m:dependencies/m:dependency", ns):
            group, name, scope = field(dep, "groupId"), field(dep, "artifactId"), field(dep, "scope", "compile")
            if scope not in ("compile", "runtime"):
                continue
            if name.startswith("quill-") and (group != "io.github.ffebr" or name not in expected or field(dep, "version") != version):
                raise ValueError(f"Unpublished or foreign Quill runtime dependency in {artifact}: {group}:{name}")
            if re.search(r"jdbc|ojdbc|hikaricp|mysql-connector|^postgresql$|vertx-mssql|vertx-oracle|^vertx-db2-client$", name, re.I):
                raise ValueError(f"Unexpected native runtime dependency in {artifact}: {name}")
            if module == "quill-vertx-db2-client" and (name.startswith("scala-") or re.search(r"_2\.(12|13)$", name)):
                raise ValueError("The Java client must be independent of Scala")
        jar = pom.with_suffix(".jar")
        sources = pom.with_name(pom.stem + "-sources.jar")
        if not jar.is_file() or not sources.is_file():
            raise ValueError(f"Missing binary or source jar for {artifact}")
        with ZipFile(jar) as archive:
            names = archive.namelist()
            if module == "quill-vertx-zio" and any("SqlServerZioVertx" in name or "context/jdbc/" in name for name in names):
                raise ValueError("Unexpected context in native artifact")
            if module == "quill-vertx-db2-client":
                constant = archive.read("io/vertx/db2client/impl/drda/CCSIDConstants.class")
                if b"CP500" not in constant or b"CP1047" in constant:
                    raise ValueError("Missing DB2 CCSID correction")
                archive.read("META-INF/services/io.vertx.sqlclient.spi.Driver")
                archive.read("META-INF/LICENSE-QUILL-VERTX-DB2.txt")
                archive.read("META-INF/NOTICE-QUILL-VERTX-DB2.txt")
        print(f"Validated io.github.ffebr:{artifact}:{version}")


if __name__ == "__main__":
    main()
