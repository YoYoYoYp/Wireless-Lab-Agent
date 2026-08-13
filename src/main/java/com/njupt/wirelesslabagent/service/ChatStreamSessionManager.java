package com.njupt.wirelesslabagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@Slf4j
public class ChatStreamSessionManager {

    private final Map<String, ChatStreamSession> sessions = new ConcurrentHashMap<>();

    public void register(String streamId, String userId, String chatId) {
        ChatStreamSession session = new ChatStreamSession(streamId, userId, chatId);
        ChatStreamSession previous = sessions.put(streamId, session);
        if (previous != null) {
            previous.requestStop("stream-replaced");
        }
        log.info("[chat-stream] registered streamId={}, userId={}, chatId={}", streamId, userId, chatId);
    }

    public Flux<String> bind(String streamId, Flux<String> upstream) {
        ChatStreamSession session = sessions.get(streamId);
        if (session == null) {
            return Flux.error(new IllegalStateException("stream session not found: " + streamId));
        }

        Mono<Void> stopSignal = session.stopSignal();
        return upstream
                .takeUntilOther(stopSignal)
                .doOnCancel(() -> log.info("[chat-stream] client canceled streamId={}", streamId))
                .doFinally(signalType -> {
                    sessions.remove(streamId, session);
                    log.info("[chat-stream] closed streamId={}, signalType={}, stopped={}",
                            streamId, signalType, session.isStopRequested());
                });
    }

    public boolean stop(String streamId, String userId) {
        ChatStreamSession session = sessions.get(streamId);
        if (session == null) {
            return false;
        }
        if (!Objects.equals(session.userId, userId)) {
            log.warn("[chat-stream] reject stop, streamId={}, requestUserId={}, owner={}",
                    streamId, userId, session.userId);
            return false;
        }
        return session.requestStop("manual-stop");
    }

    private final class ChatStreamSession {
        private final String streamId;
        private final String userId;
        private final String chatId;
        private final Sinks.Empty<Void> stopSink = Sinks.empty();
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);

        private ChatStreamSession(String streamId, String userId, String chatId) {
            this.streamId = streamId;
            this.userId = userId;
            this.chatId = chatId;
        }

        private Mono<Void> stopSignal() {
            return stopSink.asMono();
        }

        private boolean isStopRequested() {
            return stopRequested.get();
        }

        private boolean requestStop(String reason) {
            boolean firstRequest = stopRequested.compareAndSet(false, true);
            if (firstRequest) {
                stopSink.tryEmitEmpty();
                log.info("[chat-stream] stop requested, streamId={}, chatId={}, reason={}",
                        streamId, chatId, reason);
            }
            return firstRequest;
        }
    }
}
