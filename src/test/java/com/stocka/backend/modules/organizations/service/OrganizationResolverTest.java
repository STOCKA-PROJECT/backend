package com.stocka.backend.modules.organizations.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationSlugHistory;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationSlugHistoryRepository;

@ExtendWith(MockitoExtension.class)
@DisplayName("OrganizationResolver")
class OrganizationResolverTest {

    @Mock private OrganizationRepository organizationRepository;
    @Mock private OrganizationSlugHistoryRepository slugHistoryRepository;

    @InjectMocks private OrganizationResolver sut;

    private Organization org;

    @BeforeEach
    void setUp() {
        org = new Organization().setId(1).setName("Acme").setSlug("acme");
    }

    @Nested
    @DisplayName("resolve")
    class Resolve {

        @Test
        @DisplayName("should return current resolution when slug matches an active org")
        void should_returnCurrent_when_slugMatchesActiveOrg() {
            when(organizationRepository.findBySlug("acme")).thenReturn(Optional.of(org));

            OrganizationResolver.Resolved result = sut.resolve("acme");

            assertThat(result.organization()).isSameAs(org);
            assertThat(result.historical()).isFalse();
            assertThat(result.currentSlug()).isEqualTo("acme");
            verify(slugHistoryRepository, never()).findByOldSlug(any());
        }

        @Test
        @DisplayName("should fall back to history when slug is not active")
        void should_fallBackToHistory_when_slugIsNotActive() {
            when(organizationRepository.findBySlug("old-acme")).thenReturn(Optional.empty());
            OrganizationSlugHistory history = new OrganizationSlugHistory()
                    .setOrganization(org)
                    .setOldSlug("old-acme");
            when(slugHistoryRepository.findByOldSlug("old-acme")).thenReturn(Optional.of(history));

            OrganizationResolver.Resolved result = sut.resolve("old-acme");

            assertThat(result.organization()).isSameAs(org);
            assertThat(result.historical()).isTrue();
            assertThat(result.currentSlug()).isEqualTo("acme");
        }

        @Test
        @DisplayName("should throw 404 when slug matches neither current nor history")
        void should_throw404_when_slugIsUnknown() {
            when(organizationRepository.findBySlug("ghost")).thenReturn(Optional.empty());
            when(slugHistoryRepository.findByOldSlug("ghost")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.resolve("ghost"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("should throw 404 for null slug")
        void should_throw404_for_nullSlug() {
            assertThatThrownBy(() -> sut.resolve(null))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }

        @Test
        @DisplayName("should throw 404 for blank slug")
        void should_throw404_for_blankSlug() {
            assertThatThrownBy(() -> sut.resolve("  "))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("requireCurrent")
    class RequireCurrent {

        @Test
        @DisplayName("should return the org when slug is current")
        void should_returnOrg_when_slugIsCurrent() {
            when(organizationRepository.findBySlug("acme")).thenReturn(Optional.of(org));

            Organization result = sut.requireCurrent("acme");

            assertThat(result).isSameAs(org);
        }

        @Test
        @DisplayName("should throw 404 when slug is historical only")
        void should_throw404_when_slugIsHistorical() {
            // requireCurrent does not consult history — historical slugs are 404 here so
            // REST endpoints stay cache-friendly. The frontend redirects via resolve().
            when(organizationRepository.findBySlug("old-acme")).thenReturn(Optional.empty());

            assertThatThrownBy(() -> sut.requireCurrent("old-acme"))
                    .isInstanceOf(ResponseStatusException.class)
                    .extracting(e -> ((ResponseStatusException) e).getStatusCode())
                    .isEqualTo(HttpStatus.NOT_FOUND);

            verify(slugHistoryRepository, never()).findByOldSlug(any());
        }

        @Test
        @DisplayName("should throw 404 for null slug")
        void should_throw404_for_nullSlug() {
            assertThatThrownBy(() -> sut.requireCurrent(null))
                    .isInstanceOf(ResponseStatusException.class);
        }
    }

}
