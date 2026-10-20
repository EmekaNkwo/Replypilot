# ReplyPilot architecture

## Modules

| Module | Kind | Contains |
|---|---|---|
| `:domain` | Pure Kotlin/JVM | Models, rule engine, scheduler math, queue processor, use cases, simulators, ports (interfaces). No Android imports — enforced by the module type. |
| `:app` | Android | Room/DataStore persistence, channel adapters, notification listener, WorkManager/AlarmManager wiring, Hilt DI, Compose UI. |

## Package map (`:app`)

```
core/        database, datastore, security (Keystore credentials), notifications (listener, reply registry, alerts),
             scheduling (wake scheduler, worker, receivers), permissions, handoff, assistant, design system
data/        Room-backed repository implementations, mappers, data services (export/import, demo seeder)
integrations/ channel adapters: device (notification-based), demo, email, whatsapp (Business Platform relay)
features/    dashboard, scheduler, autoresponder, rules, sequences, templates, contacts, analytics, settings, onboarding
navigation/  routes and the nav host
di/          Hilt modules — the only place domain services meet Android
```

## The two pipelines share one queue

```
Scheduled message ─▶ ScheduleService ─▶ ScheduleMaterializer ─▶ executions table (idempotency key) ─┐
Sequence enroll   ─▶ SequenceService ─────────────────────────▶ executions table ───────────────────┤
Notification ─▶ MessageNotificationListener ─▶ IncomingMessageHandler ─▶ RuleEngine ─▶ executions ──┤
                                                                                                    ▼
                         WakeScheduler (WorkManager / exact alarm) ─▶ QueueWorker ─▶ QueueProcessor
                                                                                       │ claim (CAS)
                                                                                       ▼
                                                                    ChannelAdapter.send ─▶ SendOutcome
                                                                                       ▼
                                                              executions status + activity log
```

- **Nothing sends outside the queue.** Auto-replies are queued too, so they get the same safeguards, retries,
  audit trail and pause behaviour as scheduled messages.
- **Recurring schedules are rules, not timers.** A schedule is a persistent definition; the materializer turns the
  next 48 hours of occurrences into rows. There is exactly one wake-up armed at a time (the earliest due row),
  plus a 15-minute periodic safety net.

## Reliability guarantees

| Risk | Guard |
|---|---|
| Re-materialization or two workers create duplicate rows | Unique `idempotencyKey` = source + schedule revision + recipient + occurrence |
| Two workers send the same row | Atomic `UPDATE … WHERE status = :expected` claim before sending |
| Process dies mid-send | Rows stuck in `EXECUTING` > 5 min become *Needs attention* (`INTERRUPTED`), never resent automatically |
| Editing a schedule | Revision bump; old pending rows cancelled; new rows keyed on the new revision |
| Device reboot / app update | `SystemEventReceiver` re-arms; app start runs the queue |
| Time-zone change | Fixed-zone schedules keep their instants; follow-device schedules are re-planned |
| Daylight saving | `ZonedDateTime.of` semantics: gap times move forward, overlap times send once |
| Transient failures | Bounded exponential backoff (1, 2, 4… min, max 1 h); permanent codes never retry |
| Late sends | Each row has an expiry; late rows expire instead of sending hours later |
| Phone locked during an auto-send | `DEVICE_LOCKED` defers the row without spending an attempt; unlocking re-runs the queue |
| Auto-send can't confirm a send | Reported as *Needs attention*, never retried automatically |

## Message lifecycle

`Draft → Scheduled → (Due) → Executing → Submitted → Delivered`, with `Retry scheduled`, `Paused`, `Cancelled`,
`Expired`, `Skipped`, `Failed`, `Needs attention` and `Awaiting review`. **Submitted** means the channel accepted
the message; **Delivered** is set only when an adapter with `DELIVERY_STATUS` confirms it. Transitions are checked by
`MessageStateMachine`.

## Rule evaluation order

1. Global safeguards (pause, own message/echo, duplicate event, group policy, blocked sender, opt-out keyword,
   opted-out contact, blocked phrase, human takeover, loop detection, quiet hours, daily/channel/contact limits).
   Rules are still traced so the simulator can show what *would* have matched.
2. Enabled rules for the channel, highest priority first, ties by name.
3. For each matching rule: cooldown, rule daily limit, template rendering (unresolved placeholders block sending),
   channel capability.
4. A matching rule with *stop processing* ends evaluation. At most 3 replies per incoming message.

The engine is a pure function of `(message, rules, RuleContext)`, so the simulator and tests are deterministic.

## Persistence

Room (version 1, schema exported to `app/schemas`) holds schedules, executions, rules, templates, contacts (+ an
identity index per channel), sequences, enrollments, activity, reply records, fingerprints and conversation state.
Nested structures are JSON columns so optional fields can evolve without a migration; query fields are real columns.
Every schema change must add an explicit `Migration`; destructive fallback is never enabled.
DataStore holds automation settings (one atomic JSON value) and app preferences, with a corruption handler that
resets to defaults. Credentials are AES-GCM encrypted with an Android Keystore key.

## Privacy

- Notification text is matched in memory and discarded. Duplicate/loop detection uses SHA-256 fingerprints.
- The activity log stores identifiers, results and reasons — never message bodies.
- Outgoing bodies are stored only on the execution row that sends them.
- Backups and device transfer are disabled; use Export for rules/templates/sequences.
