package com.stocka.backend.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

/**
 * Static guard over the Flyway migration set. These checks need no database and exist to catch, at
 * build time, the class of mistake that takes production down at startup: two branches independently
 * claiming the same version number. Prod runs Flyway with {@code validate-on-migrate=true}, so a
 * duplicate version aborts the whole context before the web server starts.
 *
 * @see <a href="file:../../../resources/db/migration">src/main/resources/db/migration</a>
 */
@DisplayName("Flyway migrations")
class FlywayMigrationsTest {

    /** Versioned migration filename, e.g. {@code V13__missing_id_sequences.sql}. */
    private static final Pattern VERSIONED = Pattern.compile("^V(\\d+)__.+\\.sql$");

    private static List<String> migrationFileNames() throws IOException {
        Resource[] resources = new PathMatchingResourcePatternResolver()
                .getResources("classpath*:db/migration/*.sql");
        List<String> names = new ArrayList<>();
        for (Resource resource : resources) {
            names.add(resource.getFilename());
        }
        return names;
    }

    @Nested
    @DisplayName("filename conventions")
    class FileNames {

        @Test
        @DisplayName("should discover the migration set (test is not vacuously passing)")
        void should_discover_migrations() throws IOException {
            assertThat(migrationFileNames())
                    .as("no migration files were found on the classpath under db/migration")
                    .isNotEmpty();
        }

        @Test
        @DisplayName("should all follow the V<version>__<description>.sql naming pattern")
        void should_all_be_versioned() throws IOException {
            assertThat(migrationFileNames())
                    .allSatisfy(name -> assertThat(VERSIONED.matcher(name).matches())
                            .as("migration '%s' does not match V<version>__<description>.sql", name)
                            .isTrue());
        }
    }

    @Nested
    @DisplayName("version numbers")
    class Versions {

        @Test
        @DisplayName("should be unique — no two migrations may share a version (prod-killing collision)")
        void should_have_no_duplicate_versions() throws IOException {
            Map<Integer, List<String>> byVersion = new LinkedHashMap<>();
            for (String name : migrationFileNames()) {
                Matcher matcher = VERSIONED.matcher(name);
                if (matcher.matches()) {
                    int version = Integer.parseInt(matcher.group(1));
                    byVersion.computeIfAbsent(version, ignored -> new ArrayList<>()).add(name);
                }
            }

            Map<Integer, List<String>> duplicates = new LinkedHashMap<>();
            byVersion.forEach((version, files) -> {
                if (files.size() > 1) {
                    duplicates.put(version, files);
                }
            });

            assertThat(duplicates)
                    .as("multiple migrations share a Flyway version (Flyway aborts on startup): %s",
                            duplicates)
                    .isEmpty();
        }

        @Test
        @DisplayName("should be contiguous from V1 — gaps break Flyway's out-of-order=false ordering")
        void should_be_contiguous() throws IOException {
            List<Integer> versions = new ArrayList<>();
            for (String name : migrationFileNames()) {
                Matcher matcher = VERSIONED.matcher(name);
                if (matcher.matches()) {
                    versions.add(Integer.parseInt(matcher.group(1)));
                }
            }
            versions.sort(Integer::compareTo);

            List<Integer> expected = new ArrayList<>();
            for (int version = 1; version <= versions.size(); version++) {
                expected.add(version);
            }

            assertThat(versions)
                    .as("migration versions must run 1..N with no gaps or duplicates")
                    .containsExactlyElementsOf(expected);
        }
    }
}
