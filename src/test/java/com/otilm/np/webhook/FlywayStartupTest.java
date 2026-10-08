package com.otilm.np.webhook;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The shipped schema exists only through Flyway, so the migrations have to run when the application starts. The suite's
 * database switches Flyway off, which this test undoes on a database of its own.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=true",
        "spring.datasource.url=jdbc:hsqldb:mem:flywayStartup;sql.syntax_pgs=true"})
class FlywayStartupTest {

    private static final String SHIPPED_MIGRATIONS = "classpath:db/migration/V*__*.sql";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void appliesEveryShippedMigrationAtStartup() throws IOException {
        List<String> applied = jdbc
                .queryForList(
                        "select \"version\" from \"flyway_schema_history\""
                                + " where \"success\" = true and \"version\" is not null order by \"version\"",
                        String.class);

        assertEquals(shippedVersions(), applied);
    }

    private static List<String> shippedVersions() throws IOException {
        Resource[] migrations = new PathMatchingResourcePatternResolver().getResources(SHIPPED_MIGRATIONS);
        return Arrays
                .stream(migrations)
                .map(Resource::getFilename)
                .map(Objects::requireNonNull)
                .map(name -> name.substring(1, name.indexOf("__")))
                .sorted()
                .toList();
    }
}
