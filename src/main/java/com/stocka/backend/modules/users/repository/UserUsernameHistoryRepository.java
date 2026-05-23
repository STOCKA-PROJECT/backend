package com.stocka.backend.modules.users.repository;

import java.util.Optional;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.stocka.backend.modules.users.entity.User;
import com.stocka.backend.modules.users.entity.UserUsernameHistory;

@Repository
public interface UserUsernameHistoryRepository extends CrudRepository<UserUsernameHistory, Long> {

    /**
     * Looks up a username that some user used previously. The associated user is eagerly
     * loaded via {@code JOIN FETCH} so callers can inspect it without an open session. The
     * {@code @SQLRestriction} on {@link User} hides rows whose owner is soft-deleted, which
     * matches the policy of releasing the slot when the owner is gone.
     *
     * @param oldUsername the historical username
     * @return the matching history row with its user initialized, if any
     */
    @Query("SELECT h FROM UserUsernameHistory h JOIN FETCH h.user WHERE h.oldUsername = :oldUsername")
    Optional<UserUsernameHistory> findByOldUsername(@Param("oldUsername") String oldUsername);

    /**
     * Tells whether a username appears anywhere in the history table.
     *
     * @param oldUsername candidate username
     * @return {@code true} when at least one user has used it
     */
    boolean existsByOldUsername(String oldUsername);

    /**
     * Removes every history row that belongs to the given user. Used on soft-delete to
     * release the historical usernames so other users can claim them.
     *
     * @param user the user whose history must be cleared
     * @return the number of rows removed
     */
    long deleteByUser(User user);
}
