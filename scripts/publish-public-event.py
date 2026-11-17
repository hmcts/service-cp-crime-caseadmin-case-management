#!/usr/bin/env python3
"""Publish a public.progression.group-prosecution-cases-created event to the local Artemis broker.

Usage:  python3 scripts/publish-public-event.py [groupId] [eventName]

Stdlib only. Speaks STOMP to Artemis's default multi-protocol acceptor on 61616, the same way
service-cp-crime-yot-results-distribution's docker/sdg-echo does:
  * destination is the BARE address `public.event` (no /topic/ prefix - the default acceptor does not
    strip it, and the subscription would hear nothing);
  * the CPPNAME header becomes the JMS property the service's selector filters on;
  * no content-length header, so Artemis delivers a TextMessage (as the CQRS framework does).
"""
import json
import os
import socket
import sys
import uuid
from datetime import datetime, timezone

HOST = os.environ.get("ARTEMIS_HOST", "localhost")
PORT = int(os.environ.get("ARTEMIS_PORT", "61616"))
USER = os.environ.get("ARTEMIS_USER", "admin")
PASSWORD = os.environ.get("ARTEMIS_PASSWORD", "admin")
TOPIC = os.environ.get("PUBLIC_EVENT_TOPIC", "public.event")
NULL = "\x00"


def frame(command, headers, body=""):
    lines = [command] + ["{}:{}".format(k, v) for k, v in headers.items()]
    return ("\n".join(lines) + "\n\n" + body + NULL).encode("utf-8")


def main():
    group_id = sys.argv[1] if len(sys.argv) > 1 else str(uuid.uuid4())
    event_name = sys.argv[2] if len(sys.argv) > 2 else "public.progression.group-prosecution-cases-created"
    envelope = {
        "_metadata": {
            "id": str(uuid.uuid4()),
            "name": event_name,
            "createdAt": datetime.now(timezone.utc).isoformat(),
            "context": {"user": "00000000-0000-0000-0000-000000000000"},
            "stream": {"id": group_id},
        },
        "groupId": group_id,
    }
    with socket.create_connection((HOST, PORT), timeout=10) as broker:
        broker.sendall(frame("CONNECT", {"accept-version": "1.2", "host": "/",
                                         "login": USER, "passcode": PASSWORD}))
        answer = broker.recv(4096).decode("utf-8", "replace")
        if not answer.startswith("CONNECTED"):
            raise SystemExit("broker refused the STOMP connection: " + answer.strip())
        broker.sendall(frame("SEND", {"destination": TOPIC, "CPPNAME": event_name,
                                      "content-type": "application/json"}, json.dumps(envelope)))
        broker.sendall(frame("DISCONNECT", {"receipt": str(uuid.uuid4())}))
    print("published {} groupId={} to {}:{} address {}".format(event_name, group_id, HOST, PORT, TOPIC))


if __name__ == "__main__":
    main()
