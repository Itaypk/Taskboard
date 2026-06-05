# Monitoring

## Backlog.fyi — Application Overview dashboard

`backlog-overview-dashboard.json` is a Grafana dashboard giving a shallow, broad,
at-a-glance picture of the running app. It complements (does not replace) the default
Spring Boot dashboard, which already covers the deep vitals — detailed heap/non-heap
pools, GC, CPU internals, DB pool, and per-endpoint HTTP stats.

### What it shows

- **Health at a glance** (stat tiles): uptime, process & system CPU, heap used %,
  disk free, and a 5xx error-rate alarm tile.
- **Vitals over time**: CPU (process/system/load) and memory (heap used, non-heap
  used, heap max).
- **API traffic**: request rate stacked by HTTP status, and latency (average + max).
- **AI usage**: requests/min by model & outcome, tokens/min (prompt vs completion),
  24h token totals, and active conversations.
- **Current totals**: users, demo users, total tasks, tasks by status, planning
  sessions by status.
- **Background jobs & logs** (collapsed): scheduled-task run rate by outcome
  (auto-archive, demo cleanup, conversation cleanup, usage-metrics refresh) and
  warn/error log rate.

### Importing

Grafana → Dashboards → New → Import → upload the JSON, then pick your Prometheus
datasource when prompted (the dashboard exposes a `datasource` variable, so nothing
is hard-coded).

The custom application metrics are defined in
`dev.itayp.tasker.metrics.UsageMetrics` (gauges) and
`dev.itayp.tasker.ai.usage.AiUsageTracker` (counters). They are scraped from
`/actuator/prometheus` (HTTP Basic auth — see `PrometheusAuthProperties`).

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
