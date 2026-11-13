"""Publish and verify APKs in GitHub Releases using the authenticated GitHub CLI."""
import argparse
import datetime as dt
import hashlib
import json
import re
import subprocess
import tempfile
from pathlib import Path

REPO = r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+"
VERSION = r"(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){1,2}"
SHA = r"[0-9a-f]{40}"
DIGEST = r"[0-9a-f]{64}"
TAG = r"[A-Za-z0-9][A-Za-z0-9._-]*"
APK = r"[A-Za-z0-9][A-Za-z0-9._-]*\.apk"
ROOT = Path(__file__).resolve().parents[1]


def require(condition, message):
    if not condition:
        raise ValueError(message)


def matches(pattern, value):
    return isinstance(value, str) and re.fullmatch(pattern, value) is not None


def sha256(path):
    with Path(path).open("rb") as source:
        return hashlib.file_digest(source, "sha256").hexdigest()


def gh(*args):
    return subprocess.run(["gh", *map(str, args)], check=True, text=True, stdout=subprocess.PIPE).stdout


def api(endpoint, *args):
    return json.loads(gh("api", endpoint, *args))


def pages(endpoint):
    return [item for page in api(endpoint, "--paginate", "--slurp") for item in page]


def git_tag_commit(repo, tag):
    target = api(f"repos/{repo}/git/ref/tags/{tag}")["object"]
    while target["type"] == "tag":
        target = api(f"repos/{repo}/git/tags/{target['sha']}")["object"]
    require(target["type"] == "commit", f"Tag {tag} does not point to a commit")
    return target["sha"]


def load_registry(path):
    def unique_object(pairs):
        obj = dict(pairs)
        require(len(obj) == len(pairs), "Registry contains duplicate keys")
        return obj

    data = json.loads(Path(path).read_text(), object_pairs_hook=unique_object)
    require(type(data["schema_version"]) is int and data["schema_version"] == 1, "Unsupported registry schema")
    entries = data["protected_versions"]
    require(isinstance(entries, list), "Invalid protected version list")
    identities = set()
    tags = set()
    for entry in entries:
        repo, version, tag = (entry[key] for key in ("repository", "version", "tag"))
        require(matches(REPO, repo) and matches(VERSION, version) and matches(TAG, tag), "Invalid protected identity")
        require(entry["protected"] is True and entry["category"] in ("TEST", "STABLE"), "Invalid protection")
        require(entry["app"] and entry["reason"], "Missing protection details")
        require(matches(SHA, entry["commit_sha"]) and matches(DIGEST, entry["apk_sha256"]), "Invalid protected digest")
        require(matches(APK, entry["asset_name"]), "Invalid asset name")
        require(entry["release_url"] == f"https://github.com/{repo}/releases/tag/{tag}", "Invalid release URL")
        dt.date.fromisoformat(entry["protected_on"])
        ids = entry.get("source_artifact_ids", [])
        require(isinstance(ids, list) and all(type(i) is int and i > 0 for i in ids), "Invalid source artifacts")
        identity, tag_identity = (repo.lower(), version), (repo.lower(), tag)
        require(identity not in identities and tag_identity not in tags, "Duplicate protected version")
        identities.add(identity)
        tags.add(tag_identity)
    return entries


def check_cleanup_candidate(entries, repo, artifact):
    """Reject protected sources by artifact ID, commit, or version in the artifact name."""
    sha = artifact["workflow_run"]["head_sha"]
    require(matches(SHA, sha), "Missing artifact provenance")
    for item in entries:
        if item["repository"].lower() != repo.lower():
            continue
        version = re.escape(item["version"])
        require(artifact["id"] not in item.get("source_artifact_ids", []) and
                sha != item["commit_sha"] and not re.search(rf"(?<![0-9.]){version}(?![0-9.])", artifact["name"]),
                f"Protected artifact: {repo} {artifact['id']}")


def verify_apk(path, version, version_code, package, tools):
    def run(name, *args):
        return subprocess.run([str(tools / name), *map(str, args)], check=True, text=True,
                              stdout=subprocess.PIPE).stdout

    require(path.is_file() and path.stat().st_size < 2 * 1024**3, "APK missing or above release asset limit")
    run("apksigner", "verify", "--verbose", path)
    badging = run("aapt2", "dump", "badging", path)
    actual = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging, re.M)
    require(actual and actual.groups() == (package, str(version_code), version), "APK package or version mismatch")


def verify_release(repo, release, tag, commit, name, digest, category, draft=False):
    require(release["tag_name"] == tag and release["draft"] is draft, "Wrong tag or release state")
    require(release["prerelease"] == (category == "TEST"), "Wrong release category")
    require(git_tag_commit(repo, tag) == commit, "Release tag points to a different commit")
    assets = pages(f"repos/{repo}/releases/{release['id']}/assets?per_page=100")
    apks = [item for item in assets if item["name"].lower().endswith(".apk")]
    require(len(apks) == 1 and apks[0]["name"] == name and apks[0]["state"] == "uploaded",
            "Missing, unexpected or incomplete APK asset")
    asset = apks[0]
    require(asset.get("digest") in (None, f"sha256:{digest}"), "Release asset digest mismatch")
    with tempfile.TemporaryDirectory() as scratch:
        apk = Path(scratch) / name
        with apk.open("wb") as target:
            subprocess.run(["gh", "api", f"repos/{repo}/releases/assets/{asset['id']}",
                            "-H", "Accept: application/octet-stream"], check=True, stdout=target)
        require(apk.stat().st_size == asset["size"] and sha256(apk) == digest,
                "Downloaded release APK does not match the original")
    return asset["browser_download_url"]


def known_versions(release):
    tag = release["tag_name"]
    candidates = [tag] + [a["name"] for a in release.get("assets", []) if a["name"].lower().endswith(".apk")]
    return {match[1] for candidate in candidates for match in
            re.finditer(rf"(?<![0-9.])({VERSION})(?![0-9]|\.[0-9])", candidate)}


def publish(args):
    for pattern, value in ((REPO, args.repo), (VERSION, args.version), (SHA, args.commit), (TAG, args.tag),
                           (APK, args.asset_name), (r"[a-zA-Z0-9_.]+", args.package)):
        require(matches(pattern, value), f"Invalid release input: {value}")
    require(args.version_code > 0, "Invalid versionCode")
    require(not args.historical or args.expected_sha256, "Historical APK must have a known digest")
    require(not args.expected_sha256 or matches(DIGEST, args.expected_sha256), "Invalid expected digest")
    verify_apk(args.apk, args.version, args.version_code, args.package, args.build_tools)
    digest = sha256(args.apk)
    require(not args.expected_sha256 or digest == args.expected_sha256, "Original APK digest mismatch")
    for entry in load_registry(args.registry):
        if entry["repository"].lower() == args.repo.lower() and (
                entry["version"] == args.version or entry["tag"] == args.tag):
            require((entry["version"], entry["tag"], entry["commit_sha"], entry["apk_sha256"],
                     entry["asset_name"], entry["category"]) ==
                    (args.version, args.tag, args.commit, digest, args.asset_name, args.category),
                    "Protected version must match the registered APK exactly")
    require(api(f"repos/{args.repo}/commits/{args.commit}")["sha"] == args.commit, "Unknown source commit")
    releases = pages(f"repos/{args.repo}/releases?per_page=100")
    other = [r for r in releases if r["tag_name"] != args.tag and args.version in known_versions(r)]
    require(not other, "This APK version already has another release tag")
    existing = next((r for r in releases if r["tag_name"] == args.tag), None)
    if existing is not None and not existing["draft"]:
        url = verify_release(args.repo, existing, args.tag, args.commit, args.asset_name, digest, args.category)
        print(f"Already published: {url}\nSHA-256 {digest}")
        return
    if args.resume_draft:
        existing = api(f"repos/{args.repo}/releases/{args.resume_draft}")
        require(existing["draft"] and existing["tag_name"] == args.tag and
                existing["prerelease"] == (args.category == "TEST") and
                args.commit in existing["body"] and digest in existing["body"],
                "Draft does not match the approved recovery identity")
    else:
        require(existing is None, "Existing draft requires review and explicit recovery")
    if not args.historical:
        version_key = lambda v: tuple(map(int, v.split(".")))
        require(all(version_key(args.version) > version_key(v) for r in releases
                    if any(a["name"].lower().endswith(".apk") for a in r.get("assets", []))
                    for v in known_versions(r)),
                "Version must advance beyond published APK versions")

    refs = api(f"repos/{args.repo}/git/matching-refs/tags/{args.tag}")
    if not any(ref["ref"] == f"refs/tags/{args.tag}" for ref in refs):
        api(f"repos/{args.repo}/git/refs", "-X", "POST", "-f", f"ref=refs/tags/{args.tag}",
            "-f", f"sha={args.commit}")
    require(git_tag_commit(args.repo, args.tag) == args.commit, "Conflicting release tag")
    notes = (f"Category: {args.category}\n\n{args.notes}\n\n"
             f"Source commit: `{args.commit}`\n\nAPK SHA-256: `{digest}`\n")
    release = existing or api(
        f"repos/{args.repo}/releases", "-X", "POST", "-f", f"tag_name={args.tag}",
        "-f", f"target_commitish={args.commit}", "-f", f"name={args.app} {args.version} — {args.category}",
        "-f", f"body={notes}", "-F", "draft=true", "-F", f"prerelease={str(args.category == 'TEST').lower()}",
        "-f", "make_latest=false")
    require(release["draft"], "Release must remain a draft until APK verification")
    assets = pages(f"repos/{args.repo}/releases/{release['id']}/assets?per_page=100")
    if not assets:
        endpoint = release["upload_url"].split("{", 1)[0]
        require(endpoint == f"https://uploads.github.com/repos/{args.repo}/releases/{release['id']}/assets",
                "Unexpected GitHub asset upload endpoint")
        api(f"{endpoint}?name={args.asset_name}", "-X", "POST", "-H",
            "Content-Type: application/vnd.android.package-archive", "--input", args.apk)
    verify_release(args.repo, release, args.tag, args.commit, args.asset_name, digest, args.category, draft=True)
    api(f"repos/{args.repo}/releases/{release['id']}", "-X", "PATCH", "-F", "draft=false",
        "-f", "make_latest=false")
    published = api(f"repos/{args.repo}/releases/tags/{args.tag}")
    url = verify_release(args.repo, published, args.tag, args.commit, args.asset_name, digest, args.category)
    print(f"Published: {url}\nSHA-256 {digest}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--registry", type=Path)
    sub = parser.add_subparsers(dest="command", required=True)
    sub.add_parser("list-protected")
    sub.add_parser("verify-protected")
    cleanup = sub.add_parser("check-cleanup")
    cleanup.add_argument("--repo", required=True)
    cleanup.add_argument("--artifact-id", type=int, required=True)
    pub = sub.add_parser("publish")
    for arg in ("repo", "version", "commit", "tag", "app", "package", "asset-name"):
        pub.add_argument(f"--{arg}", required=True)
    pub.add_argument("--category", choices=("TEST", "STABLE"), default="TEST")
    pub.add_argument("--apk", type=Path, required=True)
    pub.add_argument("--build-tools", type=Path, required=True)
    pub.add_argument("--version-code", type=int, required=True)
    pub.add_argument("--expected-sha256")
    pub.add_argument("--historical", action="store_true")
    pub.add_argument("--resume-draft", type=int)
    pub.add_argument("--notes", default="")
    args = parser.parse_args()
    if args.command == "check-cleanup":
        # A missing credential, missing live file or invalid JSON blocks cleanup.
        with tempfile.TemporaryDirectory() as scratch:
            registry = Path(scratch) / "protected-versions.json"
            registry.write_text(gh("api", "repos/bald2012-lab/android-development-kit/"
                                   "contents/config/protected-versions.json?ref=main",
                                   "-H", "Accept: application/vnd.github.raw+json"))
            entries = load_registry(registry)
        require(matches(REPO, args.repo) and args.artifact_id > 0, "Invalid artifact identity")
        artifact = api(f"repos/{args.repo}/actions/artifacts/{args.artifact_id}")
        check_cleanup_candidate(entries, args.repo, artifact)
        print("Registry check passed. Release verification and explicit deletion approval are still required.")
        return
    args.registry = args.registry or ROOT / "config/protected-versions.json"
    if args.command == "publish":
        publish(args)
        return
    entries = load_registry(args.registry)
    if args.command == "list-protected":
        print(json.dumps(entries, ensure_ascii=False, indent=2))
    elif args.command == "verify-protected":
        for entry in entries:
            release = api(f"repos/{entry['repository']}/releases/tags/{entry['tag']}")
            print(verify_release(entry["repository"], release, entry["tag"], entry["commit_sha"],
                                 entry["asset_name"], entry["apk_sha256"], entry["category"]))


if __name__ == "__main__":
    try:
        main()
    except (ValueError, OSError, KeyError, TypeError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
