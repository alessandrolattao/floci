package io.github.hectorvent.floci.services.dsql;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.dsql.dataplane.DsqlEndpoint;
import io.github.hectorvent.floci.services.iam.IamService;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.postgresql.util.PSQLException;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Aurora DSQL's data plane against a real PostgreSQL container: a client connects to the cluster
 * endpoint with TLS and an IAM token, and the database answers in DSQL's dialect, with the refusals
 * and errors measured on AWS (eu-west-1, 2026-10-16).
 */
@QuarkusTest
@TestProfile(DsqlDatabasesProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class DsqlDataPlaneIntegrationTest {

    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "eu-west-1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
            + "/20261016/" + REGION + "/dsql/aws4_request, SignedHeaders=host, Signature=abc";
    private static final String ROLE_ARN = "arn:aws:iam::" + ACCOUNT + ":role/service/app-role";

    @Inject
    DockerClient dockerClient;

    @Inject
    DsqlEndpoint endpoint;

    @Inject
    IamService iamService;

    @ConfigProperty(name = "quarkus.http.test-port", defaultValue = "0")
    int httpPort;

    private String identifier;
    private String host;

    @BeforeAll
    void createCluster() {
        try {
            dockerClient.pingCmd().exec();
        } catch (Exception e) {
            assumeTrue(false, "Docker is not available: " + e.getMessage());
        }
        identifier = dsql().body(Map.of("deletionProtectionEnabled", false))
                .post("/cluster").then().statusCode(200).extract().path("identifier");
        host = identifier + ".dsql." + REGION + ".localhost.floci.io";
        await().atMost(Duration.ofMinutes(3)).pollInterval(Duration.ofMillis(500)).until(() ->
                "ACTIVE".equals(dsql().get("/cluster/" + identifier).then().extract().path("status")));
    }

    @AfterAll
    void deleteCluster() {
        if (identifier != null) {
            dsql().delete("/cluster/" + identifier);
            await().atMost(Duration.ofMinutes(1)).until(() ->
                    dsql().get("/cluster/" + identifier).then().extract().statusCode() == 404);
        }
    }

    @Test
    void adminConnectsWithAnAdminTokenAndGetsDsqlsSession() throws Exception {
        try (Connection connection = connect("admin", adminToken());
             Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("SELECT current_user, sys.iam_session_user(),"
                     + " length(sys.current_session_id()), current_setting('transaction_isolation')")) {
            assertTrue(row.next());
            assertEquals("admin", row.getString(1));
            assertEquals("arn:aws:iam::" + ACCOUNT + ":root", row.getString(2));
            assertEquals(26, row.getInt(3));
            assertEquals("repeatable read", row.getString(4));
        }
    }

    @Test
    void aConnectionIsRefusedAsDsqlRefusesIt() throws Exception {
        PSQLException plain = assertThrows(PSQLException.class, () -> {
            Properties properties = properties("admin", adminToken());
            properties.setProperty("sslmode", "disable");
            DriverManager.getConnection(url(), properties).close();
        });
        assertEquals("unable to accept connection, SSL is mandatory. AWS Authentication is required.",
                plain.getServerErrorMessage().getMessage());
        assertEquals("08P01", plain.getSQLState());
        assertTrue(plain.getServerErrorMessage().getDetail().startsWith("Session Id: "));

        PSQLException wrongAction = assertThrows(PSQLException.class, () ->
                connect("admin", token("DbConnect", "test", "test", Instant.now(), 900, null)).close());
        assertEquals("unable to accept connection, access denied", wrongAction.getServerErrorMessage().getMessage());
        assertEquals("08006", wrongAction.getSQLState());
        assertTrue(wrongAction.getServerErrorMessage().getDetail().startsWith("Session Id: "));
        assertEquals("Wrong user to action mapping. user: admin, action: DbConnect",
                wrongAction.getServerErrorMessage().getHint());

        PSQLException expired = assertThrows(PSQLException.class, () -> connect("admin",
                token("DbConnectAdmin", "test", "test", Instant.now().minusSeconds(120), 60, null)).close());
        assertTrue(expired.getServerErrorMessage().getHint().startsWith("Signature expired: "),
                expired.getServerErrorMessage().getHint());

        PSQLException garbage = assertThrows(PSQLException.class, () -> connect("admin", "not-a-token").close());
        assertEquals("unable to accept connection, access denied", garbage.getServerErrorMessage().getMessage());
        assertEquals(null, garbage.getServerErrorMessage().getHint());

        PSQLException otherDatabase = assertThrows(PSQLException.class, () -> {
            Properties properties = properties("admin", adminToken());
            DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + endpoint.port() + "/other", properties)
                    .close();
        });
        assertEquals("database \"other\" is not currently accepting connections",
                otherDatabase.getServerErrorMessage().getMessage());
        assertEquals("55000", otherDatabase.getSQLState());
    }

    @Test
    void aRoleLogsInOnlyForTheIamRoleMappedToIt() throws Exception {
        String accessKeyId = "ASIA" + Long.toString(System.nanoTime(), 36).toUpperCase();
        String secret = "app+session/secret=key";
        String sessionToken = "IQoJb3JpZ2luX2VjEJr+app/role+session==";
        iamService.registerLambdaExecutionRoleSession(ACCOUNT, accessKeyId, secret, sessionToken, ROLE_ARN);
        try (Connection admin = connect("admin", adminToken()); Statement statement = admin.createStatement()) {
            statement.execute("CREATE ROLE app_role WITH LOGIN");

            PSQLException unmapped = assertThrows(PSQLException.class, () -> connect("app_role",
                    token("DbConnect", accessKeyId, secret, Instant.now(), 900, sessionToken)).close());
            assertEquals("unable to accept connection, access denied", unmapped.getServerErrorMessage().getMessage());
            assertEquals("28000", unmapped.getSQLState());

            statement.execute("AWS IAM GRANT app_role TO '" + ROLE_ARN + "'");
            statement.execute("CREATE TABLE owned_by_admin (id int PRIMARY KEY, n int)");
            statement.executeQuery("CREATE INDEX ASYNC owned_by_admin_n ON owned_by_admin (n)").close();
            try (Connection app = connect("app_role", token("DbConnect", accessKeyId, secret, Instant.now(), 900,
                         sessionToken));
                 Statement query = app.createStatement()) {
                try (ResultSet row = query.executeQuery("SELECT current_user, sys.iam_session_user()")) {
                    assertTrue(row.next());
                    assertEquals("app_role", row.getString(1));
                    assertEquals(ROLE_ARN, row.getString(2));
                }
                PSQLException notOwner = assertThrows(PSQLException.class, () -> query.executeQuery(
                        "SELECT sys._invalidate_index('public.owned_by_admin_n'::regclass)").close());
                assertEquals("42501", notOwner.getSQLState());
            }

            PSQLException adminAction = assertThrows(PSQLException.class, () -> connect("app_role",
                    token("DbConnectAdmin", accessKeyId, secret, Instant.now(), 900, sessionToken)).close());
            assertEquals("Wrong user to action mapping. user: app_role, action: DbConnectAdmin",
                    adminAction.getServerErrorMessage().getHint());

            PSQLException superuser = assertThrows(PSQLException.class, () ->
                    statement.execute("AWS IAM GRANT dbowner TO '" + ROLE_ARN + "'"));
            assertEquals("0LP01", superuser.getSQLState());
            assertEquals("cannot establish trust with role \"dbowner\"", superuser.getServerErrorMessage().getMessage());
            PSQLException itself = assertThrows(PSQLException.class, () ->
                    statement.execute("AWS IAM GRANT admin TO '" + ROLE_ARN + "'"));
            assertEquals("42501", itself.getSQLState());
            assertEquals("Only roles with the ADMIN option on role \"admin\" may grant IAM trust to this role.",
                    itself.getServerErrorMessage().getDetail());
            assertThrows(PSQLException.class, () -> connect("dbowner",
                    token("DbConnect", accessKeyId, secret, Instant.now(), 900, sessionToken)).close());

            statement.execute("AWS IAM REVOKE app_role FROM '" + ROLE_ARN + "'");
            assertThrows(PSQLException.class, () -> connect("app_role",
                    token("DbConnect", accessKeyId, secret, Instant.now(), 900, sessionToken)).close());
        } finally {
            iamService.unregisterSession(ACCOUNT, accessKeyId);
        }
    }

    @Test
    void theDatabaseSpeaksDsqlsDialect() throws Exception {
        try (Connection connection = connect("admin", adminToken()); Statement statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA dialect");
            statement.execute("CREATE TABLE dialect.t (id uuid PRIMARY KEY DEFAULT gen_random_uuid(), n int)");

            String jobId;
            try (ResultSet job = statement.executeQuery("CREATE INDEX ASYNC t_n ON dialect.t (n)")) {
                assertTrue(job.next());
                jobId = job.getString("job_id");
            }
            assertEquals(26, jobId.length());
            try (ResultSet waited = statement.executeQuery("CALL sys.wait_for_job('" + jobId + "')")) {
                assertTrue(waited.next());
                assertTrue(waited.getBoolean(1));
            }

            SQLException plainIndex = assertThrows(SQLException.class, () ->
                    statement.execute("CREATE INDEX t_plain ON dialect.t (n)"));
            assertEquals("0A000", plainIndex.getSQLState());
            assertEquals("unsupported mode. please use CREATE INDEX ASYNC.",
                    ((PSQLException) plainIndex).getServerErrorMessage().getMessage());
            assertEquals(null, ((PSQLException) plainIndex).getServerErrorMessage().getWhere());

            SQLException truncate = assertThrows(SQLException.class, () -> statement.execute("TRUNCATE dialect.t"));
            assertEquals("0A000", truncate.getSQLState());
            assertEquals("unsupported statement: Truncate",
                    ((PSQLException) truncate).getServerErrorMessage().getMessage());

            connection.setAutoCommit(false);
            statement.execute("CREATE TABLE dialect.a (id int PRIMARY KEY)");
            SQLException twoDdl = assertThrows(SQLException.class, () ->
                    statement.execute("CREATE TABLE dialect.b (id int PRIMARY KEY)"));
            assertEquals("multiple ddl statements not supported in a transaction",
                    ((PSQLException) twoDdl).getServerErrorMessage().getMessage());
            connection.rollback();

            SQLException rowLimit = assertThrows(SQLException.class, () ->
                    statement.execute("INSERT INTO dialect.t (n) SELECT g FROM generate_series(1, 3001) g"));
            assertEquals("54000", rowLimit.getSQLState());
            assertEquals("transaction row limit exceeded",
                    ((PSQLException) rowLimit).getServerErrorMessage().getMessage());
            connection.rollback();
        }
    }

    @Test
    void aWriteConflictIsDsqlsOc000() throws Exception {
        try (Connection setup = connect("admin", adminToken()); Statement statement = setup.createStatement()) {
            statement.execute("CREATE TABLE conflict (id int PRIMARY KEY, n int)");
            statement.execute("INSERT INTO conflict VALUES (1, 0)");
        }
        try (Connection first = connect("admin", adminToken()); Connection second = connect("admin", adminToken())) {
            first.setAutoCommit(false);
            second.setAutoCommit(false);
            try (Statement firstStatement = first.createStatement(); Statement secondStatement = second.createStatement()) {
                secondStatement.executeQuery("SELECT n FROM conflict WHERE id = 1").close();
                firstStatement.executeUpdate("UPDATE conflict SET n = 1 WHERE id = 1");
                CompletableFuture<SQLException> blocked = CompletableFuture.supplyAsync(() -> {
                    try {
                        secondStatement.executeUpdate("UPDATE conflict SET n = 2 WHERE id = 1");
                        return null;
                    } catch (SQLException e) {
                        return e;
                    }
                });
                first.commit();
                SQLException conflict = blocked.get(30, TimeUnit.SECONDS);
                assertEquals("40001", conflict.getSQLState());
                assertEquals("change conflicts with another transaction (OC000)",
                        ((PSQLException) conflict).getServerErrorMessage().getMessage());
                second.rollback();
            }
        }
    }

    private Connection connect(String user, String password) throws SQLException {
        return DriverManager.getConnection(url(), properties(user, password));
    }

    private String url() {
        return "jdbc:postgresql://127.0.0.1:" + endpoint.port() + "/postgres";
    }

    private static Properties properties(String user, String password) {
        Properties properties = new Properties();
        properties.setProperty("user", user);
        properties.setProperty("password", password);
        properties.setProperty("sslmode", "require");
        properties.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
        return properties;
    }

    private String adminToken() throws Exception {
        return token("DbConnectAdmin", "test", "test", Instant.now(), 900, null);
    }

    private String token(String action, String accessKeyId, String secret, Instant at, int expires,
                         String sessionToken) throws Exception {
        return SigV4TokenTestHelper.createDsqlToken(host, REGION, action, accessKeyId, secret, at, expires,
                sessionToken);
    }

    private RequestSpecification dsql() {
        return given().port(httpPort).header("Authorization", AUTH).contentType("application/json");
    }
}
