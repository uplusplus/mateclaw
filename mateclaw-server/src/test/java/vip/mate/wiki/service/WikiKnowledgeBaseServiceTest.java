package vip.mate.wiki.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.model.WikiKnowledgeBaseEntity;
import vip.mate.wiki.repository.WikiKnowledgeBaseMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WikiKnowledgeBaseService#resolvePrimaryKb(Long)}.
 *
 * <p>{@code listByAgentId} returns both the agent's own KBs and shared
 * (agent-less) KBs, ordered by {@code update_time} descending. A naive
 * {@code get(0)} pick therefore hands back whichever KB was touched most
 * recently — which can be an unrelated shared KB. {@code resolvePrimaryKb}
 * must still return the KB actually bound to the agent.
 */
class WikiKnowledgeBaseServiceTest {

    private final WikiKnowledgeBaseMapper kbMapper = mock(WikiKnowledgeBaseMapper.class);
    private final WikiKnowledgeBaseService service = new WikiKnowledgeBaseService(
            kbMapper, null, null, null, null, null);

    private static WikiKnowledgeBaseEntity kb(long id, Long agentId) {
        return kb(id, agentId, null);
    }

    private static WikiKnowledgeBaseEntity kb(long id, Long agentId, String name) {
        WikiKnowledgeBaseEntity entity = new WikiKnowledgeBaseEntity();
        entity.setId(id);
        entity.setAgentId(agentId);
        entity.setName(name);
        return entity;
    }

    @Test
    @DisplayName("prefers the agent's bound KB even when a shared KB was updated more recently")
    void prefersBoundKbOverNewerSharedKb() {
        // listByAgentId order is update_time DESC: two shared KBs precede the bound one.
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(900L, null),
                kb(800L, null),
                kb(100L, 7L)));

        assertThat(service.resolvePrimaryKb(7L)).isNotNull();
        assertThat(service.resolvePrimaryKb(7L).getId()).isEqualTo(100L);
    }

    @Test
    @DisplayName("falls back to the most recent shared KB when the agent has no bound KB")
    void fallsBackToSharedKbWhenNoneBound() {
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(900L, null),
                kb(800L, null)));

        assertThat(service.resolvePrimaryKb(7L).getId()).isEqualTo(900L);
    }

    @Test
    @DisplayName("returns null when the agent can reach no knowledge base")
    void returnsNullWhenNoKb() {
        when(kbMapper.selectList(any())).thenReturn(List.of());

        assertThat(service.resolvePrimaryKb(7L)).isNull();
    }

    // ==================== findByName ====================
    //
    // The wiki tools added a kbName parameter so the LLM can target a
    // non-primary KB. findByName is the resolution layer behind that
    // parameter — it must restrict the match to KBs visible to the agent
    // and refuse to silently fall through to the primary on a miss, so a
    // bad pick surfaces as a clear "use wiki_list_kbs" hint instead of
    // routing to the wrong KB.

    @Test
    @DisplayName("findByName matches by exact name within the agent's visible set")
    void findByNameMatchesVisibleKb() {
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(900L, null, "Shared Docs"),
                kb(100L, 7L, "Agent Personal KB")));

        WikiKnowledgeBaseEntity hit = service.findByName(7L, "Agent Personal KB");
        assertThat(hit).isNotNull();
        assertThat(hit.getId()).isEqualTo(100L);

        WikiKnowledgeBaseEntity sharedHit = service.findByName(7L, "Shared Docs");
        assertThat(sharedHit).isNotNull();
        assertThat(sharedHit.getId()).isEqualTo(900L);
    }

    @Test
    @DisplayName("findByName returns null when name does not match any visible KB")
    void findByNameMissReturnsNull() {
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(900L, null, "Shared Docs"),
                kb(100L, 7L, "Agent Personal KB")));

        assertThat(service.findByName(7L, "Nonexistent KB")).isNull();
    }

    @Test
    @DisplayName("findByName is case-sensitive — LLM must copy the name verbatim")
    void findByNameIsCaseSensitive() {
        when(kbMapper.selectList(any())).thenReturn(List.of(
                kb(100L, 7L, "Agent Personal KB")));

        assertThat(service.findByName(7L, "agent personal kb")).isNull();
        assertThat(service.findByName(7L, "Agent Personal KB")).isNotNull();
    }

    @Test
    @DisplayName("findByName returns null for blank / null kbName")
    void findByNameBlankReturnsNull() {
        assertThat(service.findByName(7L, null)).isNull();
        assertThat(service.findByName(7L, "")).isNull();
        assertThat(service.findByName(7L, "   ")).isNull();
    }
}
