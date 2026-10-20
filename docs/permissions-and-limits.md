# Permissions and honest limits

ReplyPilot requests nothing at install. Each capability is explained and requested when the feature that needs it
is turned on.

| Capability | Why | Needed for | Optional? |
|---|---|---|---|
| Notification access | Read new-message notifications from apps **you enable** and use their Reply button | Auto-replies on WhatsApp, Telegram, Instagram, Messenger, SMS | Yes — only for those auto-replies |
| Post notifications (Android 13+) | Tell you when something needs approval or a manual send | Approvals, handoff reminders | Yes |
| Exact alarms (Android 12+) | Run at the exact minute | “Precise timing” | Yes |
| Battery optimisation exemption | Some devices kill background work aggressively | More dependable background runs | Yes — ReplyPilot only opens the settings page |
| Auto-send (Accessibility service) | Sends scheduled WhatsApp messages; reads the open chat's name when you choose a recipient in WhatsApp, Telegram, Instagram or Messenger; fills in the message for "Send manually" and watches for your send | Unattended WhatsApp sends, choosing recipients inside the apps, automatic Sent status for assisted sends | Yes — off by default, shown with a full disclosure first; limited to WhatsApp, WhatsApp Business, Telegram, Instagram and Messenger |
| Open apps in the background (Xiaomi, Redmi, POCO only) | HyperOS/MIUI blocks background apps from opening screens | Auto-send while you're not using ReplyPilot | Yes — only needed with Auto-send on these phones |
| Contacts | Lists your phone's contact groups (labels) | Choosing a phone contact group as recipients | Yes — asked the first time you open the group picker; picking a single contact uses the system picker and needs no permission |
| Wake lock | Turns the screen on briefly while an auto-send runs | Auto-send with the screen off (phone must be unlocked) | Normal permission, no prompt |

ReplyPilot does **not** request SMS permissions or the default-SMS role. SMS and email are sent through your own apps
(manual handoff).

### Auto-send safeguards

- It acts only when the queue sends a scheduled WhatsApp message, or when you start "Choose a chat" or "Send manually".
  It never acts on its own.
- It reads only the windows of the five declared packages, and only to find the chat title, the message box and the
  Send button.
- **Choose a chat** (WhatsApp, Telegram, Instagram, Messenger): ReplyPilot opens the app; when you open a conversation
  it reads that chat's title and returns, and you confirm or correct the name before it's added. Picked chats are
  found **by name** at send time, so the name must match what the app shows. WhatsApp and Instagram are read by
  stable view IDs, Messenger by its labelled header, and Telegram by a layout heuristic (the confirmation step catches
  misreads). Pressing Back into ReplyPilot cancels picking.
- **Send manually** for a picked Telegram, Instagram or Messenger chat: ReplyPilot opens the app; once that exact chat
  is open it fills in the message, and you tap Send. It's marked **Sent** only when the box has emptied **and** the
  message now appears one more time in the chat, so clearing the text doesn't count. If ReplyPilot can't see it,
  you can still use "Mark as sent".
- **Choose chats** collects as many chats as you open: a small "Done · N chosen" button floats at the right edge of the
  app (an accessibility overlay — no extra permission). Tap Done (or just come back to ReplyPilot) to finish; a chat
  that is open when you tap Done is included.
- Chats you can't write in (admin-only groups, channels, groups you've left) aren't added; a message says why.
- WhatsApp shortens long names in the chat header ("Ezenwanyi & Her Billionaires…"). Such names are matched by their
  start when sending; if several chats share that start, ReplyPilot stops instead of guessing — edit the name.
- Only WhatsApp sends unattended. Telegram, Instagram and Messenger messages always need your tap.

### Attachments

- Picked files are **copied into ReplyPilot's private storage** (up to 10 files, 64 MB each), so a scheduled send
  doesn't depend on the original. Copies nothing uses any more are deleted (after a 24-hour grace period).
- Files are handed to apps through a FileProvider: read-only, per share, and only from ReplyPilot's attachment folder.
- **WhatsApp with Auto-send**: each file goes in its own share (WhatsApp silently drops files from a share that mixes
  photos and documents); the message text is the caption of the first file, and is typed into a document's own
  caption box. For a picked chat or group, ReplyPilot checks WhatsApp's "Send to…" footer names exactly that chat
  before pressing Send. It's **Sent** only when WhatsApp is back on that conversation with no share screen left; if a
  later file fails after earlier ones went out, the message is **Needs attention** (never re-sent automatically).
- **Everything else** (WhatsApp without Auto-send, Telegram, Instagram, Messenger, SMS/MMS, Email): "Send manually"
  opens the app's share screen with the files (addressed where the app allows: WhatsApp by number, SMS, email); the
  text is also copied in case the app drops the caption. Then tap "Mark as sent".
- If an attached file is missing at send time, the message **Fails** ("attach again") instead of sending text alone.
- The WhatsApp Business Platform relay doesn't send attachments yet.
- A send is **Sent** only when the text has left the input box after pressing Send. If that can't be confirmed, the
  message is **Needs attention** ("check the chat before sending again") — it is never retried blindly.
- Missing groups and numbers that aren't on WhatsApp are **Failed** with the reason, without retries.
- A locked phone defers the send until you unlock it (without using up retries); unlocking triggers the queue.
- If Android or the phone's vendor force-stops ReplyPilot, Android also switches the Accessibility service off;
  ReplyPilot then shows Auto-send as off and falls back to one-tap handoffs.

## What each channel can really do

| Channel | Scheduled sends | Auto-replies | Delivery confirmation |
|---|---|---|---|
| Demo | Simulated | Simulated | Simulated |
| WhatsApp (on-device) | Automatic with Auto-send (phone unlocked), otherwise manual handoff; individual chats and groups | Yes, while the conversation's notification is present | No (Sent = confirmed it left the input box) |
| Telegram / Instagram / Messenger (on-device) | Assisted: at the scheduled time you tap Send manually, ReplyPilot fills the picked chat and marks it Sent when you send | Yes, while the conversation's notification is present | No (Sent = seen in the chat) |
| SMS (on-device) | Manual handoff at the scheduled time | Yes, while the conversation's notification is present | No |
| WhatsApp Business Platform (relay) | Yes (Meta rules apply) | Planned (relay webhooks) | Yes |
| Email | Manual handoff | No | No |

## Timing

Android may delay background work to save battery. Without precise timing, a message can run several minutes late;
with precise timing (exact alarms) it runs on the minute when the system allows. Every message has an expiry so
that, if the phone was off, it is marked *Expired* rather than sent hours late.
