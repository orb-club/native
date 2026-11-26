"""Create an isolated, synthetic test app. Does not build, install or launch it."""
import argparse
from pathlib import Path
import shutil
import subprocess

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("destination", type=Path)
parser.add_argument("--stock", action="store_true", help="Use unpatched pub.dev JNI 0.14.2")
args = parser.parse_args()
probe = Path(__file__).resolve().parent
root = probe.parents[1]
target = args.destination.resolve()
if target.exists():
    parser.error("destination must not exist; existing app data/files are never overwritten")
subprocess.run(["fvm", "flutter", "create", "--platforms=android", "--no-pub",
                "--project-name=sentry_jni_probe", "--org=club.orb.audit", str(target)],
               cwd=probe, check=True)
shutil.copytree(Path(__file__).parent / "template", target, dirs_exist_ok=True)
# The generated counter test does not describe this diagnostic app.
shutil.rmtree(target / "test")
shutil.copyfile(probe / ".fvmrc", target / ".fvmrc")
manifest = target / "pubspec.yaml"
text = manifest.read_text()
if args.stock:
    text = text.split("\ndependency_overrides:")[0] + "\n"
else:
    text = text.replace("__JNI_SOURCE__", str(root / "pkgs/jni"))
manifest.write_text(text)
subprocess.run(["fvm", "flutter", "pub", "get"], cwd=target, check=True)
print(f"Prepared {target}; build/launch only with explicit authorization.")
