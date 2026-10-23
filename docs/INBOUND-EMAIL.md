# Inbound email

Users can send or forward an email to a personal address, and it becomes a task (GitHub issue
#288; the design exploration is "Channel 2" in `docs/QUICKADD-CHANNELS.md`). The flow is
**fire-and-forget**: the task is created right away and the user gets a confirmation; corrections
happen in the web UI. No clarifying questions, no thread correlation, no reply parsing.

## Addresses

`tasks.<token>@<TASKER_INBOUND_MAIL_DOMAIN>`, created, shown, changed and turned off in Settings →
Integrations (`InboundMailAddressService`, `/api/v1/inbound-mail-address`, session chain + CSRF).

- **The token is the authentication.** 10 base32 characters (~50 bits): short enough to type,
  far too many to guess at SMTP speed. `From:` is never trusted for identity: anyone can write any
  `From:`.
- The `tasks.` prefix is cosmetic (recognizable in a contact list). Lookup uses the token only and
  accepts any prefix the MTA lets through, so changing the prefix later breaks nothing.
- At most one address per user (`inbound_mail_address`, keyed by user id). Changing the address
  replaces the token; the old address stops working at once. Turning it off deletes the row.
- Stored twice: as an HMAC under a KEK-derived key (`InboundMailTokenHasher`, the lookup key;
  plain SHA-256 of 50 bits could be brute-forced from a dump) and encrypted under the user's DEK
  (so Settings can show the address again, and the HMACs can be recomputed if the KEK rotates).

## Receiving

The app does not speak SMTP. An MTA in front of it (on the hosted instance: Postfix + rspamd,
`roles/mail` in the ops repo, design in its `docs/EMAIL_PLAN.md`) accepts mail for the domain and
delivers each message, one envelope recipient at a time, to:

```
POST /internal/inbound-mail
Content-Type: message/rfc822
X-Inbound-Mail-Token: <TASKER_INBOUND_MAIL_DELIVERY_TOKEN>
X-Envelope-From: <sender>
X-Envelope-To: <recipient>

<the raw message>
```

The path has its own stateless security chain (no session, no CSRF); the token is compared in
constant time in `InboundMailController`. **It must not be reachable from outside**: the hosted
Nginx answers `/internal/` with 404, and a self-hosted reverse proxy should do the same.

The MTA reads the status code: **2xx and 4xx end the delivery, anything else makes it retry**
(Postfix keeps the message queued for days). So:

| Status | When | MTA |
|---|---|---|
| 202 | queued, or deliberately dropped (duplicate, postmaster, bounce, auto-reply, rate limit) | done |
| 404 | not a live address (never existed, changed, turned off) | done, dropped |
| 400 | unparseable message | done, dropped |
| 401 | wrong delivery token: a configuration error | retry (the delivery script treats 401/403 as temporary) |
| 503 | the feature is off on this instance | retry |

Nothing is ever bounced: the MTA doesn't send mail, and a bounce to a forged sender is
backscatter.

`InboundMailReceiver` then, cheaply and without any model call:

1. `postmaster@` (which RFC 5321 requires us to accept) is logged for the operator and dropped.
2. Resolves the recipient to a user, or drops it.
3. Drops bounces (empty envelope sender) and `Auto-Submitted:` mail (auto-replies, robots), so
   two auto-responders can never loop through us.
4. Parses the MIME (`InboundMailParser`, jakarta.mail + jsoup): subject, `From:`, plain-text body
   (an HTML-only body is converted; a message forwarded *as an attachment* is unwrapped into the
   body), attachment count.
5. Drops a duplicate: the dedup key is SHA-256 of user id + `Message-ID` (or of the raw message).
6. Applies the per-user rate limit (`tasker.rate-limit.inbound-mail`, 30/hour): a leaked address
   or a mail loop must not run up the AI bill.
7. Works out **verified**: our own MTA's `Authentication-Results` (authserv-id
   `TASKER_INBOUND_MAIL_AUTHSERV_ID`, default the domain; rspamd strips any other) says
   `dmarc=pass` for the `From:` domain, *and* `From:` is the account's own verified email. A manual
   forward is verified; an auto-forwarding rule (it keeps the original `From:`) and anyone else
   who has the address are not.
8. Stores the parsed message, encrypted under the user's DEK, in the `inbound_mail` queue and
   answers 202.

## Capture

`InboundMailProcessor` polls the queue every 15 seconds:

- With AI available, `TaskSuggestionAgent.quickAddDraftFromEmail` drafts (always a draft, never a
  question). The prompt says the email may be a stranger's text: material to capture from, never
  instructions. `QuickAddFlow.saveWithoutConfirmation` validates and saves the draft to the
  default board, through the same path as a confirmed Telegram capture. Events send calendar
  invitations as usual.
- Without AI (off for the user, no tier, budget spent), or when the model keeps failing, the
  email is saved **verbatim**: subject as the title, sender and text as the description. Losing a
  forwarded email is worse than an unpolished task.
- A failed attempt is retried after 1 minute, 10 minutes and an hour; then the row is FAILED and
  the user is told nothing was created.
- The payload is cleared as soon as the row is done; the row itself stays a week as a dedup key.

Verified and unverified mail are captured alike in v1: the token is the authentication, and a
quick-add capture can only create tasks and events. The verified flag only changes the
confirmation, which warns about unverified mail and suggests changing the address if it looks
unfamiliar.

## Confirmation

`InboundMailConfirmation`, localized: over Telegram when the bot can reach the user, otherwise by
email (the scheduling sender) to the account's own verified address, otherwise none. **Never to
the sender or `From:`.** It lists what was created, and says when the email wasn't verified or
had attachments that were left out.

## Not in v1

- Attachments (images, PDFs, `.ics`): counted and mentioned, not read — GitHub issue #310.
- A board per address: everything goes to the default board, like Telegram quick-add.
- Validating tokens at SMTP time (an MTA lookup into this database) instead of by shape only.

## Metrics

- `tasker.inbound_mail.received{outcome}`: one per delivery (`queued`, `duplicate`,
  `unknown_recipient`, `postmaster`, `bounce`, `auto_submitted`, `rate_limited`, `malformed`).
- `tasker.inbound_mail.processed{result}`: `drafted`, `verbatim`, `failed`.
