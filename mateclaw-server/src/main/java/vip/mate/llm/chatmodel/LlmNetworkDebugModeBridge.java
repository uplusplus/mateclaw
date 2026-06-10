package vip.mate.llm.chatmodel;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import vip.mate.system.service.SystemSettingService;

/**
 * Bridges the dynamic System Settings debugMode switch into static LLM
 * diagnostics code used by custom and Spring-AI chat clients.
 */
@Component
@RequiredArgsConstructor
public class LlmNetworkDebugModeBridge {

    private static final long CACHE_TTL_MS = 2_000L;

    private final SystemSettingService systemSettingService;

    private volatile boolean cachedEnabled;
    private volatile long cacheUntilMs;

    @PostConstruct
    void init() {
        LlmCallDiagnostics.configureNetworkDebug(this::isDebugModeEnabled);
    }

    private boolean isDebugModeEnabled() {
        long now = System.currentTimeMillis();
        if (now < cacheUntilMs) {
            return cachedEnabled;
        }
        try {
            cachedEnabled = systemSettingService.isDebugModeEnabled();
        } catch (Exception ignored) {
            cachedEnabled = false;
        }
        cacheUntilMs = now + CACHE_TTL_MS;
        return cachedEnabled;
    }
}
