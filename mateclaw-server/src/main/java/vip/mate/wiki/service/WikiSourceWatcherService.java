package vip.mate.wiki.service;

import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import vip.mate.wiki.WikiProperties;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;

/**
 * Watches each KB's configured source directory and auto-ingests new files.
 *
 * <p>Implemented as a periodic, single-owner scan rather than per-node OS file
 * watchers: {@link SchedulerLock} (ShedLock) ensures exactly one instance runs
 * a cycle, so a multi-instance deployment never double-ingests, and a periodic
 * scan is inherently restart-safe (it picks up anything missed while down). The
 * underlying {@link WikiDirectoryScanService} dedups by source path, so a
 * re-scan only ingests genuinely new files; deletes are never propagated.
 * Path validation (symlink resolution + allowed roots) is enforced by the
 * shared validator inside the scan.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class WikiSourceWatcherService {

    private final WikiKnowledgeBaseService kbService;
    private final WikiDirectoryScanService scanService;
    private final WikiProperties properties;

    public WikiSourceWatcherService(WikiKnowledgeBaseService kbService,
                                    WikiDirectoryScanService scanService,
                                    WikiProperties properties) {
        this.kbService = kbService;
        this.scanService = scanService;
        this.properties = properties;
    }

    /** Scheduled entry point — gated by config, serialized across instances. */
    @Scheduled(fixedDelayString = "${mate.wiki.watcher-interval-ms:300000}", initialDelay = 60_000)
    @SchedulerLock(name = "wiki-source-watcher", lockAtMostFor = "PT10M", lockAtLeastFor = "PT30S")
    public void scheduledScan() {
        if (!properties.isWatcherEnabled()) {
            return;
        }
        int added = runScanCycle();
        if (added > 0) {
            log.info("[WikiWatcher] scan cycle ingested {} new file(s)", added);
        }
    }

    /**
     * Scan every KB that has a source directory configured and return the total
     * number of new files ingested. Per-KB failures are logged and skipped so
     * one bad directory cannot stall the others.
     */
    public int runScanCycle() {
        int totalAdded = 0;
        for (WikiKnowledgeBaseEntity kb : kbService.listAll()) {
            String dir = kb.getSourceDirectory();
            if (dir == null || dir.isBlank()) {
                continue;
            }
            try {
                WikiDirectoryScanService.ScanResult result = scanService.scanDirectory(kb.getId(), dir);
                totalAdded += result.added();
                if (!result.errors().isEmpty()) {
                    log.warn("[WikiWatcher] KB {} scan reported issues: {}", kb.getId(), result.errors());
                }
            } catch (Exception e) {
                log.warn("[WikiWatcher] scan failed for KB {}: {}", kb.getId(), e.getMessage());
            }
        }
        return totalAdded;
    }
}
