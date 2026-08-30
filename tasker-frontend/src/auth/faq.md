## What is Backlog.fyi?

A weekly planner. You keep a backlog of everything you've committed to or want to get to, and once
a week an AI assistant helps you turn part of it into an actual plan for the week ahead.

## How is that different from a to-do list?

A to-do list grows forever and never tells you what fits. Backlog.fyi separates the two halves:
capture whenever, without deciding anything, and decide once a week with something that can see the
whole list and push back on an over-full week.

## What does it cost?

Nothing. It's a non-commercial side project in beta, run by one person. There's no paid tier, no
ads, and your data is not sold.

## Is my data private?

Task titles, descriptions, notes and assistant messages are encrypted at rest, and every request is
scoped to the account that made it. AI features send the text you'd expect — the task in question —
to a third-party model provider in order to answer, and those providers have their own policies.
The [Privacy Policy](/privacy) is the full version.

## Do I have to use Telegram?

No. You can sign in with an email magic link and use the web app on its own. Telegram is optional,
and useful mainly for capturing tasks on the go and for the weekly planning conversation.

## Does it connect to my calendar?

Not yet — reading your calendar directly is planned but not built. Today the tasks you commit to in
planning are sent to you as calendar invitations by email, so they land in your calendar as real
time blocks.

## Can I use it from scripts or an AI assistant?

Yes. There's a token-authenticated HTTP API for reading and editing tasks. Create a token in the app
under Settings → Integrations; the [usage guide](https://backlog.fyi/external-api/SKILL.md) and
[OpenAPI description](https://backlog.fyi/external-api/openapi.yaml) describe the rest.

## How do I delete my account?

From Settings → General, at any time. It removes your tasks and account data. The same screen can
export everything first, or write to [{{SUPPORT_EMAIL}}](mailto:{{SUPPORT_EMAIL}}) if anything is unclear.
