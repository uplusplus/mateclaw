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
import java.util.Map;
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
     * Render the KB's allowed page types as a prompt fragment, one per line
     * with description and required-metadata hints, e.g.
     * {@code - episode: a dated event (required metadata: event_type, event_date)}.
     * Injected into the route / batch-create prompts so the LLM only emits
     * types the KB recognises.
     */
    public String describeForPrompt(Long kbId) {
        WikiPageTypeProfile profile = resolveProfile(kbId);
        StringBuilder sb = new StringBuilder();
        profile.getPageTypes().forEach((name, def) -> {
            sb.append("- ").append(name);
            if (def != null && def.getDescription() != null && !def.getDescription().isBlank()) {
                sb.append(": ").append(def.getDescription().trim());
            }
            if (def != null && def.getSchema() != null) {
                java.util.List<String> required = def.getSchema().entrySet().stream()
                        .filter(e -> e.getValue() != null && e.getValue().isRequired())
                        .map(Map.Entry::getKey)
                        .toList();
                if (!required.isEmpty()) {
                    sb.append(" (required metadata: ").append(String.join(", ", required)).append(")");
                }
            }
            sb.append('\n');
        });
        return sb.toString().trim();
    }

    /** The enabled profile row for a KB, or {@code null} when none configured. */
    public WikiPageTypeProfileEntity findEnabledRow(Long kbId) {
        if (kbId == null) {
            return null;
        }
        return profileMapper.selectOne(
                new LambdaQueryWrapper<WikiPageTypeProfileEntity>()
                        .eq(WikiPageTypeProfileEntity::getKbId, kbId)
                        .eq(WikiPageTypeProfileEntity::getEnabled, 1)
                        .last("LIMIT 1"));
    }

    /**
     * Persist a KB profile. Parses {@code configJson} first (rejecting invalid
     * JSON), then upserts the KB's single enabled row — updating in place and
     * bumping its version when one exists, else inserting a new enabled row.
     *
     * @throws IllegalArgumentException when {@code configJson} does not parse
     */
    public void saveProfile(Long kbId, String name, String configJson) {
        try {
            objectMapper.readValue(configJson, WikiPageTypeProfile.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid profile config JSON: " + e.getMessage());
        }
        WikiPageTypeProfileEntity existing = findEnabledRow(kbId);
        if (existing != null) {
            existing.setConfigJson(configJson);
            if (name != null && !name.isBlank()) {
                existing.setName(name);
            }
            existing.setVersion((existing.getVersion() == null ? 1 : existing.getVersion()) + 1);
            profileMapper.updateById(existing);
        } else {
            WikiPageTypeProfileEntity row = new WikiPageTypeProfileEntity();
            row.setKbId(kbId);
            row.setName(name == null || name.isBlank() ? "default" : name);
            row.setVersion(1);
            row.setConfigJson(configJson);
            row.setEnabled(1);
            profileMapper.insert(row);
        }
    }

    /**
     * Reset a KB to the built-in default by removing its profile rows, so
     * {@link #resolveProfile} falls back to the default. Logical delete.
     */
    public void resetToDefault(Long kbId) {
        if (kbId == null) {
            return;
        }
        profileMapper.delete(new LambdaQueryWrapper<WikiPageTypeProfileEntity>()
                .eq(WikiPageTypeProfileEntity::getKbId, kbId));
    }

    /**
     * Structurally validate a profile JSON without persisting it. Returns a
     * list of human-readable issues; empty means valid.
     */
    public java.util.List<String> validateProfileJson(String configJson) {
        java.util.List<String> issues = new java.util.ArrayList<>();
        WikiPageTypeProfile profile;
        try {
            profile = objectMapper.readValue(configJson, WikiPageTypeProfile.class);
        } catch (Exception e) {
            issues.add("Invalid JSON: " + e.getMessage());
            return issues;
        }
        if (profile.getPageTypes() == null || profile.getPageTypes().isEmpty()) {
            issues.add("Profile declares no pageTypes");
            return issues;
        }
        java.util.Set<String> validTypes = java.util.Set.of(
                "string", "number", "boolean", "date", "enum", "string_array");
        profile.getPageTypes().forEach((typeName, def) -> {
            if (def.getSchema() == null) {
                return;
            }
            def.getSchema().forEach((fieldName, fieldSchema) -> {
                String t = fieldSchema.getType();
                if (t == null || !validTypes.contains(t.trim().toLowerCase())) {
                    issues.add(typeName + "." + fieldName + ": unknown field type '" + t + "'");
                } else if ("enum".equalsIgnoreCase(t.trim())
                        && (fieldSchema.getValues() == null || fieldSchema.getValues().isEmpty())) {
                    issues.add(typeName + "." + fieldName + ": enum field declares no values");
                }
            });
        });
        return issues;
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
