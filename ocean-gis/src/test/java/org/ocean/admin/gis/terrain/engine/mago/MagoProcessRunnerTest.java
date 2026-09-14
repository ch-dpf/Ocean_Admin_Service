package org.ocean.admin.gis.terrain.engine.mago;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class MagoProcessRunnerTest {

    @Test
    void runsJavaChildProcessAndStreamsItsOutput() {
        String executableName = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        String javaCommand = Path.of(
                System.getProperty("java.home"),
                "bin",
                executableName).toString();
        List<String> output = new ArrayList<>();

        MagoProcessRunner.ProcessResult result = new MagoProcessRunner().run(
                List.of(javaCommand, "-version"),
                Duration.ofSeconds(10),
                output::add);

        assertThat(result.exitCode()).isZero();
        assertThat(result.elapsed()).isGreaterThanOrEqualTo(Duration.ZERO);
        assertThat(output).anyMatch(line -> line.toLowerCase().contains("version"));
    }
}
