package com.stocka.backend.modules.search.repository;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.stocka.backend.modules.search.entity.SearchList;

/** Access to the per-organization XEYE lists. */
public interface SearchListRepository extends JpaRepository<SearchList, Integer> {

    /**
     * Pending lists whose edits have gone quiet: the last change happened before the cut-off. A row
     * without {@code lastChangeAt} (pending before V35) counts from {@code pendingSince}.
     *
     * @param before cut-off: only lists last changed before this instant
     * @return the pending lists ready for a training
     */
    @Query("SELECT l FROM SearchList l WHERE l.pendingSince IS NOT NULL"
            + " AND COALESCE(l.lastChangeAt, l.pendingSince) < :before")
    List<SearchList> findQuietPending(@Param("before") LocalDateTime before);

    /**
     * Every list with changes not trained yet, used by the nightly training.
     *
     * @return the pending lists
     */
    List<SearchList> findByPendingSinceIsNotNull();
}
