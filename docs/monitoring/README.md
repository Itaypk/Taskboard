# Monitoring

Two things live here: the Grafana dashboard below (operational health, scraped from
Prometheus) and `funnel-queries.sql` (activation and retention, read straight from Postgres).
They answer different questions — the dashboard shows point-in-time totals, while the funnel
is per-user cohort analysis a counter cannot express. See the header of the SQL file for why
the launch funnel is deliberately not instrumented as metrics.

## Backlog.fyi — Application Overview dashboard

`backlog-overview-dashboard.json` is a Grafana dashboard giving a shallow, broad,
at-a-glance picture of the running app. It complements (does not replace) the default
Spring Boot dashboard, which already covers the deep vitals — detailed heap/non-heap
pools, GC, CPU internals, DB pool, and per-endpoint HTTP stats.

### What it shows

- **Health at a glance** (stat tiles): uptime, process & system CPU, heap used %,
  a 5xx error-rate alarm tile, and active users (claimed, last 30 days).
- **Vitals over time**: CPU (process/system/load), memory (heap used, non-heap
  used, heap max), and disk free.
- **API traffic**: request rate stacked by HTTP status; general latency (average +
  max); and planning-turn latency, broken out separately because those endpoints
  block on the OpenRouter call (see "Excluding AI latency" below).
- **AI usage**: requests/min by model & outcome, tokens/min by type (prompt,
  completion, and — when the provider reports prompt caching — cached and
  cache_write), 24h token totals, and active conversations. The `outcome` tag on
  `tasker.ai.requests` has three values: `success`, `error` (the call failed) and
  `empty` (the call returned 200 but the model produced neither content nor tool
  calls — a provider-side failure OpenRouter reported as a success). `empty` is
  worth alerting on; it is invisible in latency and error-rate panels.
- **Current totals**: users, users by type (claimed / unclaimed-idle /
  unclaimed-engaged), total tasks, tasks by status, planning sessions by status.
- **Email delivery & quick add**: emails sent/min by sender purpose (auth vs
  scheduling) & outcome (success vs failure), and the Telegram quick-capture
  funnel (saved vs. cancelled vs. expired).
- **Background jobs & logs** (collapsed): scheduled-task run rate by outcome
  (auto-archive, unclaimed-account cleanup, conversation cleanup, usage-metrics
  refresh) and warn/error log rate.
- **Request logs (Loki)**: live tail of the most recent application log lines.

### Importing

Grafana → Dashboards → New → Import → upload the JSON, then pick your Prometheus
datasource and your Loki datasource when prompted (the dashboard exposes
`datasource` and `logs_datasource` variables, so nothing is hard-coded — the
latter defaults to `grafanacloud-itaypk-logs`).

The custom application metrics are defined in
`dev.itayp.tasker.metrics.UsageMetrics` (gauges — users/tasks/planning-sessions),
`dev.itayp.tasker.ai.usage.AiUsageTracker` (AI request/token counters),
`dev.itayp.tasker.channel.email.EmailMetricsOutboundChannel` (`tasker.email.sent`
counter — one final outcome per email, after the scheduling sender's retries;
`RetryingOutboundChannel` counts the retries themselves on `tasker.email.retries`), and `dev.itayp.tasker.capture.QuickAddFlow` /
`dev.itayp.tasker.channel.telegram.QuickAddRegistry` (`tasker.quickadd.outcome`
counter). They are scraped from `/actuator/prometheus` (HTTP Basic auth — see
`PrometheusAuthProperties`). The recent-log-lines panel instead queries
structured logs directly via LogQL, independent of the Prometheus scrape.

**Naming gotcha**: the `UsageMetrics` gauges are named e.g. `tasker.users.total`
in code, but the Prometheus client strips reserved suffixes (`_total`,
`_created`, `_bucket`, `_info`, and their dotted forms) from *any* metric name
at registration time — that suffix is reserved for the exposition-format writer
to add back, and only for actual counters. So `tasker.users.total` (a gauge) is
exposed as `tasker_users`, not `tasker_users_total`; same for
`tasker.tasks.total` → `tasker_tasks` and `tasker.planning.sessions.total` →
`tasker_planning_sessions`. Counters like `tasker.ai.requests` are unaffected
and do get `_total` appended at scrape time. Keep this in mind if you rename
any gauge with a `.total`-style suffix.

### Excluding AI latency from the general latency panel

The web planning chat (`WebPlanningController`: `POST /api/v1/planning/start`,
`POST /api/v1/planning/{sessionId}/reply`, `POST /api/v1/planning/{sessionId}/revise`)
runs one synchronous orchestrator turn per request and blocks on the OpenRouter
call for the duration of the HTTP request — so its latency is dominated by LLM
response time, not app overhead. Mixing it into "general" API latency makes that
panel useless as a health signal. The "Latency (avg & max)" panel's `uri` filter
excludes these three routes (`uri!~"/actuator.*|/api/v1/planning/(start|{sessionId}/(reply|revise))"`
— RE2 doesn't support `\{`/`\}` as an escape, so the braces are left unescaped,
relying on RE2 treating a `{` that isn't a valid repetition operator as
literal); "Planning turn latency (AI-backed)" shows the inverse filter so you
don't lose visibility into it, just keep it separate.

Telegram's planning and quick-add flows do **not** need this treatment: the bot
runs over long-polling outside the Spring MVC request thread, so those AI calls
never appear in `http_server_requests_seconds` at all — only the web chat does.

If you add another synchronous AI-backed endpoint, extend both regexes (or this
will silently start polluting "general" latency again).

### Known limitation: latency percentiles

`http_server_requests_seconds` is currently exported as a plain summary (count/sum/max)
with **no histogram buckets**, so the latency panel can only show average and max —
not p95/p99. To unlock real percentiles, enable client-side histograms in
`application.yaml`:

```yaml
management:
  metrics:
    distribution:
      percentiles-histogram:
        http.server.requests: true
      # optional explicit SLO buckets to bound cardinality:
      # slo:
      #   http.server.requests: 50ms,100ms,250ms,500ms,1s,2s
```

This adds `http_server_requests_seconds_bucket` series, after which a panel using
`histogram_quantile(0.95, sum by (le) (rate(..._bucket[$__rate_interval])))` works.
Note it increases metric cardinality (one series per bucket per tag combination), so
prefer bounding it with explicit SLO buckets on a single-instance VPS.
