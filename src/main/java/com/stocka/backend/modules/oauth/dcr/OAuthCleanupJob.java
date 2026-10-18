package com.stocka.backend.modules.oauth.dcr;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.stocka.backend.modules.oauth.OAuthServerProperties;

/**
 * Nightly housekeeping of the authorization server tables: authorizations whose tokens have all
 * expired, dynamically registered clients that never obtained an authorization within
 * {@code stocka.oauth.dcr.unused-client-ttl}, and CIMD mappings not seen for a year.
 */
@Component
public class OAuthCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(OAuthCleanupJob.class);

    private final JdbcTemplate jdbc;
    private final OAuthServerProperties props;
    private final Clock clock;

    @Autowired
    public OAuthCleanupJob(JdbcTemplate jdbc, OAuthServerProperties props) {
        this(jdbc, props, Clock.systemUTC());
    }

    OAuthCleanupJob(JdbcTemplate jdbc, OAuthServerProperties props, Clock clock) {
        this.jdbc = jdbc;
        this.props = props;
        this.clock = clock;
    }

    @Scheduled(cron = "${stocka.oauth.cleanup-cron:0 45 3 * * *}")
    @Transactional
    public void run() {
        Timestamp now = Timestamp.from(clock.instant());
        int authorizations = jdbc.update("""
                DELETE FROM oauth2_authorization
                 WHERE (authorization_code_expires_at IS NULL OR authorization_code_expires_at < ?)
                   AND (access_token_expires_at IS NULL OR access_token_expires_at < ?)
                   AND (refresh_token_expires_at IS NULL OR refresh_token_expires_at < ?)
                """, now, now, now);
        Timestamp unusedBefore = Timestamp.from(clock.instant().minus(props.getDcr().getUnusedClientTtl()));
        int clients = jdbc.update("""
                DELETE FROM oauth2_registered_client
                 WHERE registered_via = 'DCR'
                   AND client_id_issued_at < ?
                   AND NOT EXISTS (SELECT 1 FROM oauth2_authorization a WHERE a.registered_client_id = oauth2_registered_client.id)
                   AND NOT EXISTS (SELECT 1 FROM oauth2_authorization_consent c WHERE c.registered_client_id = oauth2_registered_client.id)
                """, unusedBefore);
        Timestamp staleCimd = Timestamp.from(Instant.from(clock.instant()).minusSeconds(365L * 24 * 3600));
        int cimd = jdbc.update("""
                DELETE FROM oauth2_cimd_clients
                 WHERE last_seen_at < ?
                   AND NOT EXISTS (SELECT 1 FROM oauth2_authorization a WHERE a.registered_client_id = oauth2_cimd_clients.id)
                """, staleCimd);
        if (authorizations + clients + cimd > 0) {
            log.info("oauth_cleanup authorizations={} clients={} cimd={}", authorizations, clients, cimd);
        }
    }
}
