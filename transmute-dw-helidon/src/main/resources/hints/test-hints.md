## Dropwizard → Helidon Test-Fix Guidance

- **Never compare entity objects directly** — compare individual fields:
  `assertThat(result.getField()).isEqualTo(expected)` instead of `assertThat(result).isEqualTo(expected)`.
- **Always call `Mockito.reset(mockName)` in `@BeforeEach`** when mocks are shared across tests.
- **Resource tests must be plain JUnit/Mockito unit tests**, not HTTP integration tests.
- **`@Auth` user parameters were removed** from resource methods — do not pass auth principals in tests.
- **`io.dropwizard.testing.junit5.ResourceExtension` has no Helidon equivalent** — rewrite as direct unit tests
  that instantiate the resource class and call methods directly.
