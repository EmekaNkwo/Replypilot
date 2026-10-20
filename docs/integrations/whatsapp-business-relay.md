# WhatsApp Business Platform — relay integration

ReplyPilot has two separate WhatsApp channels:

| Channel | What it is | Can start conversations unattended | Delivery confirmation |
|---|---|---|---|
| **WhatsApp** (on-device) | Your normal WhatsApp or WhatsApp Business *app*, reached through its notifications | No — scheduled sends become a one-tap handoff | No |
| **WhatsApp Business Platform** | Meta's official Cloud API, reached through *your own relay server* | Yes, within Meta's messaging rules | Yes, via webhooks |

A standard WhatsApp account cannot be used through the Business Platform, and the on-device channel does not
gain Business Platform abilities by installing the WhatsApp Business app.

## Why a relay?

The Cloud API needs a privileged access token (a system-user token) tied to your Meta business. That token must
never be embedded in a mobile app. ReplyPilot therefore talks to a small server you control, which holds the
token, calls Meta, and receives Meta's webhooks. The phone stores only a revocable **device token** for the relay,
encrypted with an Android Keystore key.

```
ReplyPilot (phone) ──HTTPS + device token──▶ Your relay ──system-user token──▶ graph.facebook.com
                                             ▲
                         Meta webhooks ──────┘  (message status, incoming messages)
```

## Relay contract (v1)

All endpoints are HTTPS only and require `Authorization: Bearer <device token>`. Bodies are JSON.

### `GET /v1/health`
`200 {"ok": true, "phoneNumber": "+234…"}` — used by **Test connection**.

### `POST /v1/messages`
Request:
```json
{ "to": "+2348000000001", "text": "Hello Ada…", "idempotencyKey": "SCHEDULED:abc#r2:+2348000000001:0", "templateName": null }
```
- The relay **must** de-duplicate on `idempotencyKey` (store it for at least 7 days) and return the original
  message id for repeats. ReplyPilot never sends the same key twice on purpose, but this makes retries after a
  network timeout safe.
- If the recipient is outside the 24-hour customer-service window, the relay should send an approved template
  (map `templateName`, or a default) or reject with `422`.

Response: `202 {"id": "wamid.…"}`.

Errors ReplyPilot understands:

| Status | Meaning in ReplyPilot | Retried? |
|---|---|---|
| 400 / 422 | Provider rejected the message | No |
| 401 / 403 | Relay not configured / token revoked | No (manual handoff offered) |
| 404 | Recipient not on WhatsApp | No |
| 429 | Rate limited | Yes, with backoff |
| 5xx / network | Provider or relay error | Yes, with backoff (bounded) |

### `GET /v1/messages/{id}`
`200 {"id": "wamid.…", "status": "sent|delivered|read|failed", "detail": "…"}` — the relay derives this from Meta's
status webhooks. ReplyPilot moves a message to **Delivered** only when this reports `delivered` or `read`.

## Building a relay

Any stack works. Minimum responsibilities:

1. Verify the device token (issue one per phone; allow revocation).
2. Call `POST https://graph.facebook.com/<version>/<phone-number-id>/messages` with the system-user token.
3. Handle Meta's webhook verification and signature check (`X-Hub-Signature-256`), and store message statuses.
4. Enforce your own rate limits and template rules; log without storing message bodies longer than needed.

Incoming-message auto-replies for this channel would run on the relay (from webhooks), not on the phone; that is
listed as planned in **Connected channels**.
