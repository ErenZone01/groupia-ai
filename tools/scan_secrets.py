"""Scan tracked + non-ignored new files. Never print matched values or raw reports."""
import json
from pathlib import Path
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
IMAGE = "ghcr.io/gitleaks/gitleaks@sha256:c00b6bd0aeb3071cbcb79009cb16a60dd9e0a7c60e2be9ab65d25e6bc8abbb7f"  # v8.30.1


if __name__ == "__main__":
    with tempfile.TemporaryDirectory(prefix="groupia-secret-scan-") as temp:
        snapshot = Path(temp) / "source"
        snapshot.mkdir()
        files = subprocess.check_output(["git", "ls-files", "-z", "--cached", "--others", "--exclude-standard"], cwd=ROOT).decode().split("\0")
        for name in set(files) - {""}:
            source = ROOT / name
            if source.is_file():
                target = snapshot / name
                target.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(source, target)
        result = subprocess.run(["docker", "run", "--rm", "-v", f"{temp}:/scan", IMAGE,
                                 "dir", "/scan/source", "--config", "/scan/source/.gitleaks.toml",
                                 "--redact=100", "--report-format=json", "--report-path=/scan/report.json"],
                                capture_output=True)
        report = Path(temp) / "report.json"
        if report.exists():
            findings = json.loads(report.read_text(encoding="utf8"))
            for item in findings:
                print(f"{item['RuleID']}: {item['File']}:{item['StartLine']}")
            print(f"Secret scan: {len(findings)} findings (values withheld)")
        else:
            print("Secret scan did not produce a report; check Docker availability and image pull.")
        raise SystemExit(result.returncode)
