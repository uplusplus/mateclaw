package vip.mate.wiki.profile;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import vip.mate.wiki.model.WikiPageTypeProfileEntity;
import vip.mate.wiki.repository.WikiPageTypeProfileMapper;

import java.io.InputStream;
import java.util.Set;

/**
 * Resolves the effective pageType profile for a knowledge base.
 *
 * <p>Resolution: the KB's single enabled {@code mate_wiki_page_type_profile}
 * row (parsed from {@code config_json}); when absent or unparseable, the
 * built-in default profile loaded from
 * {@code classpath:prompts/wiki/default-page-type-profile.json}. The default
 * is never stored as a row — existing KBs keep working with zero migration.
 *
 * @author MateClaw Team
 */
@Slf4j
@Service
public class WikiPageTypeProfileService {

    private static final String DEFAULT_RESOURCE = "prompts/wiki/default-page-type-profile.json";

    private final WikiPageTypeProfileMapper profileMapper;
    private final ObjectMapper objectMapper;

    /** Parsed once at startup; immutable thereafter. */
    private WikiPageTypeProfile defaultProfile;

    public WikiPageTypeProfileService(WikiPageTypeProfileMapper profileMapper, ObjectMapper objectMapper) {
        this.profileMapper = profileMapper;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void loadDefault() {
        try (InputStream in = new ClassPathResource(DEFAULT_RESOURCE).getInputStream()) {
            this.defaultProfile = objectMapper.readValue(in, WikiPageTypeProfile.class);
        } catch (Exception e) {
            log.error("[WikiProfile] Failed to load default pageType profile from {} — "
                    + "falling back to an empty profile", DEFAULT_RESOURCE, e);
            this.defaultProfile = new WikiPageTypeProfile();
        }
    }

    /** The built-in default profile (shared, do not mutate). */
    public WikiPageTypeProfile getDefaultProfile() {
        return defaultProfile;
    }

    /**
     * The effective profile for a KB: its enabled profile row, or the built-in
     * default when none is configured (or the stored config fails to parse).
     */
    public WikiPageTypeProfile resolveProfile(Long kbId) {
        if (kbId == null) {
            return defaultProfile;
        }
        WikiPageTypeProfileEntity row = profileMapper.selectOne(
                new LambdaQueryWrapper<WikiPageTypeProfileEntity>()
                        .eq(WikiPageTypeProfileEntity::getKbId, kbId)
                        .eq(WikiPageTypeProfileEntity::getEnabled, 1)
                        .last("LIMIT 1"));
        if (row == null || row.getConfigJson() == null || row.getConfigJson().isBlank()) {
            return defaultProfile;
        }
        try {
            WikiPageTypeProfile parsed = objectMapper.readValue(row.getConfigJson(), WikiPageTypeProfile.class);
            // Carry the stored row version so callers can stamp page.profile_version.
            parsed.setVersion(row.getVersion() != null ? row.getVersion() : parsed.getVersion());
            return parsed;
        } catch (Exception e) {
            log.warn("[WikiProfile] KB {} has an unparseable profile config — using default. {}",
                    kbId, e.getMessage());
            return defaultProfile;
        }
    }

    /** The set of pageType names allowed for a KB (lowercase). */
    public Set<String> allowedPageTypes(Long kbId) {
        return resolveProfile(kbId).getPageTypes().keySet();
    }

    /**
     * Normalise a routed/created pageType against the KB profile: a declared
     * type is returned as-is (lowercase); an unknown type is downgraded to the
     * profile's {@code fallbackType}. Never returns null.
     */
    public String normalizePageType(Long kbId, String pageType) {
        WikiPageTypeProfile profile = resolveProfile(kbId);
        if (pageType != null && profile.hasPageType(pageType)) {
            return pageType.trim().toLowerCase();
        }
        String fallback = profile.getFallbackType();
        return fallback == null ? "concept" : fallback.trim().toLowerCase();
    }
}
