package com.stocka.backend.modules.organizations.service;

import java.util.Optional;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.entity.OrganizationSlugHistory;
import com.stocka.backend.modules.organizations.repository.OrganizationRepository;
import com.stocka.backend.modules.organizations.repository.OrganizationSlugHistoryRepository;

/**
 * Resolves an organization from a slug that may be either the current one or a historical
 * value kept after a rename. REST endpoints under {@code /organizations/{orgSlug}/...}
 * require the current slug ({@link #requireCurrent(String)}); the lookup endpoint used by
 * the frontend accepts both via {@link #resolve(String)} so it can redirect old links.
 */
@Service
public class OrganizationResolver {

    private final OrganizationRepository organizationRepository;
    private final OrganizationSlugHistoryRepository slugHistoryRepository;

    public OrganizationResolver(
            OrganizationRepository organizationRepository,
            OrganizationSlugHistoryRepository slugHistoryRepository
    ) {
        this.organizationRepository = organizationRepository;
        this.slugHistoryRepository = slugHistoryRepository;
    }

    /**
     * Resolves a slug that may be either current or historical.
     *
     * @param slug requested slug; must be non-null
     * @return the resolved organization along with whether the slug is historical and the
     *     current slug to redirect to
     * @throws ResponseStatusException 404 when the slug matches neither a current
     *     organization nor any history entry
     */
    public Resolved resolve(String slug) {
        if (slug == null || slug.isBlank()) {
            throw notFound();
        }
        Optional<Organization> current = organizationRepository.findBySlug(slug);
        if (current.isPresent()) {
            Organization org = current.get();
            return new Resolved(org, false, org.getSlug());
        }
        Optional<OrganizationSlugHistory> history = slugHistoryRepository.findByOldSlug(slug);
        if (history.isPresent()) {
            Organization org = history.get().getOrganization();
            return new Resolved(org, true, org.getSlug());
        }
        throw notFound();
    }

    /**
     * Resolves a slug that must be the current one. Historical slugs are rejected so REST
     * endpoints can keep responses cache-friendly: the client should redirect first via
     * {@link #resolve(String)} (exposed as {@code GET /organizations/by-slug/{slug}}).
     *
     * @param slug requested slug; must be non-null
     * @return the matching organization
     * @throws ResponseStatusException 404 when the slug is unknown or historical
     */
    public Organization requireCurrent(String slug) {
        if (slug == null || slug.isBlank()) {
            throw notFound();
        }
        return organizationRepository.findBySlug(slug).orElseThrow(this::notFound);
    }

    private ResponseStatusException notFound() {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Organización no encontrada");
    }

    /**
     * Outcome of {@link OrganizationResolver#resolve(String)}.
     *
     * @param organization the resolved organization
     * @param historical {@code true} when the input slug was a previous slug, not the
     *     current one
     * @param currentSlug the up-to-date slug for {@code organization}; equals the input
     *     when {@code historical} is {@code false}
     */
    public record Resolved(Organization organization, boolean historical, String currentSlug) {
    }
}
