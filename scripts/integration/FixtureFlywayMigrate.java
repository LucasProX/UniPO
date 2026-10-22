import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;

/**
 * Applies the packaged backend migrations to the R03 source fixture after confirming this run's marker.
 * Launched by mysql-backup-restore.sh with the backend jar's own Flyway and connector; the password comes from env.
 */
public final class FixtureFlywayMigrate {
    private FixtureFlywayMigrate() {
    }

    public static void main(String[] args) throws Exception {
        String runId = env("UNIPO_R03_RUN_ID");
        String schema = env("UNIPO_R03_SCHEMA");
        String user = env("UNIPO_R03_USER");
        String password = env("UNIPO_R03_SOURCE_PASSWORD");
        String expectedVersion = env("UNIPO_R03_EXPECTED_VERSION");
        String url = "jdbc:mysql://127.0.0.1:" + env("UNIPO_R03_PORT") + "/" + schema
                + "?useUnicode=true&characterEncoding=utf8&serverTimezone=Asia/Shanghai"
                + "&allowPublicKeyRetrieval=true&useSSL=false";

        try (Connection connection = DriverManager.getConnection(url, user, password);
                Statement statement = connection.createStatement()) {
            List<String> tables = new ArrayList<>();
            try (ResultSet rows = statement.executeQuery(
                    "SELECT TABLE_NAME FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE()")) {
                while (rows.next()) {
                    tables.add(rows.getString(1));
                }
            }
            if (!tables.equals(List.of("r03_fixture_marker"))) {
                throw new IllegalStateException("Fixture schema is not the fresh marker-only schema of this run");
            }
            try (ResultSet rows = statement.executeQuery("SELECT LOWER(HEX(run_id)) FROM r03_fixture_marker")) {
                if (!rows.next() || !runId.equals(rows.getString(1)) || rows.next()) {
                    throw new IllegalStateException("Fixture marker does not belong to this run");
                }
            }
        }

        MigrateResult result = Flyway.configure()
                .dataSource(url, user, password)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0")
                .createSchemas(false)
                .cleanDisabled(true)
                .load()
                .migrate();
        if (!result.success || !expectedVersion.equals(result.targetSchemaVersion)) {
            throw new IllegalStateException("Flyway stopped at " + result.targetSchemaVersion
                    + ", expected " + expectedVersion);
        }
        System.out.println("Flyway applied " + result.migrationsExecuted + " migrations; schema version "
                + result.targetSchemaVersion + ".");
    }

    private static String env(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            throw new IllegalStateException(name + " is required");
        }
        return value;
    }
}
