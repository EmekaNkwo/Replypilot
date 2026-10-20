# ReplyPilot

**Intelligent messaging scheduler & auto-responder for Android.**
You define the rules, ReplyPilot carries out the actions it is permitted to, and you stay in control.

ReplyPilot combines scheduled messages, recurring reminders, multi-step follow-up sequences and rule-based
auto-replies in one native Android app (Kotlin, Jetpack Compose, Material 3). Everything runs locally; no backend
is required. It is honest about what each messaging app allows: when a channel can't send unattended, ReplyPilot
hands the message to you with one tap instead of pretending it was sent.

## Highlights

- **Scheduler** — one-off, daily, weekly, monthly (day / nth weekday / last day) and yearly schedules with time
  zones, skip dates, end conditions, quiet-hour behaviour, expiry and bounded retries. Upcoming send times are
  previewed before saving. Duplicate schedules are rejected.
- **Persistent queue** — every send is a row with an idempotency key, claimed atomically before sending, so a
  restart or a second worker can never send it twice. Interrupted sends are flagged for review, not resent.
- **Auto-responder** — a central rule engine with exact / contains / starts-with / keyword / safe-regex matching,
  contact, group, unknown-sender, conversation, working-hours, weekday, date-range, rule-chaining and takeover
  conditions, AND/OR logic, priorities, stop-processing, cooldowns and per-rule limits.
- **Safeguards** — global pause, quiet hours, working hours, opt-out keywords, per-contact / daily / per-channel
  limits, duplicate-notification suppression, own-message echo detection, loop detection, blocked senders and
  phrases, and human takeover.
- **Test lab** — a deterministic rule simulator that explains every check, a queue simulator that replays retries,
  permission failures, quiet hours, double workers and crashes, and a live demo pipeline. Simulations never send.
- **Sequences** — day-offset follow-ups with per-step review, stop-on-reply and opt-out enforcement.
- **Templates & personalization** — reusable templates with `{{variables}}` and `{{variable|fallback}}`; messages
  with unresolved placeholders are never sent.
- **Contacts** — per-channel identities, tags, groups, time zones, opt-out, custom fields, CSV import with preview.
  Recipients can be chosen **inside the channel's app**: tap "Choose a chat in WhatsApp/Telegram/Instagram/Messenger",
  open the conversation, and ReplyPilot comes back with it. They can also be picked from the phone (a contact, or a
  whole contact group) or from ReplyPilot groups — no need to create them in ReplyPilot first. The Outbox can clear
  failed messages in one tap. Open as many chats as you like in one trip, then tap Done; several phone contacts can be
  picked at once too.
- **Attachments** — photos, videos and documents (up to 10 × 64 MB) with the message as caption. WhatsApp sends them
  automatically with Auto-send; other apps open their share screen with the files ready.
- **Auto-send (optional)** — an Accessibility service that presses Send on scheduled WhatsApp messages and records
  Sent or Failed automatically, including group chats. For Telegram, Instagram and Messenger it fills in the message
  when you tap Send manually, and marks it Sent once you send it. See [docs/permissions-and-limits.md](docs/permissions-and-limits.md).
- **Insights** — dashboard, analytics, delivery reports (submitted vs confirmed delivered), searchable activity
  history with diagnostics. Demo/simulated activity is always labelled.
- **Optional AI writing help** — off by default; deterministic mock for testing; Claude with your own API key.
  AI never replies on its own unless you explicitly enable AI-assisted rules *and* AI auto-send.

## Channels and what they can really do

| Channel | Scheduled sends | Auto-replies | Delivery confirmation |
|---|---|---|---|
| Demo (simulated) | ✓ simulated | ✓ simulated | ✓ simulated |
| WhatsApp (on-device) | ✓ with optional Auto-send, else manual handoff | ✓ via notification Reply | — (Sent is confirmed in the chat) |
| Telegram, Instagram, Messenger (on-device) | Assisted: message filled in, you tap Send, marked Sent automatically | ✓ via notification Reply | — |
| SMS (on-device) | Manual handoff | ✓ via notification Reply | — |
| WhatsApp Business Platform (your relay) | ✓ | Planned | ✓ |
| Email | Manual handoff | — | — |

See [docs/permissions-and-limits.md](docs/permissions-and-limits.md) and
[docs/integrations/whatsapp-business-relay.md](docs/integrations/whatsapp-business-relay.md).

## Architecture

Two Gradle modules: `:domain` (pure Kotlin — models, rule engine, scheduler, queue, simulators) and `:app`
(Android — Room, DataStore, adapters, notification listener, WorkManager/AlarmManager, Hilt, Compose).
Details and reliability guarantees: [docs/architecture.md](docs/architecture.md).

```
Scheduled message → Persistent scheduler → Execution queue → Channel adapter → Execution result
Incoming message → Message event → Rule engine → Conditions + safeguards → Response action → Channel adapter → History
```

## Build & run

Requirements: Android Studio (Ladybug or newer) with JDK 17+, Android SDK 36.

```bash
./gradlew :app:assembleDebug          # build the APK
./gradlew :domain:test                # fast JVM tests for the rule engine, scheduler and queue
./gradlew :app:testDebugUnitTest      # Robolectric tests: Room, migrations, adapters, notifications, WorkManager
```

From WSL with Android Studio installed on Windows, `cmd.exe /c gw.bat <tasks>` runs the wrapper with Android
Studio's bundled JDK.

## Try it in demo mode (no messaging account needed)

1. On first launch choose **Explore with demo data** (or Settings → Data & privacy → Load demo data).
2. **Messages → +** — create a scheduled message on the *Demo channel*; set it to repeat weekly and check the
   upcoming-times preview.
3. **Automation → Rules → +** — create a keyword rule (e.g. contains “price”) and use **Test this rule**.
4. **Automation → Test lab → Live demo** — send a simulated incoming message, see the matched rule and the
   generated response, and choose whether the demo channel succeeds, fails or needs a manual send.
5. **Insights → Activity** — inspect the log; every demo event carries a *Simulated* label.
6. Use the global **Pause automation** switch on Home, then **Settings → Data & privacy → Export** to save rules.

Demo mode never sends a real message.

## Privacy

Notification text is matched in memory and discarded; the activity log keeps identifiers and reasons, not message
bodies. Credentials are encrypted with an Android Keystore key. Cloud backup and device transfer are disabled.
You can export your rules and templates, or delete all local data, from Settings → Data & privacy.
