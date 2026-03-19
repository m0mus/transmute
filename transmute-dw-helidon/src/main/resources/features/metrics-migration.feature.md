---
name: Metrics Migration
type: feature
triggers:
  - imports: [com.codahale.metrics.annotation]
  - imports: [com.codahale.metrics.MetricRegistry]
  - imports: [io.dropwizard.metrics]
order: 15
owns:
  annotations:
    - com.codahale.metrics.annotation.Timed
    - com.codahale.metrics.annotation.Counted
    - com.codahale.metrics.annotation.Metered
    - com.codahale.metrics.annotation.ExceptionMetered
    - com.codahale.metrics.annotation.Gauge
  types:
    - com.codahale.metrics.MetricRegistry
postchecks:
  forbidImports:
    - com.codahale.metrics
    - io.dropwizard.metrics
---

This file uses Codahale/Dropwizard Metrics and must be migrated to Helidon 4 SE
Declarative Metrics (`io.helidon.metrics.api.Metrics`).

## Annotation mapping

Replace Codahale metrics annotations on methods with Helidon declarative equivalents:

| Before | After |
|--------|-------|
| `@com.codahale.metrics.annotation.Timed` | `@Metrics.Timed` |
| `@com.codahale.metrics.annotation.Counted` | `@Metrics.Counted` |
| `@com.codahale.metrics.annotation.Metered` | `@Metrics.Counted` (closest equivalent; add comment: `// TRANSMUTE[manual]: @Metered -> @Metrics.Counted; no direct @Metered in Helidon`) |
| `@com.codahale.metrics.annotation.ExceptionMetered` | Remove annotation and add comment: `// TRANSMUTE[manual]: no direct @ExceptionMetered in Helidon; use try/catch + counter` |

When replacing `@Timed` or `@Counted`, preserve the `name`/`value` attribute if present.
Tags can be added via `@Metrics.Tag`:

```java
// Before
@Timed("myTimer")
public String getData() { ... }

// After
@Metrics.Timed("myTimer")
public String getData() { ... }
```

```java
// Before
@Counted(name = "myCounter")
public void doWork() { ... }

// After
@Metrics.Counted("myCounter")
public void doWork() { ... }
```

## Gauge methods

If a method was annotated with `@com.codahale.metrics.annotation.Gauge`, replace with
`@Metrics.Gauge`. The method must return a `Number` type:

```java
// Before
@com.codahale.metrics.annotation.Gauge(name = "queueSize")
public int getQueueSize() { return queue.size(); }

// After
@Metrics.Gauge("queueSize")
public int getQueueSize() { return queue.size(); }
```

## MetricRegistry field injection

If the class declares a `MetricRegistry` field or constructor parameter:

```java
// Before
@Inject
private MetricRegistry metrics;
```

Replace with Helidon's `MeterRegistry`:

```java
@Service.Inject
private io.helidon.metrics.api.MeterRegistry meterRegistry;
// TRANSMUTE[manual]: update usages — MetricRegistry API → Helidon MeterRegistry API
```

## Programmatic metric usages

Replace programmatic `MetricRegistry` API calls with Helidon `MeterRegistry` equivalents:

| Before | After |
|--------|-------|
| `metrics.timer(name(...))` | `meterRegistry.getOrCreate(Timer.builder("..."))` |
| `metrics.counter(name(...))` | `meterRegistry.getOrCreate(Counter.builder("..."))` |
| `metrics.meter(name(...))` | `meterRegistry.getOrCreate(Counter.builder("..."))` |
| `metrics.gauge(name, object, fn)` | `meterRegistry.getOrCreate(Gauge.builder("...", object, fn))` |
| `timer.time(() -> ...)` | Add TODO — programmatic timer recording needs manual review |
| `counter.inc()` / `counter.inc(n)` | `counter.increment()` / `counter.increment(n)` |

If a programmatic usage cannot be automatically mapped, add a `TRANSMUTE[manual]` comment:
```java
// TRANSMUTE[manual]: was MetricRegistry.<method> — adapt to Helidon MeterRegistry
```

## MetricRegistry.name() helper

Replace `MetricRegistry.name(Foo.class, "bar")` with the string literal `"foo.bar"` (lowercase
class simple name + dot + suffix), or add a TODO if the pattern is dynamic.

## Imports

Remove:
- `com.codahale.metrics.*`
- `io.dropwizard.metrics.*`

Add (only those actually used after migration):
- `io.helidon.metrics.api.Metrics` (for `@Metrics.Timed`, `@Metrics.Counted`, `@Metrics.Gauge`)
- `io.helidon.metrics.api.MeterRegistry` (if programmatic meter usage exists)
- `io.helidon.metrics.api.Timer` (if programmatic timer usage exists)
- `io.helidon.metrics.api.Counter` (if programmatic counter usage exists)
- `io.helidon.metrics.api.Gauge` (if programmatic gauge usage exists)
