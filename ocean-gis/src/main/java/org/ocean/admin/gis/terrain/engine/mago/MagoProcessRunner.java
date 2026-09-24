package org.ocean.admin.gis.terrain.engine.mago;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** 运行 mago 子进程并负责超时、中断和日志尾部采集。 */
public class MagoProcessRunner {

    private static final int ERROR_TAIL_LINES = 100;

    public ProcessResult run(
            List<String> command,
            Duration timeout,
            Consumer<String> outputListener) {
        Instant startedAt = Instant.now();
        Process process;
        try {
            process = new ProcessBuilder(command)
                    .redirectErrorStream(true)
                    .start();
        } catch (IOException ex) {
            throw new MagoTerrainException("无法启动 mago 地形引擎", ex);
        }

        Consumer<String> listener = outputListener == null ? ignored -> { } : outputListener;
        Deque<String> outputTail = new ArrayDeque<>(ERROR_TAIL_LINES);
        Thread outputReader = Thread.ofVirtual()
                .name("mago-output-reader")
                .start(() -> consumeOutput(process, listener, outputTail));

        try {
            boolean finished = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!finished) {
                destroyProcessTree(process);
                throw new MagoTerrainException("mago 地形引擎执行超时: " + timeout);
            }
            outputReader.join(Duration.ofSeconds(5));
            int exitCode = process.exitValue();
            Duration elapsed = Duration.between(startedAt, Instant.now());
            if (exitCode != 0) {
                throw new MagoTerrainException(
                        "mago 地形引擎执行失败，退出码=" + exitCode + "\n" + String.join("\n", outputTail));
            }
            return new ProcessResult(exitCode, elapsed);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            destroyProcessTree(process);
            throw new MagoTerrainException("mago 地形引擎执行被中断", ex);
        }
    }

    private void consumeOutput(
            Process process,
            Consumer<String> listener,
            Deque<String> outputTail) {
        try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
            String line;
            while ((line = reader.readLine()) != null) {
                synchronized (outputTail) {
                    if (outputTail.size() == ERROR_TAIL_LINES) {
                        outputTail.removeFirst();
                    }
                    outputTail.addLast(line);
                }
                try {
                    listener.accept(line);
                } catch (RuntimeException ignored) {
                    // 进度消费者不能中断地形引擎。
                }
            }
        } catch (IOException ignored) {
            // 进程终止时输出流可能被关闭，最终结果由退出码裁决。
        }
    }

    private void destroyProcessTree(Process process) {
        process.descendants().forEach(ProcessHandle::destroy);
        process.destroy();
        try {
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            process.descendants().forEach(ProcessHandle::destroyForcibly);
            process.destroyForcibly();
        }
    }

    public record ProcessResult(int exitCode, Duration elapsed) {
    }
}
