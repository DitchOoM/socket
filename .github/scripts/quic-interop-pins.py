#!/usr/bin/env python3
"""The interop gate's pinned runner commit and peer images (test-harness/quic-interop/peer-images.json).

  apply <pins.json> <runner dir>    rewrites the runner checkout's implementations file and docker-compose.yml so every
                                    peer, the simulator and the iperf endpoint run the image a digest names
  resolve <pins.json> <runner dir>  re-resolves every tag of the runner's implementations file and docker-compose.yml
                                    to its registry digest and rewrites pins.json with them and the checkout's commit

apply fails on a peer the runner offers that pins.json does not name, so a new peer is pinned before it runs. It also
gives the server and client containers an unlimited core size: haproxy since 62c0c84b81 turns `set-dumpable` on under
`-d`, which its qns image runs with, and exits when it cannot raise the core limit, which a container without
CAP_SYS_RESOURCE can only do when docker already set the hard limit (run 45399057151: every haproxy cell).
"""
import json
import os
import subprocess
import sys
import urllib.request

ACCEPT = ", ".join([
    "application/vnd.oci.image.index.v1+json",
    "application/vnd.docker.distribution.manifest.list.v2+json",
    "application/vnd.oci.image.manifest.v1+json",
    "application/vnd.docker.distribution.manifest.v2+json",
])
# The images docker-compose.yml names itself, by the key their pin has in pins.json.
COMPOSE_IMAGES = {"simulator": "martenseemann/quic-network-simulator", "iperf": "martenseemann/quic-interop-iperf-endpoint"}
MEMLOCK = "      memlock: 67108864\n"
CORE = "      core: -1\n"


def implementations_file(runner):
    for name in ("implementations_quic.json", "implementations.json"):
        path = os.path.join(runner, name)
        if os.path.exists(path):
            return path
    sys.exit(f"no implementations file in {runner}")


def pinned(tag, digest):
    """An image reference that names its tag for the reader and its digest for docker."""
    return f"{tag}@{digest}"


def apply(pins_path, runner):
    pins = json.load(open(pins_path))
    head = subprocess.run(["git", "-C", runner, "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    if head != pins["runner"]:
        sys.exit(f"the runner checkout is {head}, the pins name {pins['runner']}")
    path = implementations_file(runner)
    impl = json.load(open(path))
    missing = sorted(set(impl) - set(pins["images"]))
    if missing:
        sys.exit(f"the runner offers peers with no pinned image: {missing}; run quic-interop-pins.py resolve")
    for name, entry in impl.items():
        pin = pins["images"][name]
        if entry["image"] != pin["tag"]:
            sys.exit(f"{name}: the runner names {entry['image']}, the pin was resolved from {pin['tag']}")
        entry["image"] = pinned(pin["tag"], pin["digest"])
    json.dump(impl, open(path, "w"), indent=2)
    compose_path = os.path.join(runner, "docker-compose.yml")
    compose = open(compose_path).read()
    for key, image in COMPOSE_IMAGES.items():
        pin = pins["compose"][key]
        if f"image: {image}\n" not in compose:
            sys.exit(f"docker-compose.yml no longer names {image}")
        compose = compose.replace(f"image: {image}\n", f"image: {pinned(pin['tag'], pin['digest'])}\n")
    if compose.count(MEMLOCK) != 2:
        sys.exit("docker-compose.yml's server and client ulimits are not where this expects them")
    compose = compose.replace(MEMLOCK, MEMLOCK + CORE)
    open(compose_path, "w").write(compose)
    for name in sorted(impl):
        print(f"{name}: {impl[name]['image']}")
    for key, pin in sorted(pins["compose"].items()):
        print(f"{key}: {pinned(pin['tag'], pin['digest'])}")


def split(ref):
    last = ref.split("/")[-1]
    name, tag = (ref.rsplit(":", 1) if ":" in last else (ref, "latest"))
    first = name.split("/")[0]
    if "." in first or ":" in first:
        return first, name[len(first) + 1:], tag
    return "registry-1.docker.io", name if "/" in name else "library/" + name, tag


def token(registry, repo):
    if registry == "registry-1.docker.io":
        url = f"https://auth.docker.io/token?service=registry.docker.io&scope=repository:{repo}:pull"
    elif registry == "ghcr.io":
        url = f"https://ghcr.io/token?scope=repository:{repo}:pull"
    else:
        url = f"https://{registry}/v2/token?scope=repository:{repo}:pull"
    with urllib.request.urlopen(url, timeout=30) as r:
        answer = json.load(r)
    return answer.get("token") or answer["access_token"]


def digest(ref):
    registry, repo, tag = split(ref)
    request = urllib.request.Request(f"https://{registry}/v2/{repo}/manifests/{tag}", method="HEAD")
    request.add_header("Accept", ACCEPT)
    request.add_header("Authorization", "Bearer " + token(registry, repo))
    with urllib.request.urlopen(request, timeout=30) as r:
        return r.headers["Docker-Content-Digest"]


def resolve(pins_path, runner):
    pins = json.load(open(pins_path))
    impl = json.load(open(implementations_file(runner)))
    pins["runner"] = subprocess.run(["git", "-C", runner, "rev-parse", "HEAD"], capture_output=True, text=True, check=True).stdout.strip()
    pins["images"] = {name: {"tag": entry["image"], "digest": digest(entry["image"])} for name, entry in sorted(impl.items())}
    pins["compose"] = {
        key: {"tag": f"{image}:latest", "digest": digest(f"{image}:latest")} for key, image in sorted(COMPOSE_IMAGES.items())
    }
    open(pins_path, "w").write(json.dumps(pins, indent=2) + "\n")


def main(argv):
    if len(argv) != 4 or argv[1] not in ("apply", "resolve"):
        sys.exit(__doc__)
    {"apply": apply, "resolve": resolve}[argv[1]](argv[2], argv[3])


if __name__ == "__main__":
    main(sys.argv)
