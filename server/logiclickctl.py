#!/usr/bin/env python3
"""Send a Logiclick command without hand-writing curl requests."""

import argparse
import json
import os
import sys
import urllib.error
import urllib.request


def area(values: list[float]) -> dict:
    return dict(zip(("left", "top", "right", "bottom"), values, strict=True))


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default=os.environ.get("LOGICLICK_API_URL", ""))
    parser.add_argument("--token", default=os.environ.get("LOGICLICK_ADMIN_TOKEN", ""))
    parser.add_argument("--device", default=os.environ.get("LOGICLICK_DEVICE_ID", "phone-1"))
    parser.add_argument("--pixels", action="store_true", help="Use pixel coordinates instead of normalized 0..1 values")
    sub = parser.add_subparsers(dest="command", required=True)

    tap = sub.add_parser("tap")
    tap.add_argument("area", nargs=4, type=float, metavar=("LEFT", "TOP", "RIGHT", "BOTTOM"))
    tap.add_argument("--hold", nargs=2, type=int, default=(60, 100), metavar=("MIN_MS", "MAX_MS"))

    press = sub.add_parser("long-press")
    press.add_argument("area", nargs=4, type=float, metavar=("LEFT", "TOP", "RIGHT", "BOTTOM"))
    press.add_argument("--hold", nargs=2, type=int, default=(500, 900), metavar=("MIN_MS", "MAX_MS"))

    for name in ("swipe", "drag"):
        swipe = sub.add_parser(name)
        swipe.add_argument("start", nargs=4, type=float, metavar=("L1", "T1", "R1", "B1"))
        swipe.add_argument("end", nargs=4, type=float, metavar=("L2", "T2", "R2", "B2"))
        swipe.add_argument("--duration", nargs=2, type=int, default=(250, 450), metavar=("MIN_MS", "MAX_MS"))

    global_action = sub.add_parser("global")
    global_action.add_argument("action", choices=("back", "home", "recents", "notifications", "quick_settings", "dismiss_notification_shade"))

    launch = sub.add_parser("launch")
    launch.add_argument("package")

    raw = sub.add_parser("raw")
    raw.add_argument("json")

    args = parser.parse_args()
    if not args.url or not args.token:
        parser.error("Set --url and --token, or LOGICLICK_API_URL and LOGICLICK_ADMIN_TOKEN")
    space = "pixels" if args.pixels else "normalized"
    if args.command in {"tap", "long-press"}:
        payload = {
            "type": "long_press" if args.command == "long-press" else "tap",
            "coordinateSpace": space,
            "area": area(args.area),
            "holdMs": {"min": args.hold[0], "max": args.hold[1]},
        }
    elif args.command in {"swipe", "drag"}:
        payload = {
            "type": args.command,
            "coordinateSpace": space,
            "startArea": area(args.start),
            "endArea": area(args.end),
            "durationMs": {"min": args.duration[0], "max": args.duration[1]},
        }
    elif args.command == "global":
        payload = {"type": "global_action", "action": args.action}
    elif args.command == "launch":
        payload = {"type": "launch_app", "packageName": args.package}
    else:
        payload = json.loads(args.json)

    request = urllib.request.Request(
        f"{args.url.rstrip('/')}/v1/devices/{args.device}/commands",
        data=json.dumps(payload).encode(),
        headers={"Authorization": f"Bearer {args.token}", "Content-Type": "application/json"},
        method="POST",
    )
    try:
        with urllib.request.urlopen(request, timeout=10) as response:
            print(json.dumps(json.load(response), indent=2))
    except urllib.error.HTTPError as error:
        print(error.read().decode(), file=sys.stderr)
        raise SystemExit(1)


if __name__ == "__main__":
    main()
