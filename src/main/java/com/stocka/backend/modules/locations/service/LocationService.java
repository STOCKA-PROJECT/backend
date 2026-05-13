package com.stocka.backend.modules.locations.service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stocka.backend.modules.locations.dto.CreateLocationDto;
import com.stocka.backend.modules.locations.dto.LocationBreadcrumbItemDto;
import com.stocka.backend.modules.locations.dto.LocationTreeNodeDto;
import com.stocka.backend.modules.locations.dto.UpdateLocationDto;
import com.stocka.backend.modules.locations.entity.Location;
import com.stocka.backend.modules.locations.repository.LocationRepository;
import com.stocka.backend.modules.notifications.events.ResourceKind;
import com.stocka.backend.modules.notifications.events.ResourceLifecycleEvent;
import com.stocka.backend.modules.notifications.preferences.entity.LifecycleAction;
import com.stocka.backend.modules.organizations.entity.Organization;
import com.stocka.backend.modules.organizations.service.OrganizationService;
import com.stocka.backend.modules.users.entity.User;

/**
 * CRUD and tree assembly for {@link Location} entities. All operations are scoped to a single
 * organization; cross-org accesses raise 404. Modifications use PATCH-partial semantics.
 */
@Service
public class LocationService {
    private static final int MAX_NAME_LENGTH = 255;

    private final LocationRepository locationRepository;
    private final OrganizationService organizationService;
    private final LocationCycleValidator cycleValidator;
    private final List<LocationContentChecker> contentCheckers;
    private final ApplicationEventPublisher events;

    public LocationService(
            LocationRepository locationRepository,
            OrganizationService organizationService,
            LocationCycleValidator cycleValidator,
            List<LocationContentChecker> contentCheckers,
            ApplicationEventPublisher events
    ) {
        this.locationRepository = locationRepository;
        this.organizationService = organizationService;
        this.cycleValidator = cycleValidator;
        this.contentCheckers = contentCheckers == null ? List.of() : contentCheckers;
        this.events = events;
    }

    @Transactional
    public Location create(Integer orgId, CreateLocationDto dto) {
        Organization org = organizationService.findById(orgId);
        String name = sanitizeName(dto.getName());

        Location parent = resolveParent(org, dto.getParentId());
        ensureUniqueName(org, parent, name, null);

        Location location = new Location()
                .setOrganization(org)
                .setName(name)
                .setDescription(emptyToNull(dto.getDescription()))
                .setParent(parent);
        Location saved = locationRepository.save(location);
        publishLifecycle(saved, LifecycleAction.CREATED);
        return saved;
    }

    public Location findInOrg(Integer orgId, Integer locationId) {
        Organization org = organizationService.findById(orgId);
        Location loc = locationRepository.findById(locationId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ubicación no encontrada"));
        if (!loc.getOrganization().getId().equals(org.getId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Ubicación no encontrada");
        }
        return loc;
    }

    public List<Location> listAll(Integer orgId) {
        Organization org = organizationService.findById(orgId);
        return locationRepository.findByOrganization(org);
    }

    public List<LocationTreeNodeDto> tree(Integer orgId) {
        Organization org = organizationService.findById(orgId);
        List<Location> all = locationRepository.findByOrganization(org);
        Map<Integer, List<Location>> byParent = new HashMap<>();
        for (Location l : all) {
            Integer parentId = l.getParent() == null ? null : l.getParent().getId();
            byParent.computeIfAbsent(parentId, k -> new ArrayList<>()).add(l);
        }
        return buildChildren(byParent, null);
    }

    public List<LocationBreadcrumbItemDto> breadcrumb(Location location) {
        List<LocationBreadcrumbItemDto> items = new ArrayList<>();
        Location cursor = location;
        while (cursor != null) {
            items.add(LocationBreadcrumbItemDto.from(cursor));
            cursor = cursor.getParent();
        }
        Collections.reverse(items);
        return items;
    }

    @Transactional
    public Location update(Integer orgId, Integer locationId, UpdateLocationDto dto) {
        Location location = findInOrg(orgId, locationId);
        Organization org = location.getOrganization();

        boolean nameChanged = false;
        boolean parentChanged = false;
        boolean descriptionChanged = false;
        String newName = location.getName();
        Location newParent = location.getParent();

        if (dto.getName() != null) {
            newName = sanitizeName(dto.getName());
            nameChanged = !newName.equals(location.getName());
        }
        if (dto.getDescription() != null) {
            String newDesc = emptyToNull(dto.getDescription());
            descriptionChanged = !java.util.Objects.equals(newDesc, location.getDescription());
            location.setDescription(newDesc);
        }
        if (Boolean.TRUE.equals(dto.getMoveToRoot())) {
            newParent = null;
            parentChanged = location.getParent() != null;
        } else if (dto.getParentId() != null) {
            newParent = resolveParent(org, dto.getParentId());
            cycleValidator.ensureNoCycle(location, newParent);
            parentChanged = location.getParent() == null
                    || !location.getParent().getId().equals(newParent.getId());
        }

        if (nameChanged || parentChanged) {
            ensureUniqueName(org, newParent, newName, location.getId());
            location.setName(newName);
            location.setParent(newParent);
        }

        Location saved = locationRepository.save(location);
        if (nameChanged || parentChanged || descriptionChanged) {
            publishLifecycle(saved, LifecycleAction.EDITED);
        }
        return saved;
    }

    @Transactional
    public void softDelete(Integer orgId, Integer locationId) {
        Location location = findInOrg(orgId, locationId);

        long subLocations = locationRepository.countByParent(location);
        long contentTotal = 0;
        StringBuilder details = new StringBuilder();
        if (subLocations > 0) {
            details.append(subLocations).append(" sub-ubicaciones");
            contentTotal += subLocations;
        }
        for (LocationContentChecker checker : contentCheckers) {
            long count = checker.countContent(location);
            if (count > 0) {
                if (details.length() > 0) details.append(", ");
                details.append(count).append(' ').append(checker.contentLabel());
                contentTotal += count;
            }
        }
        if (contentTotal > 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La ubicación contiene " + details + "; vacíala antes de eliminarla");
        }

        location.setDeletedAt(LocalDateTime.now());
        locationRepository.save(location);
        publishLifecycle(location, LifecycleAction.DELETED);
    }

    private List<LocationTreeNodeDto> buildChildren(Map<Integer, List<Location>> byParent, Integer parentId) {
        List<Location> children = byParent.get(parentId);
        if (children == null || children.isEmpty()) {
            return List.of();
        }
        children.sort(Comparator.comparing(Location::getName, String.CASE_INSENSITIVE_ORDER));
        List<LocationTreeNodeDto> out = new ArrayList<>(children.size());
        for (Location c : children) {
            out.add(LocationTreeNodeDto.from(c, buildChildren(byParent, c.getId())));
        }
        return out;
    }

    private Location resolveParent(Organization org, Integer parentId) {
        if (parentId == null) {
            return null;
        }
        Location parent = locationRepository.findById(parentId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Ubicación padre no encontrada"));
        if (!parent.getOrganization().getId().equals(org.getId())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "La ubicación padre pertenece a otra organización");
        }
        return parent;
    }

    private void ensureUniqueName(Organization org, Location parent, String name, Integer excludeId) {
        Optional<Location> existing = parent == null
                ? locationRepository.findByOrganizationAndParentIsNullAndName(org, name)
                : locationRepository.findByOrganizationAndParentAndName(org, parent, name);
        if (existing.isPresent() && (excludeId == null || !existing.get().getId().equals(excludeId))) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Ya existe una ubicación con ese nombre en este nivel");
        }
    }

    private String sanitizeName(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "El nombre es obligatorio");
        }
        String trimmed = raw.trim();
        if (trimmed.length() > MAX_NAME_LENGTH) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "El nombre no puede superar " + MAX_NAME_LENGTH + " caracteres");
        }
        return trimmed;
    }

    private String emptyToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private void publishLifecycle(Location location, LifecycleAction action) {
        User actor = currentUser();
        events.publishEvent(new ResourceLifecycleEvent(
                location.getOrganization().getId(),
                ResourceKind.LOCATION,
                action,
                location.getId(),
                location.getName(),
                actor == null ? null : actor.getId(),
                null
        ));
    }

    private static User currentUser() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !(auth.getPrincipal() instanceof User user)) {
            return null;
        }
        return user;
    }
}
