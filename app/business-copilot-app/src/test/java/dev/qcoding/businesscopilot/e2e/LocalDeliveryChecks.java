package dev.qcoding.businesscopilot.e2e;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.assertThat;

/** Local delivery evidence on disposable fictional data, not a production capacity or RPO/RTO claim. */
final class LocalDeliveryChecks {
    static void capacity(Path repository, Path evidence, int port) throws Exception {
        var builder = new ProcessBuilder("bash", "scripts/capacity-smoke-test.sh")
                .directory(repository.toFile()).redirectErrorStream(true)
                .redirectOutput(evidence.resolve("capacity.log").toFile());
        builder.environment().put("BUSINESS_COPILOT_BASE_URL", "http://127.0.0.1:" + port);
        builder.environment().put("BUSINESS_COPILOT_SMOKE_USERNAME", "admin");
        builder.environment().put("BUSINESS_COPILOT_SMOKE_PASSWORD", "admin-change-me");
        builder.environment().put("BUSINESS_COPILOT_CAPACITY_REQUESTS", "50");
        builder.environment().put("BUSINESS_COPILOT_CAPACITY_CONCURRENCY", "5");
        var process = builder.start();
        try {
            assertThat(process.waitFor(60, TimeUnit.SECONDS)).as("local capacity timeout").isTrue();
            assertThat(process.exitValue()).as(Files.readString(evidence.resolve("capacity.log"))).isZero();
        } finally {
            process.descendants().forEach(ProcessHandle::destroy);
            process.destroy();
            if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly();
        }
    }

    // Call only after closing the application, so rows cannot change between the snapshot and dump.
    static void restore(PostgreSQLContainer source, Path evidence) throws Exception {
        long started = System.nanoTime();
        Map<String, String> before = manifest(source);
        for (String table : new String[]{"data_query_results", "knowledge_chunk_embeddings", "support_reply_drafts",
                "report_drafts", "resume_assessments", "hr_candidate_consents"}) {
            assertThat(before.get(table)).as("nonempty workflow table " + table).doesNotStartWith("0:");
        }
        var dump = source.execInContainer("pg_dump", "-U", source.getUsername(), "-d", source.getDatabaseName(),
                "-Fc", "--no-owner", "--no-privileges", "-f", "/tmp/workflow.dump");
        assertThat(dump.getExitCode()).as(dump.getStderr()).isZero();
        Path backup = evidence.resolve("workflow.dump");
        source.copyFileFromContainer("/tmp/workflow.dump", backup.toString());
        try (var restored = new PostgreSQLContainer(DockerImageName.parse("pgvector/pgvector:pg16")
                .asCompatibleSubstituteFor("postgres"))) {
            restored.start();
            restored.copyFileToContainer(MountableFile.forHostPath(backup), "/tmp/workflow.dump");
            var restore = restored.execInContainer("pg_restore", "--exit-on-error", "--no-owner", "--no-privileges",
                    "-U", restored.getUsername(), "-d", restored.getDatabaseName(), "/tmp/workflow.dump");
            assertThat(restore.getExitCode()).as(restore.getStderr()).isZero();
            assertThat(manifest(restored)).as("all public table row counts and content hashes including ownership and vectors")
                    .isEqualTo(before);
            var jdbc = jdbc(restored);
            assertThat(jdbc.queryForObject("SELECT version FROM flyway_schema_history WHERE success ORDER BY installed_rank DESC LIMIT 1", String.class)).isEqualTo("43");
            assertThat(jdbc.queryForObject("SELECT embedding <=> embedding FROM knowledge_chunk_embeddings LIMIT 1", Double.class)).isCloseTo(0.0, org.assertj.core.data.Offset.offset(0.000001));
            Files.writeString(evidence.resolve("restore.txt"), "Disposable PostgreSQL restore passed; all public tables, ownership fields, consent states and vectors match.\nElapsed seconds: "
                    + TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started) + "\n" + before
                    + "\nDeployment roles, secrets, production recovery windows and storage durability require external acceptance.\n");
        } finally { Files.deleteIfExists(backup); }
    }

    private static JdbcTemplate jdbc(PostgreSQLContainer database) {
        return new JdbcTemplate(new DriverManagerDataSource(database.getJdbcUrl(), database.getUsername(), database.getPassword()));
    }
    private static Map<String, String> manifest(PostgreSQLContainer database) {
        var jdbc = jdbc(database);
        Map<String, String> result = new TreeMap<>();
        for (String table : jdbc.queryForList("SELECT tablename FROM pg_tables WHERE schemaname='public' ORDER BY tablename", String.class)) {
            String quoted = "\"" + table.replace("\"", "\"\"") + "\"";
            result.put(table, jdbc.queryForObject("SELECT count(*)::text || ':' || COALESCE(md5(string_agg(md5(t::text), '' ORDER BY t::text)), 'empty') FROM public." + quoted + " t", String.class));
        }
        return result;
    }
}
