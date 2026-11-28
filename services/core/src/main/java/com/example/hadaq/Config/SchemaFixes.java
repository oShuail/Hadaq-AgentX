package com.example.hadaq.Config;

import jakarta.persistence.EntityManagerFactory;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/*
 * Schema pieces Hibernate cannot make on every server we use. On MariaDB 10.4 (XAMPP) Hibernate writes unique
 * constraints as "alter table if exists ... add constraint", which 10.4 rejects, so the index is made here with plain
 * SQL that MariaDB 10.4+, newer MariaDB and MySQL all accept. The EntityManagerFactory parameter makes this run after
 * Hibernate has created the tables.
 */
@Slf4j
@Component
public class SchemaFixes implements ApplicationRunner {

    private final JdbcTemplate jdbc;

    public SchemaFixes(JdbcTemplate jdbc, EntityManagerFactory tablesReady) {
        this.jdbc = jdbc;
    }

    @Override
    public void run(ApplicationArguments args) {
        uniqueIndex("captures", "ux_capture_client_uuid", "client_uuid");
    }

    private void uniqueIndex(String table, String name, String column) {
        Integer exists = jdbc.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE() AND table_name = ? AND column_name = ? AND non_unique = 0
                """, Integer.class, table, column);
        if (exists != null && exists > 0) {
            return;
        }
        // a table that already holds duplicates cannot get the index: say so instead of failing the start
        try {
            jdbc.execute("CREATE UNIQUE INDEX " + name + " ON " + table + " (" + column + ")");
            log.info("Created unique index {} on {}({})", name, table, column);
        } catch (Exception exception) {
            log.error("Could not create the unique index {} on {}({}): {}", name, table, column, exception.getMessage());
        }
    }
}
