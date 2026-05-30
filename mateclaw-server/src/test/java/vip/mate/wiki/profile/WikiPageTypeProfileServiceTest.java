package vip.mate.wiki.profile;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import vip.mate.wiki.model.WikiPageTypeProfileEntity;
import vip.mate.wiki.repository.WikiPageTypeProfileMapper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Unit tests for {@link WikiPageTypeProfileService}: default profile loading,
 * KB profile resolution and fallback behaviour. The default profile is loaded
 * from the real classpath resource; the mapper is mocked.
 */
class WikiPageTypeProfileServiceTest {

    private WikiPageTypeProfileMapper mapper;
    private WikiPageTypeProfileService service;

    @BeforeEach
    void setUp() {
        mapper = mock(WikiPageTypeProfileMapper.class);
        service = new WikiPageTypeProfileService(mapper, new ObjectMapper());
        service.loadDefault();
    }

    @Test
    void defaultProfileReproducesBuiltInPageTypes() {
        WikiPageTypeProfile def = service.getDefaultProfile();
        assertTrue(def.hasPageType("concept"));
        assertTrue(def.hasPageType("person"));
        assertTrue(def.hasPageType("process"));
        assertTrue(def.hasPageType("other"));
        assertEquals(10, def.getPageTypes().size());
    }

    @Test
    void noConfiguredProfile_resolvesToDefault() {
        when(mapper.selectOne(any())).thenReturn(null);
        assertTrue(service.allowedPageTypes(42L).contains("concept"));
    }

    @Test
    void configuredProfile_isParsedAndVersionStamped() {
        WikiPageTypeProfileEntity row = new WikiPageTypeProfileEntity();
        row.setKbId(42L);
        row.setVersion(5);
        row.setEnabled(1);
        row.setConfigJson("{\"version\":1,\"pageTypes\":{\"episode\":{\"label\":\"Episode\"}}}");
        when(mapper.selectOne(any())).thenReturn(row);

        WikiPageTypeProfile resolved = service.resolveProfile(42L);
        assertTrue(resolved.hasPageType("episode"));
        assertFalse(resolved.hasPageType("concept"));
        assertEquals(5, resolved.getVersion());  // stamped from the row
    }

    @Test
    void unparseableConfig_fallsBackToDefault() {
        WikiPageTypeProfileEntity row = new WikiPageTypeProfileEntity();
        row.setKbId(42L);
        row.setEnabled(1);
        row.setConfigJson("{ not valid json");
        when(mapper.selectOne(any())).thenReturn(row);

        assertTrue(service.resolveProfile(42L).hasPageType("concept"));
    }

    @Test
    void normalizePageType_keepsKnown_downgradesUnknown() {
        when(mapper.selectOne(any())).thenReturn(null); // default profile
        assertEquals("person", service.normalizePageType(1L, "Person"));
        assertEquals("concept", service.normalizePageType(1L, "made-up-type"));
        assertEquals("concept", service.normalizePageType(1L, null));
    }
}
