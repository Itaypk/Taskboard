## High Level

An AI-powered weekly planner that helps you actually get tasks done - not just write them down. The core loop: you maintain a task backlog, the planner helps you schedule what to tackle each week based on your calendar and priorities, and agreed tasks land on your calendar as time blocks.

### Motivation

- Tasks get written down somewhere and buried - no system surfaces them at the right time
- Blocking time for tasks works, but only when you actually do the planning and account for what's already on your calendar
- Without a regular planning habit, tasks drift indefinitely

### Core Loop

1. **Capture** - add tasks via the web UI with enough context for the planner to work with
2. **Plan** - weekly planning session via Telegram: the AI proposes a week plan based on your backlog, calendar availability, and priorities. You negotiate in natural language until you agree on a plan
3. **Schedule** - agreed tasks get placed on your Google Calendar as time blocks
4. **Review** - mark tasks done, reschedule, or let them roll into next week's planning

### Features

#### Task Management
- Task fields: title, description, URL, priority, deadline, estimated duration, status, tags
  - All optional except title
- Tags carry context that informs planning (e.g. "home-improvement" = at home, "in-person" = requires physical presence, "deep-work" = needs a long uninterrupted block)
- Responsive web UI for managing the full backlog - view, filter, sort, bulk edit

#### AI Weekly Planner
- Scheduled weekly planning session via Telegram, driven by natural language conversation
- Reads your Google Calendar to see existing commitments and find available slots
- Proposes which tasks to tackle this week and when, based on priority, deadlines, estimated duration, tag context, and free time
- Iterative — you can push back, reprioritize, swap tasks until you agree on a plan
- Full conversation history is sent with each turn (conversations are short enough for this to be practical)
- Creates Google Calendar events for the agreed plan

#### Daily Digest
- An optional daily Telegram message (on by default) at a time and on days the user picks: today's planned time blocks, plus tasks that aren't in the plan and are due today or overdue
- Due tasks can be muted from the digest ("next week" / "don't remind me again"); a mute lifts when the deadline changes
- Design in `docs/DAILY-DIGEST.md`

#### Integrations
- **Google Calendar** (OAuth): read availability, write time blocks. "Test" app — no verification needed for personal use
- **Telegram Bot**: planning conversations
- **External API** (`/api/external/v1`): token-authenticated task CRUD for scripts, automations
  and AI assistants. Contract at `/external-api/openapi.yaml`, agent skill at
  `/external-api/SKILL.md`; design note in `docs/EXTERNAL-API.md`

#### Per-User Context
- A personal context block (free-text facts) stored per user — e.g. "I prefer deep work in the morning", "Tuesdays I leave early for pickup"
- Available for edit through the web UI settings

### Non-Goals (for v1)
- File attachments on tasks — URLs are sufficient for now
- Offline support / PWA — a responsive web app is sufficient
- Real-time calendar sync — one-directional: read availability, write events at planning time
- Advanced collaboration features
- Mobile app — responsive web covers this
