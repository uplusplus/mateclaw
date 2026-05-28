package vip.mate.wiki.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import vip.mate.wiki.model.WikiPageEntity;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Single source of truth for wikilink extraction and resolution-state
 * computation. The page viewer's TypeScript {@code resolveWikilink} mirrors
 * the matching semantics; both must stay in lockstep so users do not see a
 * visibly-working link that the lint marks as broken (or vice-versa).
 * <p>
 * Resolution rule (intentionally narrow):
 *
 * <ul>
 *   <li>Extract every {@code [[target]]} or {@code [[target|display]]} from
 *       content, skipping fenced and inline code spans.</li>
 *   <li>For each occurrence keep only {@code target.toLowerCase().trim()} —
 *       no {@link WikiPageService#canonicalSlug} fuzzy collapse, no
 *       title→slug guessing. The lint flags a link as broken iff no active
 *       KB page has {@code page.slug.equalsIgnoreCase(target)}.</li>
 * </ul>
 *
 * The strict comparison surfaces real authoring mistakes (typo in slug,
 * stale ref to a renamed page) rather than silently papering over them with
 * canonical-form coercion. Phase 1's frontend resolver keeps a title
 * fallback for legacy {@code [[Page Title]]} content so the visible link
 * still navigates, but that fallback is intentionally absent here — title-
 * form authors are expected to migrate as the slug-first prompt rollout
 * lands in Phase 3.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WikiLinkService {

    /**
     * Matches every {@code [[...]]} occurrence. Non-greedy on the inside so
     * pathological inputs like {@code [[a]] [[b]]} resolve as two separate
     * links rather than one giant link {@code "a]] [[b"}.
     */
    private static final Pattern WIKILINK = Pattern.compile("\\[\\[([^\\]]+?)]]");

    /**
     * Matches a fenced code block. Anchored to {@code ^```} on a line so a
     * stray triple-backtick mid-paragraph does not flip the world into "in
     * code" mode and swallow real wikilinks for the rest of the document.
     * Captures the opening fence and content lazily; the matched range is
     * removed wholesale before wikilink extraction.
     */
    private static final Pattern FENCED_CODE = Pattern.compile(
            "(?m)^```[\\s\\S]*?^```", Pattern.MULTILINE);

    /**
     * Matches inline {@code `...`} spans. Non-greedy so adjacent inline spans
     * are handled as separate matches.
     */
    private static final Pattern INLINE_CODE = Pattern.compile("`[^`\\n]*?`");

    /** Hard cap matching the frontend's MAX_SLUG_LEN — see wikilink.ts. */
    private static final int MAX_TARGET_LEN = 256;

    private final ObjectMapper objectMapper;

    /**
     * Extract every wikilink target string from {@code content}, normalised
     * to lowercase + trimmed, with code blocks stripped first.
     * <p>
     * Returns an insertion-ordered set so callers that serialize to JSON get
     * a stable order (helps diffability of {@code broken_links} fields across
     * scans and makes audit logs easier to read).
     *
     * @param content full markdown body; {@code null} or blank returns empty
     * @return targets as written (before {@code |} alias), lowercased
     */
    public Set<String> extractOutlinks(String content) {
        if (content == null || content.isBlank()) return Collections.emptySet();

        // Strip code first so inline / fenced examples that show literal
        // [[wikilink]] syntax stay literal. Replacement with an equal-length
        // run of spaces would be more correct (preserves positions for any
        // future error reporting) but isn't worth the complexity here — we
        // only need the targets.
        String stripped = FENCED_CODE.matcher(content).replaceAll("");
        stripped = INLINE_CODE.matcher(stripped).replaceAll("");

        Set<String> targets = new LinkedHashSet<>();
        Matcher m = WIKILINK.matcher(stripped);
        while (m.find()) {
            String raw = m.group(1).trim();
            if (raw.isEmpty()) continue;
            int pipe = raw.indexOf('|');
            String target = (pipe >= 0 ? raw.substring(0, pipe) : raw).trim();
            if (target.isEmpty() || target.length() > MAX_TARGET_LEN) continue;
            // Lowercase here so {@link #computeBrokenLinks} can do exact
            // equality against {@code page.slug.toLowerCase()} without an
            // extra normalisation step per page.
            targets.add(target.toLowerCase(Locale.ROOT));
        }
        return targets;
    }

    /**
     * Compute the broken subset of {@code outlinks} given the KB's active
     * page slug set. {@code activeSlugs} is expected to be already lowercased
     * — callers compute it once per scan and reuse across pages.
     *
     * @return targets that have no matching page slug, in the same insertion
     *         order as {@code outlinks}
     */
    public List<String> computeBrokenLinks(Set<String> outlinks, Set<String> activeSlugsLower) {
        if (outlinks == null || outlinks.isEmpty()) return Collections.emptyList();
        if (activeSlugsLower == null) activeSlugsLower = Collections.emptySet();
        List<String> broken = new ArrayList<>();
        for (String t : outlinks) {
            if (!activeSlugsLower.contains(t)) broken.add(t);
        }
        return broken;
    }

    /**
     * Convenience: extract + compute in one call. Used from
     * {@code WikiPageService.save/update} where both fields are written in
     * the same transaction.
     */
    public LinkAnalysis analyze(String content, Set<String> activeSlugsLower) {
        Set<String> outlinks = extractOutlinks(content);
        List<String> broken = computeBrokenLinks(outlinks, activeSlugsLower);
        return new LinkAnalysis(new ArrayList<>(outlinks), broken);
    }

    /** Pair returned by {@link #analyze(String, Set)}. */
    public record LinkAnalysis(List<String> outgoingLinks, List<String> brokenLinks) {}

    /** Serialize a list to JSON for persistence. Best-effort: never throws. */
    public String toJsonArray(List<String> values) {
        if (values == null || values.isEmpty()) return "[]";
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            log.warn("[WikiLink] Failed to serialize list to JSON, falling back to empty: {}", e.getMessage());
            return "[]";
        }
    }

    /** Parse a JSON array back into a list. Best-effort: never throws. */
    public List<String> fromJsonArray(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("[WikiLink] Failed to parse JSON array, treating as empty: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Compute the lowercase slug set for a KB from a pre-loaded page list.
     * Centralised so both single-page save paths and the KB-wide scan use the
     * same definition of "active page".
     */
    public Set<String> lowercaseSlugSet(List<WikiPageEntity> pages) {
        if (pages == null || pages.isEmpty()) return Collections.emptySet();
        return pages.stream()
                .map(WikiPageEntity::getSlug)
                .filter(s -> s != null && !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }
}
