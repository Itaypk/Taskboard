## What is Backlog.fyi?

A weekly planner. You keep a backlog of everything you've committed to or want to get to, and once
a week an AI assistant helps you turn part of it into an actual plan for the week ahead.

## How is that different from a to-do list?

A to-do list grows forever and never tells you what fits. Backlog.fyi separates the two halves:
capture whenever, without deciding anything, and decide once a week with something that can see the
whole list and push back on an over-full week.

## Who is it for?

People running their own life. Backlog.fyi isn't trying to replace Trello, Jira or Monday — there 
are no sprints, no workflows, no story points. I use it for personal things, and for the small work 
items that fall through the cracks of a real project tracker: return a call, review a document, 
renew the thing before it expires.

## What's the fastest way to add something?

Send it to the Telegram bot. Anything you forward or type — a message from someone else, a photo of
a school notice, a voice note recorded on the way to the car — comes back as a draft you confirm
with one tap. No command to remember, no app to open. If what you sent is a fixed appointment
rather than a task, it becomes a calendar event and the invitation arrives by email.

## Do I have to use Telegram?

No. You can sign in with an email magic link and use the web app on its own. Telegram is optional,
and useful mainly for capturing tasks on the go and for the weekly planning conversation.

## Does it connect to my calendar?

Not yet — reading your calendar directly is planned but not built. Today the tasks you commit to in
planning are sent to you as calendar invitations by email, so they land in your calendar as real
time blocks.

## Can I share tasks with my family?

Yes. Tasks belong to a board, and a board can have more than one member — invite someone by email
and you both see that board's tasks. You can keep several boards, so the shared shopping and school
runs live on one while your own list stays private on another. A board with a single member is
private; that's the whole difference.

## Can I use it from scripts or an AI assistant?

Yes. There's a token-authenticated HTTP API for reading and editing tasks. Create a token in the app
under Settings → Integrations; the [usage guide](https://backlog.fyi/external-api/SKILL.md) and
[OpenAPI description](https://backlog.fyi/external-api/openapi.yaml) describe the rest.

## What does it cost?

Nothing. It's a solo, non-commercial side project in beta — no ads, and your data is never sold
or used to train models. The AI features do cost real money to run, so they come with usage limits;
if that bill ever outgrows a hobby budget, I'd sooner ask heavy users to chip in than change that.

## Is my data private?

Your email address, task titles, descriptions, notes and assistant messages are encrypted at rest,
and every request is scoped to the account that made it. AI features send the text you'd expect —
the task in question — to a third-party model provider so it can answer, and those providers have
their own policies. The [Privacy Policy](/privacy) is the full version.

## How do I delete my account?

From Settings → General, at any time. It removes your tasks and account data, and the same screen
can export everything first if you want a copy. If anything is unclear, write to
[{{SUPPORT_EMAIL}}](mailto:{{SUPPORT_EMAIL}}).

## How do I report abuse?

Write to [{{ABUSE_EMAIL}}](mailto:{{ABUSE_EMAIL}}).
