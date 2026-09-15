package org.ocean.admin.gis.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ocean.admin.gis.config.FileUploadConfig;
import org.ocean.admin.gis.util.UploadPathResolver;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class UploadPathResolverTest {
    @TempDir
    Path uploadRoot;

    @Test
    void resolvesKeysWithinConfiguredRootAndRejectsTraversal() {
        FileUploadConfig config = new FileUploadConfig();
        config.setBasePath(uploadRoot.toString());
        UploadPathResolver resolver = new UploadPathResolver(config);

        assertEquals(uploadRoot.resolve("datasets/demo/source.tif").toAbsolutePath().normalize(),
                resolver.resolveKey("datasets/demo/source.tif"));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveKey("../outside.tif"));
        assertThrows(IllegalArgumentException.class,
                () -> resolver.resolveKey(""));
    }
}
