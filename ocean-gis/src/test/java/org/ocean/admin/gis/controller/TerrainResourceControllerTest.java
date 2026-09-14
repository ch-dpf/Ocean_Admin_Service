package org.ocean.admin.gis.controller;

import org.junit.jupiter.api.Test;
import org.ocean.admin.gis.service.TerrainPublicationService;
import org.springframework.core.io.Resource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class TerrainResourceControllerTest {

    private final Path tempDir = Path.of("target", "test-work", "terrain-resource-controller");

    @Test
    void servesTerrainWithQuantizedMeshContentType() throws Exception {
        String serviceCode = "TRN_0123456789ABCDEF0123";
        Files.createDirectories(tempDir);
        Path tile = Files.write(tempDir.resolve("0.terrain"), new byte[]{1, 2, 3});
        TerrainPublicationService service = mock(TerrainPublicationService.class);
        when(service.resolvePublishedFile(serviceCode, "/0/0/0.terrain"))
                .thenReturn(tile);
        TerrainResourceController controller = new TerrainResourceController(service);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "GET", "/terrain/" + serviceCode + "/0/0/0.terrain");

        ResponseEntity<Resource> response = controller.getResource(serviceCode, request);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getHeaders().getContentType())
                .isEqualTo(MediaType.parseMediaType("application/vnd.quantized-mesh"));
        assertThat(response.getHeaders().getContentLength()).isEqualTo(3L);
    }
}
