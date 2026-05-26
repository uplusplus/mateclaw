package vip.mate.wiki.tool;

import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.model.WikiPageEntity;
import vip.mate.wiki.service.HybridRetriever;
import vip.mate.wiki.service.WikiKnowledgeBaseService;
import vip.mate.wiki.service.WikiPageService;
import vip.mate.wiki.service.WikiRawMaterialService;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Routing-level coverage for {@link WikiTool#resolveKbId(Long, String)}
 * (exercised through the public {@code wiki_list_pages} and
 * {@code wiki_list_kbs} surfaces).
 *
 * <p>The behaviour these tests pin in place is the fix for the upstream
 * "single-KB collapse" bug: when an agent reaches more than one knowledge
 * base, every wiki tool used to silently operate on whichever KB the
 * primary-fallback picked, with no way for the LLM to target a different
 * one. The {@code kbName} parameter and {@code wiki_list_kbs} discovery
 * tool together close that gap. These tests verify:
 * <ol>
 *   <li>blank {@code kbName} → routes to the primary KB (legacy behaviour
 *       preserved for single-KB agents);</li>
 *   <li>named {@code kbName} that matches a visible KB → routes to that
 *       specific KB, not the primary;</li>
 *   <li>named {@code kbName} that doesn't match → fail-closed error that
 *       names the bad pick and points the agent at
 *       {@code wiki_list_kbs} so the next attempt can pick a valid name;</li>
 *   <li>{@code wiki_list_kbs} surfaces every visible KB along with the
 *       {@code isPrimary} / {@code boundToAgent} flags the LLM needs to
 *       decide which to target.</li>
 * </ol>
 */
class WikiToolKbNameRoutingTest {

    private static final Long AGENT = 7L;
    private static final long PRIMARY_KB = 100L;
    private static final long OTHER_KB = 200L;

    private final WikiPageService pageService = mock(WikiPageService.class);
    private final WikiKnowledgeBaseService kbService = mock(WikiKnowledgeBaseService.class);
    private final WikiRawMaterialService rawService = mock(WikiRawMaterialService.class);
    private final HybridRetriever hybridRetriever = mock(HybridRetriever.class);
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final WikiTool tool = new WikiTool(pageService, kbService, rawService,
            hybridRetriever, objectMapper);

    private static WikiKnowledgeBaseEntity kb(long id, String name, Long agentId) {
        WikiKnowledgeBaseEntity entity = new WikiKnowledgeBaseEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setAgentId(agentId);
        entity.setPageCount(0);
        return entity;
    }

    private static WikiPageEntity page(String slug, String title) {
        WikiPageEntity entity = new WikiPageEntity();
        entity.setSlug(slug);
        entity.setTitle(title);
        entity.setPageType("user");
        return entity;
    }

    /** Mock listSummaries to return KB-specific page slugs so the test can
     *  prove which KB the tool actually queried. */
    private void wirePages() {
        when(pageService.listSummaries(eq(PRIMARY_KB))).thenReturn(List.of(
                page("primary-only-slug", "Primary KB Page")));
        when(pageService.listSummaries(eq(OTHER_KB))).thenReturn(List.of(
                page("other-only-slug", "Other KB Page")));
    }

    // ==================== wiki_list_pages routing ====================

    @Test
    @DisplayName("wiki_list_pages with blank kbName routes to the primary KB")
    void blankKbNameRoutesToPrimary() {
        wirePages();
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(kb(PRIMARY_KB, "Primary", AGENT));

        String json = tool.wiki_list_pages(AGENT, null, null);
        JSONObject obj = JSONUtil.parseObj(json);

        JSONArray pages = obj.getJSONArray("pages");
        assertThat(pages).hasSize(1);
        assertThat(pages.getJSONObject(0).getStr("slug")).isEqualTo("primary-only-slug");
    }

    @Test
    @DisplayName("wiki_list_pages with a known kbName routes to that KB instead of the primary")
    void namedKbNameRoutesToNamedKb() {
        wirePages();
        // Primary fallback still wired so the test would fail loudly if the
        // tool silently used it despite a non-blank kbName.
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(kb(PRIMARY_KB, "Primary", AGENT));
        when(kbService.findByName(AGENT, "Other")).thenReturn(kb(OTHER_KB, "Other", null));

        String json = tool.wiki_list_pages(AGENT, null, "Other");
        JSONObject obj = JSONUtil.parseObj(json);

        JSONArray pages = obj.getJSONArray("pages");
        assertThat(pages).hasSize(1);
        assertThat(pages.getJSONObject(0).getStr("slug")).isEqualTo("other-only-slug");
    }

    @Test
    @DisplayName("wiki_list_pages with an unknown kbName fails closed and names the bad pick")
    void unknownKbNameFailsClosed() {
        when(kbService.findByName(AGENT, "Bogus")).thenReturn(null);
        // Primary still mockable; the routing must NOT silently fall through.
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(kb(PRIMARY_KB, "Primary", AGENT));

        String json = tool.wiki_list_pages(AGENT, null, "Bogus");
        JSONObject obj = JSONUtil.parseObj(json);

        String err = obj.getStr("error");
        assertThat(err)
                .as("error message should name the bad pick and point at wiki_list_kbs")
                .contains("Bogus")
                .contains("wiki_list_kbs");
    }

    @Test
    @DisplayName("wiki_list_pages with no resolvable KB at all emits the no-KB error (legacy path)")
    void noResolvableKbReturnsLegacyError() {
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(null);

        String json = tool.wiki_list_pages(AGENT, null, null);
        JSONObject obj = JSONUtil.parseObj(json);

        assertThat(obj.getStr("error")).contains("No wiki knowledge base found");
    }

    // ==================== wiki_list_kbs ====================

    @Test
    @DisplayName("wiki_list_kbs enumerates every visible KB with isPrimary / boundToAgent")
    void wikiListKbsEnumeratesAll() {
        when(kbService.listByAgentId(AGENT)).thenReturn(List.of(
                kb(OTHER_KB, "Other", null),
                kb(PRIMARY_KB, "Primary", AGENT)));
        when(kbService.resolvePrimaryKb(AGENT)).thenReturn(kb(PRIMARY_KB, "Primary", AGENT));

        String json = tool.wiki_list_kbs(AGENT);
        JSONObject obj = JSONUtil.parseObj(json);

        assertThat(obj.getInt("kbCount")).isEqualTo(2);
        assertThat(obj.getStr("primary")).isEqualTo("Primary");

        JSONArray kbs = obj.getJSONArray("kbs");
        assertThat(kbs).hasSize(2);

        JSONObject other = kbs.getJSONObject(0);
        assertThat(other.getStr("name")).isEqualTo("Other");
        assertThat(other.getBool("isPrimary")).isFalse();
        assertThat(other.getBool("boundToAgent")).isFalse();

        JSONObject primary = kbs.getJSONObject(1);
        assertThat(primary.getStr("name")).isEqualTo("Primary");
        assertThat(primary.getBool("isPrimary")).isTrue();
        assertThat(primary.getBool("boundToAgent")).isTrue();
    }
}
