package vip.mate.wiki.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end test of the source watcher's scan cycle against H2: new files in a
 * KB's source directory are auto-ingested, and a re-scan is idempotent (dedup
 * by source path). Auto-processing is disabled so the test stays model-free.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration/h2",
                "mateclaw.feature-flag.refresh-ms=999999",
                "mate.wiki.auto-process-on-upload=false"
        }
)
class WikiSourceWatcherServiceE2ETest {

    @Autowired
    private WikiSourceWatcherService watcherService;
    @Autowired
    private WikiKnowledgeBaseService kbService;

    private static final java.util.concurrent.atomic.AtomicLong SEQ =
            new java.util.concurrent.atomic.AtomicLong(System.nanoTime());

    @Test
    void scanCycleIngestsNewFiles_thenDedups(@TempDir Path sourceDir) throws IOException {
        Files.writeString(sourceDir.resolve("note-a.md"), "# Note A\n\ncontent a");
        Files.writeString(sourceDir.resolve("note-b.md"), "# Note B\n\ncontent b");

        WikiKnowledgeBaseEntity kb = kbService.create(
                "watcher-" + SEQ.incrementAndGet(), "test", null);
        kbService.updateSourceDirectory(kb.getId(), sourceDir.toString());

        // First cycle ingests both new files.
        int firstAdded = watcherService.runScanCycle();
        assertTrue(firstAdded >= 2, "expected >= 2 new files, got " + firstAdded);

        // A new file appears; the next cycle ingests only it (existing files dedup).
        Files.writeString(sourceDir.resolve("note-c.md"), "# Note C\n\ncontent c");
        int secondAdded = watcherService.runScanCycle();
        assertEquals(1, secondAdded, "only the newly added file should ingest");
    }

    @Test
    void kbsWithoutSourceDirectory_areSkipped() {
        // A KB with no source directory must not cause errors in the cycle.
        kbService.create("nodir-" + SEQ.incrementAndGet(), "test", null);
        // Should complete without throwing (count is non-negative).
        assertTrue(watcherService.runScanCycle() >= 0);
    }
}
