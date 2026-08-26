# Multimodal quick-add capture (photos and voice notes)

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

Sending a photo or a voice note to the bot **starts a quick-add capture directly** — no `/add` in
front of it. The attachment is the request. From the confirmation card onward the capture is exactly
the existing flow (Save / Adjust / Cancel, the clarify loop, the same validation).

One extra step is visible: before the card, the bot echoes **what it read or heard**
(`🖼 From the image: …` / `🗣 I heard: …`). A misread date on a blurry invitation is far easier to
spot in that line than in the resulting event.

Accepted: photos, images sent as documents (`image/*`), voice notes, and audio files in a codec we
have a label for. Everything else is declined with a message saying what to do instead.

## Decisions

### D1. The model reads the attachment once; the read-back becomes the request

The capture model is asked for a `source_text` alongside its usual `items` / `clarify` JSON — a short
first-person restatement of what the attachment says. `QuickAddFlow` stores *that* as the capture's
`originalRequest`.

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

`InputModalitySupport` asks the library's `ModelCapabilityService` whether the capture model
advertises the `image` / `audio` input modality (from OpenRouter's `architecture.input_modalities`,
prefetched at startup). OpenRouter *rejects* an unsupported modality rather than ignoring it, so an
ungated attach is a 400 in the user's face.

An unknown model — capability fetch failed, no API key — reports no modalities and capture is
declined with "tell me what it says and I'll capture it". That is the right default: a text path
always exists.

### D3. A separate model slug for media capture

`tasker.ai.multimodal-model` (`TASKER_AI_MULTIMODAL_MODEL`), defaulting to the task-assistant model.
The current default assistant model is a free text-only one; pinning vision to its own slug means
enabling this feature is a config change, and the (much pricier) vision model isn't dragged into
every text capture. It joins `configuredModels`, so its capabilities are prefetched at boot.

**Nothing is enabled until that slug is set to a model with the modality.** Until then the bot
politely declines media, which is also the safe state for cost.

### D4. Audio goes to the model as audio, not through a transcription step

OpenRouter has a separate `/audio/transcriptions` endpoint (Whisper and friends), which would give a
transcript we could feed to any text model. We didn't use it: our `openrouter-client` library wraps
chat and image generation only, so it would mean a new library surface — and the chat `input_audio`
part gets us the transcript anyway, via `source_text`, in a single call that also does the drafting.

Telegram voice notes are OGG/Opus and go out with `format: "ogg"`. Whether a given model accepts that
container is a per-model question; a model that doesn't will reject it, which is why the config knob
in D3 is the deployment-time control. If we later want codec independence (or cheaper audio), the
transcription endpoint is the escape hatch — add it to the library and swap the audio branch.

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

Media that arrives mid-planning is declined with a note to say it in a message. The planner would
need its own reading of the attachment (its own model call, its own prompt surface), and silently
dropping the photo is worse than saying so. The orchestrator's `ChannelInbound` branches degrade to
the caption defensively; they are not a path the channel takes.

## Privacy

Attachment bytes are **never persisted and never logged** — not to the database, not to disk, not
into a log line. Logs carry the kind, the media type and the byte count only. The bytes leave the
process exactly once, to the AI provider, on the same call path (and under the same user AI opt-out
and tier limits) as every other capture. `source_text` is a normal user string from there on and is
subject to the usual encryption of task content once saved.

## Metrics

`tasker.quickadd.media{kind=image|audio|mixed, result=captured|failed|unsupported}` — alongside the
existing `tasker.quickadd.outcome`. `unsupported` climbing means the configured model can't do what
users are trying to send.

## Deliberately out of scope

- **PDFs.** The library models the `file` content part and OpenRouter will OCR it, but a PDF
  invitation is rare enough on Telegram that it wasn't worth the extra modality gate. Adding it is a
  new `AttachmentKind` plus a `ContentPart.File` branch.
- **Video and video notes.** Provider support varies too much; the library doesn't model it either.
- **Multiple attachments in one capture.** The plumbing takes a list, but Telegram delivers an album
  as separate updates, so each photo currently starts its own capture. Grouping by `media_group_id`
  is the follow-up if anyone forwards multi-page invitations.
- **Media in the planning conversation** (see D7) and on the web UI.

## Key components

| Component | Role |
| --- | --- |
| `ChannelInbound.Media` / `InboundAttachment` (`channel/ConversationChannel.kt`) | Channel-agnostic inbound media. |
| `TelegramMediaExtractor` | `getFile` + download, size caps, mime→codec mapping, photo-size choice. |
| `TelegramChannel` | Routes a media message: AI opt-out check, planning-session refusal, then capture. |
| `QuickAddFlow.beginFromMedia` | Runs the capture, echoes `source_text`, hands over to the existing card flow. |
| `TaskSuggestionAgent.quickAddDraftFromMedia` | Builds the multimodal `ChatMessage`, picks the capture model. |
| `InputModalitySupport` | The capability gate (`ModelCapabilityService.inputModalities`). |
| `prompts/task-suggestion/system-clarify.md` | The `source_text` contract and how to capture from an attachment. |

Requires `openrouter-client` **0.10.0** (`ContentPart.ImageUrl` / `InputAudio`,
`ChatMessage.withAttachments`).
