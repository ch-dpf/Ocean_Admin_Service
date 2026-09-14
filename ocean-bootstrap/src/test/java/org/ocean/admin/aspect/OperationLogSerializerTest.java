package org.ocean.admin.aspect;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OperationLogSerializerTest {

    private final OperationLogSerializer serializer =
            new OperationLogSerializer(new ObjectMapper(), 8192, 16384);

    @Test
    void shouldMaskSensitiveFieldsAndSummarizeMultipartFiles() {
        MockMultipartFile file = new MockMultipartFile(
                "files", "terrain.tif", "image/tiff", new byte[]{1, 2, 3});
        Map<String, Object> credentials = Map.of(
                "username", "ocean",
                "password", "plain-text",
                "nested", Map.of("token", "jwt-value"));

        String json = serializer.serializeRequest(
                new String[]{"credentials", "files"},
                new Object[]{credentials, List.of(file)},
                new String[0]);

        assertThat(json)
                .contains("\"password\":\"******\"")
                .contains("\"token\":\"******\"")
                .contains("terrain.tif")
                .contains("\"size\":3")
                .doesNotContain("plain-text", "jwt-value");
    }

    @Test
    void shouldApplyOperationSpecificSensitiveFields() {
        String json = serializer.serializeRequest(
                new String[]{"payload"},
                new Object[]{Map.of("apiCredential", "do-not-store")},
                new String[]{"apiCredential"});

        assertThat(json)
                .contains("\"apiCredential\":\"******\"")
                .doesNotContain("do-not-store");
    }
}
