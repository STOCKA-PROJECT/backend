package com.stocka.backend.modules.search.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

import com.stocka.backend.modules.search.entity.SearchList;

/** Access to the per-organization XEYE lists. */
public interface SearchListRepository extends JpaRepository<SearchList, Integer> {

    /**
     * Lists whose last change is old enough to be worth a training.
     *
     * @param before cut-off: only lists pending since before this instant
     * @return the lists with a pending training older than {@code before}
     */
    List<SearchList> findByPendingSinceBefore(LocalDateTime before);
}
