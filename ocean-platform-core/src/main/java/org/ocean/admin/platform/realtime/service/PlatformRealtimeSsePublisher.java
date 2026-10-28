package org.ocean.admin.platform.realtime.service;

import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.ocean.admin.kernel.realtime.EventMessage;
import org.ocean.admin.kernel.realtime.RealtimeEventPublisher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/** 平台域SSE通道，独立维护连接与资源上限；发布线程仅序列化和入队。 */
@Slf4j
@Component("platformRealtimeSsePublisher")
public class PlatformRealtimeSsePublisher implements RealtimeEventPublisher {
    public static final String REQUEST_EMITTER_ATTRIBUTE = PlatformRealtimeSsePublisher.class.getName() + ".emitter";
    private static final String EVENT_DOMAIN = "platform";
    private static final int SEND_BATCH_SIZE = 16;
    private final ObjectMapper objectMapper;
    private final int maxConnections;
    private final int maxPendingMessages;
    private final long maxPendingBytes;
    private final long heartbeatNanos;
    private final long sendTimeoutNanos;
    private final long connectionTimeoutMillis;
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final AtomicBoolean stopped = new AtomicBoolean();
    private final ThreadPoolExecutor sendExecutor;
    private final ThreadPoolExecutor completionExecutor;
    private final ScheduledExecutorService maintenance;

    public PlatformRealtimeSsePublisher(ObjectMapper objectMapper,
            @Value("${ocean.platform.realtime.sse.max-connections:256}") int maxConnections,
            @Value("${ocean.platform.realtime.sse.max-pending-messages:64}") int maxPendingMessages,
            @Value("${ocean.platform.realtime.sse.max-pending-bytes:524288}") long maxPendingBytes,
            @Value("${ocean.platform.realtime.sse.send-threads:8}") int sendThreads,
            @Value("${ocean.platform.realtime.sse.send-queue-capacity:256}") int sendQueueCapacity,
            @Value("${ocean.platform.realtime.sse.heartbeat-interval-ms:15000}") long heartbeatMillis,
            @Value("${ocean.platform.realtime.sse.send-timeout-ms:10000}") long sendTimeoutMillis,
            @Value("${ocean.platform.realtime.sse.connection-timeout-ms:1800000}") long connectionTimeoutMillis) {
        if (maxConnections < 1 || maxPendingMessages < 1 || maxPendingBytes < 128 || sendThreads < 1
                || sendQueueCapacity < 1 || heartbeatMillis < 1 || sendTimeoutMillis < 1 || connectionTimeoutMillis < 1)
            throw new IllegalArgumentException("SSE连接、队列与超时配置必须为正数，字节上限至少128");
        this.objectMapper = objectMapper;
        this.maxConnections = maxConnections;
        this.maxPendingMessages = maxPendingMessages;
        this.maxPendingBytes = maxPendingBytes;
        this.heartbeatNanos = TimeUnit.MILLISECONDS.toNanos(heartbeatMillis);
        this.sendTimeoutNanos = TimeUnit.MILLISECONDS.toNanos(sendTimeoutMillis);
        this.connectionTimeoutMillis = connectionTimeoutMillis;
        sendExecutor = pool(sendThreads, sendQueueCapacity, "platform-realtime-sse-send");
        completionExecutor = pool(2, maxConnections, "platform-realtime-sse-close");
        maintenance = Executors.newSingleThreadScheduledExecutor(threads("platform-realtime-sse-maintenance"));
        maintenance.scheduleWithFixedDelay(this::maintainSafely, 1, 1, TimeUnit.SECONDS);
    }

    private static ThreadFactory threads(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }

    private static ThreadPoolExecutor pool(int threads, int capacity, String name) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(capacity), threads(name), new ThreadPoolExecutor.AbortPolicy());
    }

    /** 创建时尚不发送；MVC异步响应初始化后由配置层调用activate。 */
    public SseEmitter connect(String requestedTopics) {
        Set<String> topics = parseTopics(requestedTopics);
        Connection connection;
        synchronized (connections) {
            if (stopped.get() || connections.size() >= maxConnections)
                throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "实时事件连接已满，请稍后重试");
            connection = new Connection(topics);
            connections.put(connection.id, connection);
        }
        connection.offer(Frame.comment("connected", true));
        return connection.emitter;
    }

    public void activate(SseEmitter emitter) {
        if (emitter instanceof ManagedEmitter managed) managed.connection.activate();
    }

    private Set<String> parseTopics(String requested) {
        if (requested == null) return Set.of(EVENT_DOMAIN);
        if (requested.isBlank() || requested.length() > 512)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件主题不能为空且不能超过512字符");
        List<String> topics = Arrays.stream(requested.split(",", -1)).map(String::trim).toList();
        if (topics.size() > 16 || topics.stream().anyMatch(topic ->
                !topic.matches("[A-Za-z][A-Za-z0-9_-]*(\\.[A-Za-z][A-Za-z0-9_-]*)*")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件主题格式无效或超过16项");
        if (topics.stream().anyMatch(topic ->
                !topic.equals(EVENT_DOMAIN) && !topic.startsWith(EVENT_DOMAIN + ".")))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "事件主题必须属于当前接口的事件域: " + EVENT_DOMAIN);
        return topics.stream().collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public boolean hasSubscribers(String eventType) {
        return !stopped.get() && connections.values().stream().anyMatch(connection -> connection.accepts(eventType));
    }

    @Override
    public void publish(EventMessage<?> message) {
        if (stopped.get()) return;
        List<Connection> recipients = connections.values().stream()
                .filter(connection -> connection.accepts(message.type())).toList();
        if (recipients.isEmpty()) return;
        String json = objectMapper.writeValueAsString(message);
        Frame frame = new Frame(json, json.getBytes(StandardCharsets.UTF_8).length + 64L, false);
        for (Connection connection : recipients) connection.offer(frame);
    }

    private void maintainSafely() {
        try {
            long now = System.nanoTime();
            for (Connection connection : connections.values()) {
                long sendingSince = connection.inFlightSince;
                if (connection.closed.get()) {
                    connection.scheduleCompletion();
                } else if (now - connection.createdAt >= TimeUnit.MILLISECONDS.toNanos(connectionTimeoutMillis)) {
                    connection.close("CONNECTION_TIMEOUT");
                } else if (sendingSince != 0 && now - sendingSince >= sendTimeoutNanos) {
                    connection.close("SEND_TIMEOUT");
                } else if (now - connection.lastSentAt >= heartbeatNanos) {
                    connection.heartbeat();
                }
            }
        } catch (RuntimeException ex) {
            log.warn("SSE连接维护失败", ex);
        }
    }

    @PreDestroy
    public void destroy() {
        synchronized (connections) { stopped.set(true); }
        maintenance.shutdownNow();
        for (Connection connection : connections.values()) connection.close("SHUTDOWN");
        sendExecutor.shutdownNow();
        completionExecutor.shutdown();
        try {
            if (!completionExecutor.awaitTermination(5, TimeUnit.SECONDS)) completionExecutor.shutdownNow();
        } catch (InterruptedException ex) {
            completionExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        connections.clear();
    }

    private record Frame(String data, long bytes, boolean opening) {
        static Frame comment(String text, boolean opening) { return new Frame(text, 64, opening); }
    }

    private final class Connection {
        final String id = UUID.randomUUID().toString();
        final Set<String> topics;
        final ManagedEmitter emitter = new ManagedEmitter(this);
        final ArrayDeque<Frame> pending = new ArrayDeque<>();
        final AtomicBoolean closed = new AtomicBoolean();
        final AtomicBoolean completionScheduled = new AtomicBoolean();
        final long createdAt = System.nanoTime();
        volatile long lastSentAt = createdAt;
        volatile long inFlightSince;
        volatile Thread writer;
        boolean active;
        boolean draining;
        int pendingCount;
        long pendingBytes;

        Connection(Set<String> topics) {
            this.topics = topics;
            emitter.onCompletion(this::release);
            emitter.onTimeout(() -> close("ASYNC_TIMEOUT"));
            emitter.onError(error -> release());
        }

        boolean accepts(String eventType) {
            return !closed.get() && topics.stream().anyMatch(topic ->
                    eventType.equals(topic) || eventType.startsWith(topic + "."));
        }

        void activate() {
            boolean schedule = false;
            synchronized (this) {
                active = true;
                if (!closed.get() && !pending.isEmpty() && !draining) {
                    draining = true;
                    schedule = true;
                }
            }
            if (schedule) scheduleDrain();
        }

        void heartbeat() {
            synchronized (this) {
                if (!active || pendingCount != 0 || closed.get()) return;
            }
            offer(Frame.comment("heartbeat", false));
        }

        void offer(Frame frame) {
            boolean overflow;
            boolean schedule = false;
            synchronized (this) {
                if (closed.get()) return;
                overflow = pendingCount >= maxPendingMessages || frame.bytes > maxPendingBytes - pendingBytes;
                if (!overflow) {
                    pending.addLast(frame);
                    pendingCount++;
                    pendingBytes += frame.bytes;
                    if (active && !draining) {
                        draining = true;
                        schedule = true;
                    }
                }
            }
            if (overflow) close("PENDING_LIMIT");
            else if (schedule) scheduleDrain();
        }

        void scheduleDrain() {
            try {
                sendExecutor.execute(this::drain);
            } catch (RejectedExecutionException ex) {
                close("SEND_EXECUTOR_FULL");
            }
        }

        void drain() {
            try {
                for (int sent = 0; sent < SEND_BATCH_SIZE; sent++) {
                    Frame frame;
                    synchronized (this) {
                        if (closed.get()) return;
                        frame = pending.pollFirst();
                        if (frame == null) { draining = false; return; }
                        writer = Thread.currentThread();
                        inFlightSince = System.nanoTime();
                    }
                    try {
                        SseEmitter.SseEventBuilder event = SseEmitter.event();
                        if (frame.opening) event.comment("connected").reconnectTime(3000);
                        else if ("heartbeat".equals(frame.data)) event.comment("heartbeat");
                        else event.name("app.event").data(frame.data, MediaType.APPLICATION_JSON);
                        emitter.send(event);
                    } finally {
                        synchronized (this) {
                            writer = null;
                            inFlightSince = 0;
                            if (!closed.get()) {
                                pendingCount--;
                                pendingBytes -= frame.bytes;
                                lastSentAt = System.nanoTime();
                            }
                        }
                    }
                }
                synchronized (this) {
                    if (closed.get()) return;
                    if (pending.isEmpty()) { draining = false; return; }
                }
                scheduleDrain();
            } catch (IOException ex) {
                // Servlet容器接管I/O失败后的异步清理，不得再次完成已不可用的响应。
                log.debug("SSE客户端已断开: connectionId={}, reason={}", id, ex.getMessage());
                release();
            } catch (RuntimeException ex) {
                log.debug("SSE发送结束: connectionId={}, reason={}", id, ex.getMessage());
                close("SEND_FAILED");
            }
        }

        void close(String reason) {
            if (!closed.compareAndSet(false, true)) return;
            synchronized (this) {
                pending.clear();
                pendingBytes = 0;
                pendingCount = 0;
                Thread sending = writer;
                if (sending != null && sending != Thread.currentThread()) sending.interrupt();
            }
            log.debug("SSE连接回收: connectionId={}, reason={}", id, reason);
            scheduleCompletion();
        }

        void scheduleCompletion() {
            if (!completionScheduled.compareAndSet(false, true)) return;
            try {
                completionExecutor.execute(() -> {
                    try {
                        if (emitter.tryComplete()) release();
                    } catch (IllegalStateException ex) {
                        // 容器可能已经在onError之后完成AsyncContext。
                        log.debug("SSE响应已由容器回收: connectionId={}, reason={}", id, ex.getMessage());
                        release();
                    } finally {
                        completionScheduled.set(false);
                    }
                });
            } catch (RejectedExecutionException ex) {
                completionScheduled.set(false);
            }
        }

        void release() {
            closed.set(true);
            synchronized (this) {
                pending.clear();
                pendingCount = 0;
                pendingBytes = 0;
                Thread sending = writer;
                if (sending != null && sending != Thread.currentThread()) sending.interrupt();
            }
            connections.remove(id, this);
        }
    }

    /** 关闭任务不能等待正在进行的网络写锁；繁忙时由维护线程稍后重试。 */
    private final class ManagedEmitter extends SseEmitter {
        final Connection connection;
        ManagedEmitter(Connection connection) {
            super(connectionTimeoutMillis);
            this.connection = connection;
        }
        boolean tryComplete() {
            if (!writeLock.tryLock()) return false;
            try {
                super.complete();
                return true;
            } finally {
                writeLock.unlock();
            }
        }
    }
}
