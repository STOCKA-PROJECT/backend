package com.stocka.backend.modules.search.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.stocka.backend.modules.search.entity.SearchEntry;

/** Access to the piece ↔ XEYE element links. */
public interface SearchEntryRepository extends JpaRepository<SearchEntry, Integer> {

    List<SearchEntry> findByOrganizationId(Integer organizationId);

    long countByOrganizationId(Integer organizationId);

    void deleteByOrganizationId(Integer organizationId);
}
