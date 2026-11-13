# Multimodal quick-add capture (photos, voice notes and files)

Design note for the shipped first cut of media capture: forward a photo of an invitation, or send a
voice note, to the Telegram bot and get the same draft card `/add` produces. It records the design,
the decisions and their rationale, and what is deliberately left out.

## Why

The two capture shapes we kept losing to friction:

- **An invitation that arrives as an image** — a school notice, a birthday card, a screenshot of a
  WhatsApp message. Today the user reads the date off the picture and retypes it into `/add`, which
  is exactly the transcription work the assistant should be doing.
- **A voice message** — the "remind me to…" you record walking to the car. Typing it out is the
  whole cost of capturing it.

Both are already how people forward things to *each other* on Telegram; the bot just couldn't
receive them.

## What shipped

Media is captured **inside a quick-add**: send `/add`, then send the photo or the voice note instead
of typing the description. The attachment is the request. From the confirmation card onward the
capture is exactly the existing flow (Save / Adjust / Cancel, the clarify loop, the same validation).

An attachment that arrives with no quick-add in progress originally got a pointer to `/add` and
nothing else; it now opens a capture of its own (D8, superseded by `docs/FREE-TEXT-CAPTURE.md` D7).

One extra step is visible: before the card, the bot echoes **what it read or heard**
(`🖼 From the image: …` / `📄 From the file: …` / `🗣 I heard: …`). A misread date on a blurry invitation is far easier to
spot in that line than in the resulting event.

Accepted: photos, images sent as documents (`image/*`), voice notes, and audio files in a codec we
have a label for. Everything else is declined with a message saying what to do instead.

## Decisions

### D1. The model reads the attachment once; the read-back becomes the request

The vision model is asked for a `source_text` alongside its usual `items` / `clarify` JSON — a short
first-person restatement of what the attachment says. `QuickAddFlow` stores *that* as the capture's
`originalRequest`. For a voice note the read-back is the transcript itself (D4).

Everything downstream — a clarifying question, an "Adjust", a revise — therefore runs on plain text
against the normal task-assistant model. Consequences, all of them wanted:

- **No bytes are retained.** They live only for the duration of the one call that reads them, so the
  15-minute quick-add registry never holds image data.
- **One multimodal call per capture**, not one per round. Vision tokens are the expensive part.
- **The user sees the interpretation** and can correct it in words, which is a better repair loop
  than re-sending the photo.

The fallback order for the request is `source_text` → the message caption → a placeholder that only
ever reaches the model.

### D2. Capability-gated, failing closed

`InputModalitySupport` asks the library's `ModelCapabilityService` whether the model each kind goes
to can take it (from OpenRouter's `architecture`, prefetched at startup): the vision model must
advertise `image` input, and the transcription model must be a speech-to-text model (`transcription`
output). OpenRouter *rejects* an unsupported modality rather than ignoring it, so an ungated attach
is a 400 in the user's face. PDFs need no gate (see "Files").

An unknown model — capability fetch failed, no API key — reports no modalities and capture is
declined with "tell me what it says and I'll capture it". That is the right default: a text path
always exists.

### D3. One model slug per modality

**Revised by GitHub issue #362.** The first cut had one `TASKER_AI_MULTIMODAL_MODEL` for every
attachment, so a model had to take images, audio and files at once: switching to a stronger or
cheaper model that lacked one modality degraded capture. Models split along a different line —
chat models that see, and dedicated speech-to-text models — so the config does too:

- **`tasker.ai.vision-model`** (`TASKER_AI_VISION_MODEL`) for images and PDFs, defaulting to the
  task-assistant model. Both kinds go to it in one call, so an email carrying an image and a PDF is
  still read at once. `TASKER_AI_MULTIMODAL_MODEL` is read as its deprecated old name for now.
- **`tasker.ai.transcription-model`** (`TASKER_AI_TRANSCRIPTION_MODEL`) for voice notes and audio
  files (D4). No fallback.

Both join `configuredModels`, so their capabilities are prefetched at boot and the zero-data-retention
check covers them (OpenRouter lists speech-to-text models in `/model/{slug}` and `/endpoints/zdr`
like chat models). `InputModalitySupport` logs a startup warning when a dedicated slug can't do its
job: a vision model without `image` input, or a transcription model that isn't speech-to-text.

**Images and voice stay dark until a model that handles them is configured.** Until then the bot
politely declines them, which is also the safe state for cost.

### D4. Audio is transcribed, then captured as text

**Revised by GitHub issue #362; this used to send audio to the chat model as an `input_audio` part.**
A voice note goes to OpenRouter's `/audio/transcriptions` (the library's `TranscriptionClient`,
wrapped by `AudioTranscriber`), and the transcript is then drafted from exactly like typed text, by
the task-assistant model. Audio is out of chat-model capability routing entirely, so the text model
can be text-and-vision only. The transcript is the capture's `source_text`: what the user is shown
as "I heard", and its `originalRequest` from there on. The drafting prompt is told the text is a
transcript, so it reads past a misheard word instead of capturing it verbatim. An empty transcript
ends the capture as unreadable.

The transcription call goes through the library's gate and listener like any other, so the AI
opt-out and tier apply and it is recorded (as `conversation_type=transcription`). It is billed by
audio seconds and reports no tokens, so for now it doesn't count against the tier's token budget; a
cost-based budget is where that would change.

Telegram voice notes are OGG/Opus and go out with `format: "ogg"`. Whether a given speech-to-text
model accepts that container is a per-model question (Whisper does); the config knob in D3 is the
deployment-time control.

### D5. We download the bytes rather than passing Telegram's URL

Telegram gives a `file_id`; turning it into content is `getFile` + a download from the file endpoint,
whose URL **embeds the bot token**. Handing that URL to OpenRouter would leak the token, so the bot
downloads and base64s the bytes itself
([FAQ: how to download photo](https://rubenlagus.github.io/TelegramBotsDocumentation/faq.html#how-to-download-photo)).

Guardrails in `TelegramMediaExtractor`: 5 MB per image and per audio clip, checked against Telegram's
declared size *before* the download and again while reading the stream (a declared size can be
missing or wrong). Base64 inflates the payload by a third on top of that. Telegram compresses photos
hard and a voice note is a few hundred KB per minute, so these are guardrails against a pathological
file, not everyday limits.

### D6. Media is a channel concept, not a Telegram one

`ChannelInbound.Media(attachments, caption)` with `InboundAttachment(kind, bytes, mediaType, format)`
sits next to `Text` and `Selection`, so `QuickAddFlow` stays channel-agnostic and a future web
drag-and-drop reuses it whole. Only `TelegramMediaExtractor` knows about `PhotoSize`, `Voice`, and
`getFile`.

### D7. Not during a planning session

The planner takes no attachments: it would need its own reading of one (its own model call, its own
prompt surface). It never sees them either: `TelegramChannel.handleMedia` answers an attachment sent
mid-session with the same redirect `/add` gets there, ahead of the download. The orchestrator's
`ChannelInbound` branches degrade to the caption defensively; they are not a path the channel takes.

That gate used to be a side effect of D8's stricter one. Now that an unprompted attachment does open
a capture, it is explicit — losing it would fork the conversation the planner is in the middle of.

### D8. An attachment must land inside a quick-add, not start one

**Superseded by `docs/FREE-TEXT-CAPTURE.md` D7 — an unprompted attachment now opens a capture.**
Kept for the reasoning, which is what made the deferral worth doing.

The obvious design is the opposite one: a forwarded photo *is* a capture request, so let it open the
flow with no `/add` in front of it. That was the first cut, and it was pulled back on review —
treating every inbound photo as a quick-add locks in an answer to a question that is still open
(what the bot should do with out-of-band messages generally), and it is much easier to add that
entry point later than to take it away from users who got used to it.

So: media is accepted only while a quick-add is in progress. A photo arriving on its own is answered
with "send /add first". The gate sits in `TelegramChannel.handleMedia`, ahead of the download, so an
unsolicited attachment costs no `getFile` round-trip either.

`QuickAddFlow.beginFromMedia` remains the single entry point for capturing *from* an attachment, now
reached only through `handleInbound` — when the flow is waiting for a description, or when media
arrives against an existing draft (which restarts the capture from the attachment). Wiring a new
surface to it, or reinstating the out-of-band entry, is a call-site change and nothing more.

**What changed.** `FREE-TEXT-CAPTURE.md` answers the open question — an unprompted message *is* a
capture — so the deferral expired with it, and the call-site change it predicted is what shipped:
`handleMedia`'s gate is gone and `QuickAddFlow.beginUnpromptedFromMedia` is the second entry point.
An unprompted image captures outright; an unprompted voice note may instead be routed to another
command, because a voice note is as open-ended as typed text. The cost the gate used to save is now
bounded by a per-user capture rate limit instead.

## Files

A file sent to the bot (GitHub issue #321) is captured like a photo, by its declared type or its
name:

- an **image** sent as a file → `IMAGE`, as a photo;
- a **PDF** → `AttachmentKind.DOCUMENT`, a `ContentPart.File` sent to the vision model. It needs no
  modality: OpenRouter passes a PDF to a model with native file input, and parses it for one without
  (GitHub issue #362). With no `file-parser` plugin set, the parser is OpenRouter's default for that
  case, `mistral-ocr`, billed per page on top of the model;
- a **text file** or a **calendar invitation** (`.txt`, `.ics`, `text/calendar`) →
  `AttachmentKind.TEXT`, decoded by `TextAttachments` and put into the prompt as text. It needs no
  modality, so the normal model reads it. The prompt treats an invitation's event as already on the
  user's calendar: capture what they have to do about it, with its date as the deadline
  (`docs/INBOUND-EMAIL.md` has the details, shared with inbound email);
- anything else → "I can't read that kind of file", naming what is read. A GIF (an animation that
  is also a document) is not a file and is ignored as before.

Files are capped at 5 MB, and a text file at 10,000 characters after decoding.

## Privacy

Attachment bytes are **never persisted and never logged** — not to the database, not to disk, not
into a log line. Logs carry the kind, the media type and the byte count only. The bytes leave the
process exactly once, to the AI provider, on the same call path (and under the same user AI opt-out
and tier limits) as every other capture. `source_text` is a normal user string from there on and is
subject to the usual encryption of task content once saved.

## Metrics

`tasker.quickadd.media{kind=image|audio|document|text|mixed, result=captured|failed|unsupported|routed}` —
alongside the existing `tasker.quickadd.outcome`. `unsupported` climbing means the configured model
can't do what users are trying to send. `routed` is an unprompted voice note the model decided
wasn't a capture at all (`docs/FREE-TEXT-CAPTURE.md` D7); it can only appear for audio.

## Deliberately out of scope

- **Office documents** (Word, Excel, ODF). OpenRouter's `file` input is for PDFs, so these would
  need text extraction of their own. Left out until a user asks; they get the "can't read that kind
  of file" reply.
- **Video and video notes.** Provider support varies too much; the library doesn't model it either.
- **Multiple attachments in one capture.** The plumbing takes a list, but Telegram delivers an album
  as separate updates, so each photo currently starts its own capture. Grouping by `media_group_id`
  is the follow-up if anyone forwards multi-page invitations.
- **Media in the planning conversation** (see D7) and on the web UI.

## Key components

| Component | Role |
| --- | --- |
| `ChannelInbound.Media` / `InboundAttachment` (`channel/ConversationChannel.kt`) | Channel-agnostic inbound media. |
| `TelegramMediaExtractor` | `getFile` + download, size caps, mime→codec mapping, photo-size choice, file types. |
| `TextAttachments` (`channel/`) | Recognizes and decodes text files and invitations (shared with inbound email). |
| `TelegramChannel.handleMedia` | Routes a media message: AI opt-out check, then into a running capture or a new one. |
| `QuickAddFlow.beginFromMedia` / `beginUnpromptedFromMedia` | Runs the capture, echoes `source_text`, hands over to the existing card flow. |
| `TaskSuggestionAgent.quickAddDraftFromMedia` | Transcribes audio, builds the `ChatMessage` with images and PDFs, picks the model. |
| `InputModalitySupport` | The capability gate and the model per kind (`ModelCapabilityService`). |
| `AudioTranscriber` | Speech-to-text through `TranscriptionClient`, with the instance's data-retention setting. |
| `prompts/task-suggestion/system-clarify.md` | The `source_text` contract and how to capture from an attachment. |

Requires `openrouter-client` **0.10.0** (`ContentPart.ImageUrl` / `InputAudio`,
`ChatMessage.withAttachments`).
