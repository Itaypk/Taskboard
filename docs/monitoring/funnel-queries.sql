-- Activation funnel queries (docs/LAUNCH-PLAN.md, "Measuring").
--
-- These are deliberately NOT Prometheus metrics. Two reasons:
--
--   1. The numbers are already in the schema, and can be computed retroactively. `users.created_at`
--      and `users.claimed` give registration; `users.engaged_at` (changeset 004) is stamped on the
--      first non-tutorial write, which is exactly "created a first real task"; `planning_session`
--      carries `user_id`, `status` and `started_at`. Adding counters would only have started
--      collecting from the day they shipped, and would have told us nothing we can't ask now.
--   2. The retention number is a per-user cohort question ("did this person come back at least five
--      days later"), which a counter cannot express at all. The `UsageMetrics` gauges are
--      point-in-time snapshots of the same tables and answer a different question.
--
-- Run against prod Postgres. Nothing here decrypts anything: every column used is a timestamp, a
-- flag or an id, so these are safe to run and safe to paste into notes.
--
-- Not answered here: acquisition source. Nothing records where a sign-up came from, and that is a
-- deliberate call (no paid ads, and UTM tails look bad in aggregators and social embeds). With one
-- launch channel per week, attribute by correlating sign-up timestamps against when each post went
-- out, plus the nginx Referer log.

-- 1. Sign-ups per week, split by whether the account was ever claimed.
--    Unclaimed rows undercount: UnclaimedAccountCleanupService deletes never-engaged accounts after
--    2 days and engaged-but-unclaimed ones after 14, so recent weeks read higher than older ones.
SELECT date_trunc('week', created_at) AS week,
       count(*)                                  AS signups,
       count(*) FILTER (WHERE claimed)           AS claimed,
       count(*) FILTER (WHERE NOT claimed)       AS still_unclaimed
FROM users
GROUP BY week
ORDER BY week DESC;

-- 2. Did the board get understood? Share of accounts that made a first real (non-tutorial) write.
SELECT date_trunc('week', created_at)                 AS cohort_week,
       count(*)                                       AS signups,
       count(engaged_at)                              AS reached_first_task,
       round(100.0 * count(engaged_at) / nullif(count(*), 0), 1) AS pct
FROM users
GROUP BY cohort_week
ORDER BY cohort_week DESC;

-- 3. Is the core loop reachable? Share of accounts that completed a first planning session.
WITH first_session AS (
    SELECT user_id, min(started_at) AS first_started
    FROM planning_session
    WHERE status = 'COMPLETED'
    GROUP BY user_id
)
SELECT date_trunc('week', u.created_at)                  AS cohort_week,
       count(*)                                          AS signups,
       count(f.user_id)                                  AS reached_first_plan,
       round(100.0 * count(f.user_id) / nullif(count(*), 0), 1) AS pct
FROM users u
LEFT JOIN first_session f ON f.user_id = u.id
GROUP BY cohort_week
ORDER BY cohort_week DESC;

-- 4. THE RETENTION NUMBER. Users with a second completed planning session at least 5 days after
--    their first — i.e. they came back in a later week, not a same-evening retry.
--    docs/LAUNCH-PLAN.md's phase 1 exit criterion is five of these.
WITH completed AS (
    SELECT user_id, started_at,
           min(started_at) OVER (PARTITION BY user_id) AS first_started
    FROM planning_session
    WHERE status = 'COMPLETED'
)
SELECT count(DISTINCT user_id) AS users_with_second_session
FROM completed
WHERE started_at >= first_started + interval '5 days';

-- 4b. The same users, listed, so you can go and talk to them.
WITH completed AS (
    SELECT user_id, started_at,
           min(started_at) OVER (PARTITION BY user_id) AS first_started
    FROM planning_session
    WHERE status = 'COMPLETED'
)
SELECT user_id,
       min(first_started)                AS first_session,
       min(started_at)                   AS second_session,
       count(*)                          AS later_sessions
FROM completed
WHERE started_at >= first_started + interval '5 days'
GROUP BY user_id
ORDER BY second_session DESC;

-- 5. Weekly AI spend proxy. OpenRouter's own dashboard is the billing source of truth; this is the
--    per-user distribution behind it, which is what tells you whether one account is the whole bill.
SELECT date_trunc('week', started_at) AS week,
       count(*)                       AS sessions,
       count(DISTINCT user_id)        AS distinct_users
FROM planning_session
GROUP BY week
ORDER BY week DESC;
