package com.company.promoengine.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.company.promoengine.support.JsonComparison;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.Objects;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Acceptance tests for the promotion contract. Each file under
 * {@code src/test/resources/fixtures/public} is one request and its expected body.
 * All six run against the implemented {@code DefaultPromotionEngine}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PromoEngineFixtureTest {

    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
            .enable(JsonGenerator.Feature.WRITE_BIGDECIMAL_AS_PLAIN);

    @Autowired
    private MockMvc mockMvc;

    @ParameterizedTest(name = "{0}")
    @MethodSource("fixtures")
    void evaluatesFixture(String name) throws Exception {
        try (var input = getClass().getResourceAsStream("/fixtures/public/" + name)) {
            if (input == null) {
                throw new AssertionError("Missing fixture " + name);
            }
            JsonNode root = JSON.readTree(input);
            var result = mockMvc.perform(post("/api/v1/cart/evaluate")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(JSON.writeValueAsBytes(root.get("request"))))
                    .andExpect(status().isOk())
                    .andReturn();
            JsonNode actual = JSON.readTree(result.getResponse().getContentAsString());
            JsonComparison.assertEquals(root.get("expectedResponse"), actual);
        }
    }

    static Stream<String> fixtures() throws Exception {
        var resolver = new PathMatchingResourcePatternResolver();
        return Arrays.stream(resolver.getResources("classpath:fixtures/public/*.json"))
                .map(Resource::getFilename)
                .filter(Objects::nonNull)
                .sorted();
    }
}
