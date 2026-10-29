package com.stocka.backend.modules.search.repository;

import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_EMAIL;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.ADMIN_PASSWORD;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.buildMockMvc;
import static com.stocka.backend.modules.organizations.IntegrationTestSupport.login;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.stocka.backend.modules.organizations.IntegrationTestSupport;
import com.stocka.backend.modules.search.entity.SearchList;

/**
 * Runs the training queries of {@link SearchListRepository} against the real schema: which lists the
 * quiet-window tick and the nightly cron pick up.
 */
@SpringBootTest
@DisplayName("SearchListRepository (integration)")
class SearchListRepositoryIntegrationTest {

    private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 29, 18, 0);
    private static final LocalDateTime CUTOFF = NOW.minusMinutes(30);

    @Autowired private WebApplicationContext context;
    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private SearchListRepository lists;

    private final ObjectMapper om = new ObjectMapper();

    private MockMvc mockMvc;
    private String adminToken;
    private int orgCounter;

    @BeforeEach
    void setUp() throws Exception {
        mockMvc = buildMockMvc(context);
        IntegrationTestSupport.cleanDatabase(jdbcTemplate);
        adminToken = login(mockMvc, om, ADMIN_EMAIL, ADMIN_PASSWORD);
    }

    private Integer createOrg() throws Exception {
        String slug = "org-" + (++orgCounter);
        String body = mockMvc.perform(post("/organizations")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(om.writeValueAsString(Map.of("name", slug, "slug", slug))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return (Integer) om.readValue(body, Map.class).get("id");
    }

    private Integer saveList(LocalDateTime pendingSince, LocalDateTime lastChangeAt) throws Exception {
        Integer orgId = createOrg();
        lists.save(new SearchList()
                .setOrganizationId(orgId)
                .setXeyeListId(1000L + orgId)
                .setListName("stocka-org-" + orgId)
                .setPendingSince(pendingSince)
                .setLastChangeAt(lastChangeAt));
        return orgId;
    }

    @Nested
    @DisplayName("findQuietPending")
    class FindQuietPending {

        @Test
        @DisplayName("returns a pending list whose last change is older than the cut-off")
        void should_returnList_when_lastChangeBeforeCutoff() throws Exception {
            Integer quiet = saveList(NOW.minusHours(3), NOW.minusMinutes(31));

            assertThat(lists.findQuietPending(CUTOFF))
                    .extracting(SearchList::getOrganizationId).containsExactly(quiet);
        }

        @Test
        @DisplayName("skips a list still being edited even when its first change is old")
        void should_skipList_when_lastChangeRecent() throws Exception {
            saveList(NOW.minusHours(3), NOW.minusMinutes(5));

            assertThat(lists.findQuietPending(CUTOFF)).isEmpty();
        }

        @Test
        @DisplayName("skips a list changed exactly at the cut-off")
        void should_skipList_when_lastChangeEqualsCutoff() throws Exception {
            saveList(NOW.minusHours(3), CUTOFF);

            assertThat(lists.findQuietPending(CUTOFF)).isEmpty();
        }

        @Test
        @DisplayName("skips a list with nothing pending")
        void should_skipList_when_notPending() throws Exception {
            saveList(null, NOW.minusHours(3));

            assertThat(lists.findQuietPending(CUTOFF)).isEmpty();
        }

        @Test
        @DisplayName("counts from pendingSince a list pending before last_change_at existed")
        void should_fallBackToPendingSince_when_lastChangeMissing() throws Exception {
            Integer legacyQuiet = saveList(NOW.minusMinutes(40), null);
            saveList(NOW.minusMinutes(10), null);

            assertThat(lists.findQuietPending(CUTOFF))
                    .extracting(SearchList::getOrganizationId).containsExactly(legacyQuiet);
        }
    }

    @Nested
    @DisplayName("findByPendingSinceIsNotNull")
    class FindPending {

        @Test
        @DisplayName("returns every pending list, quiet or still being edited")
        void should_returnAllPending() throws Exception {
            Integer quiet = saveList(NOW.minusHours(3), NOW.minusMinutes(40));
            Integer busy = saveList(NOW.minusHours(3), NOW.minusMinutes(1));
            saveList(null, NOW.minusHours(3));

            assertThat(lists.findByPendingSinceIsNotNull())
                    .extracting(SearchList::getOrganizationId).containsExactlyInAnyOrder(quiet, busy);
        }

        @Test
        @DisplayName("returns nothing when no list is pending")
        void should_returnEmpty_when_nothingPending() throws Exception {
            saveList(null, NOW.minusHours(3));

            assertThat(lists.findByPendingSinceIsNotNull()).isEmpty();
        }
    }
}
