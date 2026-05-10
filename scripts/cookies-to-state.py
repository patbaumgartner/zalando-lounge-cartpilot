#!/usr/bin/env python3
"""Convert a Netscape cookies.txt export into Playwright storageState JSON.

Usage:
    python3 scripts/cookies-to-state.py [cookies.txt] [session/state.json]

Defaults to reading ./cookies.txt and writing ./session/state.json so a freshly
exported browser session seeds an authenticated dev run (trust-session-file-in-dev=true).
"""
import json
import os
import sys


def convert(src: str, dst: str) -> int:
    cookies = []
    with open(src, encoding="utf-8") as fh:
        for raw in fh:
            line = raw.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            parts = line.split("\t")
            if len(parts) != 7:
                continue
            domain, _include_sub, path, secure, expires, name, value = parts
            try:
                exp = int(expires)
            except ValueError:
                exp = 0
            cookies.append({
                "name": name,
                "value": value,
                "domain": domain,
                "path": path,
                "expires": exp if exp > 0 else -1,
                "httpOnly": False,
                "secure": secure.upper() == "TRUE",
                "sameSite": "Lax",
            })

    os.makedirs(os.path.dirname(dst) or ".", exist_ok=True)
    with open(dst, "w", encoding="utf-8") as fh:
        json.dump({"cookies": cookies, "origins": []}, fh, indent=2)
    return len(cookies)


def main() -> None:
    src = sys.argv[1] if len(sys.argv) > 1 else "cookies.txt"
    dst = sys.argv[2] if len(sys.argv) > 2 else "session/state.json"
    count = convert(src, dst)
    print(f"wrote {count} cookies to {dst}")


if __name__ == "__main__":
    main()
