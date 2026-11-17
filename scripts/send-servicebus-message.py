#!/usr/bin/env python3
"""Send one JSON message to the local Service Bus emulator queue, or peek its dead-letter queue.

Setup:  python3 -m venv .venv && .venv/bin/pip install azure-servicebus
Usage:  .venv/bin/python scripts/send-servicebus-message.py '{"groupId":"..."}' [subject]
        .venv/bin/python scripts/send-servicebus-message.py --peek-dlq
"""
import os
import sys
import uuid

from azure.servicebus import ServiceBusClient, ServiceBusMessage, ServiceBusSubQueue

CONNECTION_STRING = os.environ.get(
    "CP_CM_SERVICEBUS_CONNECTION_STRING",
    "Endpoint=sb://localhost;SharedAccessKeyName=RootManageSharedAccessKey;"
    "SharedAccessKey=SAS_KEY_VALUE;UseDevelopmentEmulator=true;")
QUEUE = os.environ.get("CP_CM_SERVICEBUS_QUEUE_NAME", "casemanagement.requests")


def main():
    with ServiceBusClient.from_connection_string(CONNECTION_STRING) as client:
        if len(sys.argv) > 1 and sys.argv[1] == "--peek-dlq":
            with client.get_queue_receiver(QUEUE, sub_queue=ServiceBusSubQueue.DEAD_LETTER) as dlq:
                for message in dlq.peek_messages(max_message_count=20):
                    print(message.message_id, message.dead_letter_reason,
                          message.dead_letter_error_description, str(message))
            return
        body = sys.argv[1] if len(sys.argv) > 1 else '{"groupId":"%s"}' % uuid.uuid4()
        subject = sys.argv[2] if len(sys.argv) > 2 else None
        message = ServiceBusMessage(body, message_id=str(uuid.uuid4()), subject=subject,
                                    content_type="application/json")
        with client.get_queue_sender(QUEUE) as sender:
            sender.send_messages(message)
        print("sent messageId={} to {}: {}".format(message.message_id, QUEUE, body))


if __name__ == "__main__":
    main()
