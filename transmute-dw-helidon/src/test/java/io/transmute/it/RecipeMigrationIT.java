package io.transmute.it;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests that apply DW->Helidon recipes to real Dropwizard source files
 * and verify postchecks pass.
 *
 * Requires TRANSMUTE_API_KEY environment variable. Skipped automatically when not set.
 */
@EnabledIfEnvironmentVariable(named = "TRANSMUTE_API_KEY", matches = ".+")
class RecipeMigrationIT {

    private final RecipeTestHarness harness = new RecipeTestHarness();

    @Test
    void healthCheckMigration() throws Exception {
        var input = fixture("TemplateHealthCheck.java");
        var output = harness.applyRecipe("HealthCheck Migration", input);
        var failures = harness.runPostchecks("HealthCheck Migration", output);

        assertTrue(failures.isEmpty(), "Postcheck failures: " + failures);
        assertFalse(output.contains("import com.codahale.metrics.health.HealthCheck"),
            "Old HealthCheck import should be removed");
        assertTrue(output.contains("HealthCheckResponse"),
            "Should use Helidon HealthCheckResponse");
    }

    @Test
    void configurationMigration() throws Exception {
        var input = fixture("HelloWorldConfiguration.java");
        var output = harness.applyRecipe("Dropwizard Configuration", input);
        var failures = harness.runPostchecks("Dropwizard Configuration", output);

        assertTrue(failures.isEmpty(), "Postcheck failures: " + failures);
        assertFalse(output.contains("extends Configuration"),
            "Should not extend Configuration");
        assertTrue(output.contains("io.helidon.config.Config"),
            "Should use Helidon Config");
    }

    @Test
    void restResourceMigration() throws Exception {
        var input = fixture("HelloWorldResource.java");
        var output = harness.applyRecipe("REST Resource", input);
        var failures = harness.runPostchecks("REST Resource", output);

        assertTrue(failures.isEmpty(), "Postcheck failures: " + failures);
        assertFalse(output.contains("import javax.ws.rs"), "Old JAX-RS imports should be removed");
        assertTrue(output.contains("@Http."), "Should use Helidon @Http annotations");
        assertTrue(output.contains("@Service.Singleton"), "Should be @Service.Singleton");
    }

    @Test
    void applicationBootstrapMigration() throws Exception {
        var input = fixture("HelloWorldApplication.java");
        var output = harness.applyRecipe("Application Bootstrap", input);
        var failures = harness.runPostchecks("Application Bootstrap", output);

        assertTrue(failures.isEmpty(), "Postcheck failures: " + failures);
        assertFalse(output.contains("extends Application"), "Should not extend Application");
        assertTrue(output.contains("WebServer"), "Should use Helidon WebServer");
    }

    @Test
    void securityAuthMigration() throws Exception {
        var input = fixture("ExampleAuthenticator.java");
        var output = harness.applyRecipe("Security / Auth Migration", input);
        var failures = harness.runPostchecks("Security / Auth Migration", output);

        assertTrue(failures.isEmpty(), "Postcheck failures: " + failures);
        assertFalse(output.contains("import io.dropwizard.auth"), "Old auth imports should be removed");
    }

    @Test
    void unitOfWorkMigration() throws Exception {
        var input = fixture("PeopleResource.java");
        var output = harness.applyRecipe("Unit of Work / Hibernate Migration", input);
        var failures = harness.runPostchecks("Unit of Work / Hibernate Migration", output);

        assertTrue(failures.isEmpty(), "Postcheck failures: " + failures);
        // Check no active @UnitOfWork annotation (comments containing the text are fine)
        assertFalse(output.lines().anyMatch(l -> l.stripLeading().startsWith("@UnitOfWork")),
                "UnitOfWork annotation should be removed");
        assertFalse(output.contains("import io.dropwizard.hibernate"), "DW hibernate import should be removed");
    }

    @Test
    void beanValidationMigration() throws Exception {
        var input = fixture("PeopleResource.java");
        var output = harness.applyRecipe("Bean Validation Migration", input);
        var failures = harness.runPostchecks("Bean Validation Migration", output);

        assertTrue(failures.isEmpty(), "Postcheck failures: " + failures);
        assertFalse(output.contains("import javax.validation"), "javax.validation import should be removed");
        assertTrue(output.contains("import io.helidon.validation.Validation"), "Helidon Validation import should be added");
        assertTrue(output.contains("@Validation."), "Helidon @Validation.* annotations should be used");
    }

    /**
     * Validates the Bean Validation Migration on a JPA {@code @Entity} class.
     *
     * <p>Key assertions:
     * <ul>
     *   <li>{@code @Validation.Validated} is added to the class — required even on {@code @Entity} types</li>
     *   <li>{@code @Min}/{@code @Max} are converted to {@code @Validation.Integer.Min}/{@code @Validation.Integer.Max}
     *       and moved to the public getter — Helidon's {@code ValidatedTypeGenerator} only processes
     *       no-argument non-void public methods (getters); annotations on private fields or void setters
     *       produce an empty property switch with an unreachable trailing `;` in the generated file</li>
     *   <li>No {@code javax.validation} imports remain</li>
     * </ul>
     *
     * <p>Run this test alone with:
     * <pre>mvn test -pl transmute-dw-helidon -Pit -Dtest=RecipeMigrationIT#beanValidationEntityMigration</pre>
     */
    @Test
    void beanValidationEntityMigration() throws Exception {
        var input = fixture("Person.java");
        var output = harness.applyRecipe("Bean Validation Migration", input);
        var failures = harness.runPostchecks("Bean Validation Migration", output);

        assertTrue(failures.isEmpty(), "Postcheck failures: " + failures);
        assertFalse(output.contains("import javax.validation"), "javax.validation import should be removed");
        assertTrue(output.contains("import io.helidon.validation.Validation"), "Helidon Validation import should be added");
        assertTrue(output.contains("@Validation.Validated"), "@Validation.Validated must be on the class (required even for @Entity)");
        assertTrue(output.contains("@Validation.Integer.Min"), "@Min(0) should become @Validation.Integer.Min(0)");
        assertTrue(output.contains("@Validation.Integer.Max"), "@Max(9999) should become @Validation.Integer.Max(9999)");
        // Annotations must be on the public getter (no-args, non-void) — not on the private field or void setter
        int minPos = output.indexOf("@Validation.Integer.Min");
        int getterPos = output.indexOf("getYearBorn()");
        assertTrue(minPos > 0 && getterPos > 0 && Math.abs(minPos - getterPos) < 120,
                "@Validation.Integer.Min should be on the getYearBorn() getter, not on the private field or void setter");
    }

    @Test
    void testMigrationResourceExtension() throws Exception {
        var input = fixture("PeopleResourceTest.java");
        var output = harness.applyRecipe("Test Migration", input);
        var failures = harness.runPostchecks("Test Migration", output);

        assertTrue(failures.isEmpty(), "Postcheck failures: " + failures);
        assertFalse(output.contains("ResourceExtension"), "ResourceExtension should be removed");
        assertFalse(output.contains("DropwizardExtensionsSupport"), "DropwizardExtensionsSupport should be removed");
        assertTrue(output.contains("@ServerTest"), "Should use @ServerTest");
        assertTrue(output.contains("Http1Client"), "Should use Http1Client");
    }

    @Test
    void testMigrationAuthProtected() throws Exception {
        var input = fixture("ProtectedResourceTest.java");
        var output = harness.applyRecipe("Test Migration", input);
        var failures = harness.runPostchecks("Test Migration", output);

        assertTrue(failures.isEmpty(), "Postcheck failures: " + failures);
        assertFalse(output.contains("ResourceExtension"), "ResourceExtension should be removed");
        assertFalse(output.contains("BasicCredentialAuthFilter"), "BasicCredentialAuthFilter should be removed");
        assertTrue(output.contains("@ServerTest"), "Should use @ServerTest");
        assertTrue(output.contains("Http1Client"), "Should use Http1Client");
        assertTrue(output.contains("HeaderNames.AUTHORIZATION"), "Should use Helidon HeaderNames.AUTHORIZATION");
        assertTrue(output.contains("basicAuth("), "Should use basicAuth() helper");
    }

    @Test
    void testMigrationDropwizardAppExtension() throws Exception {
        var input = fixture("IntegrationTest.java");
        var output = harness.applyRecipe("Test Migration", input);
        var failures = harness.runPostchecks("Test Migration", output);

        assertTrue(failures.isEmpty(), "Postcheck failures: " + failures);
        assertFalse(output.contains("DropwizardAppExtension"), "DropwizardAppExtension should be removed");
        assertFalse(output.contains("DropwizardExtensionsSupport"), "DropwizardExtensionsSupport should be removed");
        assertFalse(output.contains("APP.getLocalPort()"), "APP.getLocalPort() should be removed");
        assertTrue(output.contains("@ServerTest"), "Should use @ServerTest");
        assertTrue(output.contains("Http1Client"), "Should use Http1Client");
    }

    private String fixture(String name) throws Exception {
        try (var stream = getClass().getResourceAsStream("/fixtures/" + name)) {
            assertNotNull(stream, "Fixture not found: " + name);
            return new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }
}
