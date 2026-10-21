#!/usr/bin/env python3
"""Run the real Swing workbench in a temporary empty profile; retain only its metrics/log."""

import argparse
import os
from pathlib import Path
import subprocess
import tempfile
import xml.etree.ElementTree as ET


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--idle-seconds", type=int, default=35)
    args = parser.parse_args()
    if not 0 <= args.idle_seconds <= 120:
        parser.error("idle-seconds must be between 0 and 120")
    root = Path(__file__).resolve().parents[2]
    reports = root / "app/target/surefire-reports"
    classpath = None
    for report in reports.glob("TEST-*.xml"):
        prop = ET.parse(report).getroot().find("./properties/property[@name='java.class.path']")
        if prop is not None:
            classpath = prop.attrib["value"]
            break
    if not classpath or not (root / "app/target/test-classes/com/jlshell/app/WorkbenchPerformanceProbe.class").is_file():
        parser.error("Run mvn -pl app,launcher -am test with JDK 21 first")
    java_home = os.environ.get("JAVA_HOME")
    java = str(Path(java_home) / "bin/java") if java_home else "java"
    output = args.output.resolve()
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="jlshell-performance-") as temporary:
        command = [java, "-Xms32m", "-Xmx512m", "-XX:+UseG1GC", "-XX:+ExplicitGCInvokesConcurrent",
                   "-XX:G1PeriodicGCInterval=30000", "-XX:MinHeapFreeRatio=20", "-XX:MaxHeapFreeRatio=40",
                   "--add-exports=java.desktop/sun.swing=ALL-UNNAMED",
                   "--add-opens=java.desktop/sun.swing=ALL-UNNAMED",
                   "--add-opens=java.desktop/javax.swing=ALL-UNNAMED",
                   "-Duser.home=" + temporary, "-Djlshell.ui.renderer=swing", "-Djlshell.ui.preview=true",
                   "-Djlshell.performance.idleMillis=" + str(args.idle_seconds * 1000),
                   "-cp", classpath, "com.jlshell.app.WorkbenchPerformanceProbe"]
        with output.open("w", encoding="utf-8") as log:
            result = subprocess.run(command, cwd=root, stdout=log, stderr=subprocess.STDOUT,
                                    timeout=60 + args.idle_seconds)
    for line in output.read_text(encoding="utf-8").splitlines():
        if line.startswith("PERF "):
            print(line)
    print("Full log:", output)
    raise SystemExit(result.returncode)


if __name__ == "__main__":
    main()
