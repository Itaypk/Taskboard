# Template task boards

Pre-populated task boards for life events ("relocating to Germany", "going on a long trip") or
recurring projects — public curated templates, possibly a gallery of them, possibly private
user-defined ones. Evaluated 2026-08.

## Conclusion: defer

The feature is coherent and technically tractable, but its value depends almost entirely on an
acquisition channel we haven't committed to. The chain is: templates matter most to a user who
arrives *because* they want to accomplish a specific project → those users arrive via search →
ranking for checklist queries requires a serious SEO/content investment, not just pages existing.
Without that investment, templates degrade into a "start from a template" picker that our current
sign-ups have little use for — a generic new user doesn't want a canned checklist, and users who
do arrive with a project in mind can type their own tasks (which the planner handles fine either
way). So the engineering is downstream of a content/marketing bet, and that bet should be made
deliberately, not as a side effect of an appealing feature.

Revisit if: (a) we decide to invest in content marketing anyway, (b) usage of the "duplicate board"
action (shipped since this doc's original assessment — see below) shows people want reusable task
lists badly enough to justify curated starter content, or (c) a distribution channel appears where
template links can be shared directly (communities, newsletters) without needing to rank.

## The idea

- Public templates: curated boards for common life events, instantiable into a real board with one
  click. Optionally a public gallery — the app's first meaningful public pages beyond the welcome
  page and legal boilerplate, with SEO value and a shareable artifact.
- Private templates: a user saves their own task list as a template to re-instantiate later
  (recurring projects, checklists shared within a team via board sharing).

## Discussion

### Motivations, and how they held up

1. **Shared-checklist utility** ("easy interface for individuals/teams to track such projects").
   Real but mostly already served: board sharing gives any group a good-looking, assignable
   checklist today; a template only saves the initial typing. The genuinely missing piece was a
   **"duplicate board"** action, which covers the recurring-project / private-template need at a
   fraction of the cost — since shipped (`BoardService.duplicateBoard`, any member may copy a
   board's categories/tags/tasks into a fresh board they solely own).
2. **Public visibility / SEO** (gallery pages that can rank and bring users). The strategic
   motivation, and the expensive one. "Moving to Germany checklist" is a competitive query owned
   by content-marketing incumbents; a thin gallery page doesn't rank by existing. Ranking requires
   genuinely good editorial content per template — a writing commitment, not an engineering
   feature. There's also an audience-fit question: checklist-seekers aren't necessarily
   weekly-planner users, though life-event projects are at least planner-shaped (deadlines,
   durations, sequencing), so the persona mismatch is a risk rather than a disqualifier.
3. **Onboarding / planner cold-start** (a new user with an empty backlog has nothing for the
   flagship planning loop to chew on; a template fills it). Considered and rejected as a
   stand-alone justification: it only works for users who arrive with a specific project intent,
   and those arrive via the search channel above — circular. For a generic sign-up there's no
   good candidate template; pre-filling their board with someone else's checklist is noise, not
   activation. Cold-start is better served by the existing demo/sandbox flow.

### Technical shape (if/when built)

- **Templates cannot be ordinary boards.** Board content is encrypted under per-board DEKs
  (`docs/BOARD-MODEL.md`); public content is plaintext by definition. The clean model: a template
  is a static, read-only definition (title, tasks with priorities/durations/tags, category
  structure), and *instantiation copies it into a normal encrypted board* owned by the user. A
  parallel content type — not a `public` flag on `board`, which would tangle the encryption and
  authorization stories.
- **Curated-only content.** Templates ship as repo resources (or an admin-managed table later).
  User-*published* templates are out of scope indefinitely: they add moderation burden and erode
  the encryption promise ("we can't read your tasks — except the ones you publish"), with a
  consent surface that isn't worth it for a solo project.
- **Public gallery costs**, beyond the pages themselves: the first unauthenticated content
  endpoints on a currently fully session-gated surface (new `permitAll` paths on the session
  chain, or static pre-rendering); SEO prerendering work (note the `index.html` crawler fallback
  is already stale — see issue #256); per-template OG/meta assets. None of it hard, all of it
  carry-forward surface area.
- **Staging, if the bet is made**: gallery + instantiation land together (the gallery is the
  acquisition channel, instantiation is the conversion), with a small curated set whose editorial
  quality gets the attention. A picker-only Phase 1 without the gallery was considered and dropped
  — without the search channel it has no audience (see the cold-start point above).
- Mascot-per-template is a natural cosmetic tie-in (`tools/README.md` workflow).
