"""Create or reuse a local DualTouch upload key and register it using GitHub CLI."""

import argparse
import base64
import getpass
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile
import warnings
import zipfile


REPOSITORY = "hebi10/DualTouch"
SECRET = "DUALTOUCH_SIGNING_BUNDLE_BASE64"
KEYSTORE = "dualtouch-signing.p12"
CERTIFICATE = "dualtouch-upload.cer"
ALIAS = "dualtouch-upload"
PASSWORD_ENV = "DUALTOUCH_KEY_SETUP_PASSWORD"


def signing_directory():
    if os.name == "nt":
        return Path(os.environ["LOCALAPPDATA"]) / "DualTouch" / "signing"
    return Path.home() / ".local" / "share" / "dualtouch" / "signing"


def find_keytool():
    keytool = shutil.which("keytool")
    if keytool:
        return keytool
    filename = "keytool.exe" if os.name == "nt" else "keytool"
    candidates = []
    if os.environ.get("JAVA_HOME"):
        candidates.append(Path(os.environ["JAVA_HOME"]) / "bin" / filename)
    if os.name == "nt":
        studio = Path(os.environ["ProgramFiles"]) / "Android" / "Android Studio"
        candidates.append(studio / "jbr" / "bin" / filename)
    for path in candidates:
        if path.is_file():
            return str(path)
    raise RuntimeError("Install a JDK or add Android Studio's JDK bin directory to PATH.")


def require_unset_secret(gh):
    result = subprocess.run(
        [gh, "secret", "list", "--repo", REPOSITORY, "--app", "actions", "--json", "name"],
        capture_output=True, text=True, check=False,
    )
    if result.returncode != 0:
        raise RuntimeError("GitHub CLI must be signed in with access to DualTouch repository secrets.")
    if any(entry.get("name") == SECRET for entry in json.loads(result.stdout)):
        raise RuntimeError("The DualTouch signing secret already exists. Leave it in place and verify its certificate.")


def read_password(confirm):
    with warnings.catch_warnings():
        warnings.simplefilter("error", getpass.GetPassWarning)
        password = getpass.getpass("Upload key password (input is hidden): ")
        if not re.fullmatch(r"[A-Za-z0-9_-]{16,}", password):
            raise RuntimeError("Use at least 16 characters from A-Z, a-z, 0-9, underscore and hyphen.")
        if confirm and password != getpass.getpass("Repeat upload key password: "):
            raise RuntimeError("Passwords do not match.")
    return password


def run_keytool(keytool, arguments, password):
    result = subprocess.run(
        [keytool, *arguments, "-storepass:env", PASSWORD_ENV],
        env={**os.environ, PASSWORD_ENV: password},
        capture_output=True, check=False,
    )
    if result.returncode != 0:
        raise RuntimeError("Could not use the upload keystore. Check its password, alias, and JDK.")
    return result.stdout


def setup_key(directory, keytool, password):
    directory.mkdir(mode=0o700, parents=True, exist_ok=True)
    key = directory / KEYSTORE
    if not key.exists():
        with tempfile.TemporaryDirectory(dir=directory) as staging:
            temporary_key = Path(staging) / KEYSTORE
            run_keytool(keytool, [
                "-genkeypair", "-keystore", str(temporary_key), "-storetype", "PKCS12",
                "-alias", ALIAS, "-keyalg", "RSA", "-keysize", "3072", "-validity", "10000",
                "-dname", "CN=DualTouch Upload",
            ], password)
            with key.open("xb") as destination:
                destination.write(temporary_key.read_bytes())

    certificate = run_keytool(keytool, [
        "-exportcert", "-keystore", str(key), "-storetype", "PKCS12", "-alias", ALIAS,
    ], password)
    pin = hashlib.sha256(certificate).hexdigest()
    (directory / CERTIFICATE).write_bytes(certificate)
    (directory / "certificate.sha256").write_text(pin + "\n", encoding="ascii")
    return key, pin


def register_secret(gh, key, pin, password):
    properties = (
        f"storeFile={KEYSTORE}\nstoreType=PKCS12\nkeyAlias={ALIAS}\n"
        f"storePassword={password}\nkeyPassword={password}\n"
    )
    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w", zipfile.ZIP_DEFLATED) as bundle:
        bundle.write(key, KEYSTORE)
        bundle.writestr("signing.properties", properties)
        bundle.writestr("certificate.sha256", pin + "\n")
        bundle.writestr("RESTORE.txt", "DualTouch Google Play upload key.\n")
    require_unset_secret(gh)
    result = subprocess.run(
        [gh, "secret", "set", SECRET, "--repo", REPOSITORY, "--app", "actions"],
        input=base64.b64encode(archive.getvalue()), capture_output=True, check=False,
    )
    if result.returncode != 0:
        raise RuntimeError("GitHub did not confirm secret registration. The encrypted local keystore was retained.")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--register", action="store_true", help="Register the key with the existing GitHub CLI login.")
    args = parser.parse_args()

    gh = shutil.which("gh") if args.register else None
    if args.register and not gh:
        raise RuntimeError("GitHub CLI is required for --register.")
    if gh:
        require_unset_secret(gh)

    directory = signing_directory()
    keytool = find_keytool()
    password = read_password(confirm=not (directory / KEYSTORE).is_file())
    key, pin = setup_key(directory, keytool, password)
    print(f"Encrypted keystore: {key}", flush=True)
    print(f"Public certificate SHA-256: {pin}", flush=True)
    if gh:
        register_secret(gh, key, pin, password)
        print(f"Registered {SECRET} in {REPOSITORY}.")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        raise SystemExit("Cancelled.") from None
    except (OSError, RuntimeError, ValueError, getpass.GetPassWarning) as error:
        raise SystemExit(str(error)) from None
