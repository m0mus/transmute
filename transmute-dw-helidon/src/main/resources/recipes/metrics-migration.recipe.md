---
name: Metrics Migration
type: recipe
triggers:
  - imports: [com.codahale.metrics.annotation]
  - imports: [com.codahale.metrics.MetricRegistry]
  - imports: [io.dropwizard.metrics]
order: 15
postchecks:
  forbidImports:
    - com.codahale.metrics
    - io.dropwizard.metrics
---

This file uses Codahale/Dropwizard Metrics and must be migrated to Micrometer, which
Helidon 4 SE uses for metrics.

## Annotation mapping

Replace Codahale metrics annotations on methods and classes:

| Before | After |
|--------|-------|
| `@com.codahale.metrics.annotation.Timed` | `@io.micrometer.core.annotation.Timed` |
| `@com.codahale.metrics.annotation.Counted` | `@io.micrometer.core.annotation.Counted` |
| `@com.codahale.metrics.annotation.Metered` | `@io.micrometer.core.annotation.Counted` (closest equivalent; add comment: `// TODO: @Metered → @Counted; Micrometer has no @Metered`) |
| `@com.codahale.metrics.annotation.ExceptionMetered` | Remove annotation and add comment: `// TODO: no direct @ExceptionMetered in Micrometer; use try/catch + counter` |

When replacing `@Timed` or `@Counted`, preserve the `name` attribute if present:
- `@Timed("myTimer")` → `@Timed("myTimer")`
- `@Counted(name = "myCounter")` → `@Counted(value = "myCounter")` (Micrometer uses `value`)

## MetricRegistry field injection

If the class declares a `MetricRegistry` field or constructor parameter:

```java
// Before
@Inject
private MetricRegistry metrics;
```

Replace with:

```java
@Service.Inject
private MeterRegistry meterRegistry;
// TODO: update usages below — MetricRegistry → MeterRegistry (Micrometer API)
```

## Programmatic metric usages

Replace programmatic `MetricRegistry` API calls:

| Before | After |
|--------|-------|
| `metrics.timer(name(...))` | `Timer.builder("...").register(meterRegistry)` |
| `metrics.counter(name(...))` | `Counter.builder("...").register(meterRegistry)` |
| `metrics.meter(name(...))` | `Counter.builder("...").register(meterRegistry)` |
| `metrics.histogram(name(...))` | `DistributionSummary.builder("...").register(meterRegistry)` |
| `metrics.gauge(name, object, fn)` | `Gauge.builder("...", object, fn).register(meterRegistry)` |
| `timer.time(() -> ...)` | `timer.record(() -> ...)` |
| `timer.update(duration, unit)` | `timer.record(duration, unit)` |
| `counter.inc()` / `counter.inc(n)` | `counter.increment()` / `counter.increment(n)` |

If a programmatic usage cannot be automatically mapped, add a TODO comment:
```java
// DW_MIGRATION_TODO[manual]: was MetricRegistry.<method> — adapt to Micrometer MeterRegistry
```

## MetricRegistry.name() helper

Replace `MetricRegistry.name(Foo.class, "bar")` with the string literal `"foo.bar"` (lowercase
class simple name + dot + suffix), or add a TODO if the pattern is dynamic.

## Imports

Remove:
- `com.codahale.metrics.*`
- `io.dropwizard.metrics.*`

Add (only those actually used after migration):
- `io.micrometer.core.annotation.Timed`
- `io.micrometer.core.annotation.Counted`
- `io.micrometer.core.instrument.MeterRegistry`
- `io.micrometer.core.instrument.Timer`
- `io.micrometer.core.instrument.Counter`
- `io.micrometer.core.instrument.DistributionSummary`
- `io.micrometer.core.instrument.Gauge`
