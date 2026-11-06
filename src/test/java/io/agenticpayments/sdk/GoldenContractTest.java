package io.agenticpayments.sdk;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class GoldenContractTest {
    private static final Path FIXTURES = Path.of("specification", "0.1.0-draft", "fixtures");
    private static final String EXPECTED = "sha256:c1f63c9e22cb8953af008e171b42d17fa188e027651ff07fc8620fceb6c238ad";
    private static final ObjectMapper JSON = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build());

    @Test
    void goldenBytesHaveExactLengthEncodingAndDigest() throws Exception {
        byte[] bytes = Files.readAllBytes(FIXTURES.resolve("checkout-canonical.json"));
        assertEquals(402, bytes.length);
        assertEquals('{', bytes[0]);
        assertEquals('}', bytes[bytes.length - 1]);
        assertEquals(EXPECTED, digest(bytes));
        JsonNode snapshot = JSON.readTree(bytes);
        assertEquals("fixtures-0.1", snapshot.path("catalogVersion").asText());
        assertEquals("1250", snapshot.path("money").path("minorUnits").asText());
        assertEquals(EXPECTED, JSON.readTree(FIXTURES.resolve("checkout-canonical.metadata.json").toFile()).path("digest").asText());
        assertEquals(new String(bytes, StandardCharsets.UTF_8), JSON.writeValueAsString(snapshot));
    }

    @Test
    void everyBindingMutationDiffersFromTheApprovedSnapshotAndDigest() throws Exception {
        JsonNode original = JSON.readTree(FIXTURES.resolve("checkout-canonical.json").toFile());
        var manifest = JSON.readTree(FIXTURES.resolve("manifest.json").toFile());
        assertEquals(6, manifest.path("bindingFixtures").size());
        for (var fixture : manifest.path("bindingFixtures")) {
            JsonNode changed = JSON.readTree(FIXTURES.resolve(fixture.path("file").asText()).toFile());
            assertNotEquals(original, changed, fixture.path("file").asText());
            assertNotEquals(EXPECTED, digest(JSON.writeValueAsBytes(changed)));
            assertTrue(fixture.path("expectedSchemaValid").asBoolean());
            assertFalse(fixture.path("expectedBindingValid").asBoolean());
        }
    }

    @Test
    void duplicateMembersAreRejectedBeforeSchemaEvaluation() {
        assertThrows(com.fasterxml.jackson.core.JsonParseException.class,
                () -> JSON.readTree(FIXTURES.resolve("invalid/create-duplicate-keys.json").toFile()));
    }

    @Test
    void schemaAndFixtureReferencesResolveWithinTheVersionedSpecification() throws Exception {
        JsonNode manifest = JSON.readTree(FIXTURES.resolve("manifest.json").toFile());
        for (String group : List.of("schemaFixtures", "bindingFixtures", "parserFixtures")) {
            for (var entry : manifest.path(group)) {
                assertTrue(Files.isRegularFile(FIXTURES.resolve(entry.path("file").asText())));
                if (entry.has("schema")) {
                    assertTrue(Files.isRegularFile(FIXTURES.resolve(entry.path("schema").asText())));
                }
            }
        }
        try (var entries = Files.list(FIXTURES.resolveSibling("schemas"))) {
            for (Path file : entries.toList()) {
                JsonNode schema = JSON.readTree(file.toFile());
                assertEquals("https://json-schema.org/draft/2020-12/schema", schema.path("$schema").asText());
                if (schema.has("$ref")) {
                    String[] target = schema.path("$ref").asText().split("#", 2);
                    JsonNode definitions = JSON.readTree(file.getParent().resolve(target[0]).toFile());
                    assertFalse(definitions.at(target[1]).isMissingNode());
                }
            }
        }
    }

    private static String digest(byte[] bytes) throws Exception {
        return "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
