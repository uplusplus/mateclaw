package vip.mate.tool.builtin;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import vip.mate.i18n.I18nService;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

/**
 * Regression test for the single-line oversized-file bug: a file whose only
 * line exceeds the output byte budget used to return empty content with
 * readLines=0 and a continuation hint that never advanced — an infinite retry
 * loop. The tool must instead return a clipped, clearly-flagged result and make
 * progress.
 */
class ReadFileToolLargeLineTest {

    private ReadFileTool tool;

    @BeforeEach
    void setUp() {
        // Default answer echoes msg(key, ...) back as the key, so assertions stay
        // locale-agnostic and the answer covers every varargs arity uniformly.
        I18nService i18n = mock(I18nService.class, inv ->
                "msg".equals(inv.getMethod().getName()) ? inv.getArgument(0) : null);
        tool = new ReadFileTool(i18n);
        // Ensure no workspace boundary is active so absolute temp paths validate.
        ToolExecutionContext.clear();
    }

    @AfterEach
    void tearDown() {
        ToolExecutionContext.clear();
    }

    @Test
    @DisplayName("single line larger than the 30KB budget returns clipped content, not empty + infinite loop")
    void singleOversizedLine_returnsClippedContent(@TempDir Path dir) throws Exception {
        // ~40KB single-line JSON array on one physical line.
        StringBuilder json = new StringBuilder("[");
        for (int i = 0; i < 4000; i++) {
            if (i > 0) json.append(',');
            json.append("\"item-").append(i).append("\"");
        }
        json.append(']');
        Path file = dir.resolve("big.json");
        Files.writeString(file, json.toString(), StandardCharsets.UTF_8);

        String raw = tool.read_file(file.toString(), null, null, null);
        JSONObject res = JSONUtil.parseObj(raw);

        assertFalse(res.getBool("error", false), "should not be an error result");
        assertTrue(res.getBool("truncated"), "should be marked truncated");
        assertTrue(res.getBool("lineTruncated", false), "should flag in-line truncation");
        // The bug: content was empty and readLines was 0.
        assertEquals(1, res.getInt("readLines"), "must count the clipped line as read");
        assertTrue(res.getStr("content").length() > 1000, "content must carry the clipped line, not be empty");
        assertEquals("tool.read_file.line_truncated", res.getStr("message"));
    }

    @Test
    @DisplayName("normal multi-line file reads fully without truncation")
    void smallFile_readsFully(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("small.txt");
        Files.writeString(file, "alpha\nbeta\ngamma\n", StandardCharsets.UTF_8);

        String raw = tool.read_file(file.toString(), null, null, null);
        JSONObject res = JSONUtil.parseObj(raw);

        assertFalse(res.getBool("truncated"), "small file should not truncate");
        assertEquals(3, res.getInt("readLines"));
        assertTrue(res.getStr("content").contains("beta"));
    }
}
