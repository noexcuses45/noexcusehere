"""Patches the generated Android project for Health Connect + release builds.

Run AFTER `flutter create --platforms android .`:
    python scripts/patch_android.py
"""
import re
from pathlib import Path

root = Path(__file__).resolve().parent.parent
manifest = root / "android" / "app" / "src" / "main" / "AndroidManifest.xml"
text = manifest.read_text(encoding="utf-8")

additions = """    <uses-permission android:name="android.permission.INTERNET"/>
    <uses-permission android:name="android.permission.health.READ_STEPS"/>
    <queries>
        <package android:name="com.google.android.apps.healthdata"/>
    </queries>
"""
if "health.READ_STEPS" not in text:
    text = text.replace("<application", additions + "    <application", 1)

if "ACTION_SHOW_PERMISSIONS_RATIONALE" not in text:
    rationale = """            <intent-filter>
                <action android:name="androidx.health.ACTION_SHOW_PERMISSIONS_RATIONALE"/>
            </intent-filter>
"""
    text = text.replace("</activity>", rationale + "        </activity>", 1)

manifest.write_text(text, encoding="utf-8")
print("Patched AndroidManifest.xml")

for name in ("build.gradle.kts", "build.gradle"):
    gradle = root / "android" / "app" / name
    if gradle.exists():
        g = gradle.read_text(encoding="utf-8")
        g = re.sub(r"minSdk\s*=\s*flutter\.minSdkVersion", "minSdk = 28", g)
        g = re.sub(r"minSdkVersion\s+flutter\.minSdkVersion", "minSdkVersion 28", g)
        gradle.write_text(g, encoding="utf-8")
        print(f"Patched {name} (minSdk 28 for Health Connect)")
        break
