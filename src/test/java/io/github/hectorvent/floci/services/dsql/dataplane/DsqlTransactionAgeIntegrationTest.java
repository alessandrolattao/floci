package io.github.hectorvent.floci.services.dsql.dataplane;

import com.github.dockerjava.api.DockerClient;
import io.github.hectorvent.floci.services.dsql.DsqlDatabasesProfile;
import io.github.hectorvent.floci.testutil.SigV4TokenTestHelper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.restassured.specification.RequestSpecification;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

import static io.restassured.RestAssured.given;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * A transaction older than DSQL lets one live fails at its next statement with DSQL's 54000, over
 * the simple and the extended protocol alike, and a ROLLBACK still ends it. The limit, 300 seconds
 * on DSQL, is shortened to one second here.
 */
@QuarkusTest
@TestProfile(DsqlDatabasesProfile.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("docker")
class DsqlTransactionAgeIntegrationTest {

    private static final String ACCOUNT = "111122223333";
    private static final String REGION = "eu-west-1";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
            + "/20261016/" + REGION + "/dsql/aws4_request, SignedHeaders=host, Signature=abc";

    @Inject
    DockerClient dockerClient;

    @Inject
    DsqlEndpoint endpoint;

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
        endpoint.transactionAgeLimit(Duration.ofSeconds(1));
    }

    @AfterEach
    void keepTheShortLimit() {
        endpoint.transactionAgeLimit(Duration.ofSeconds(1));
    }

    @AfterAll
    void deleteCluster() {
        endpoint.transactionAgeLimit(Duration.ofSeconds(300));
        if (identifier != null) {
            dsql().delete("/cluster/" + identifier);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"extended", "simple"})
    void aTransactionPastTheLimitFailsAtItsNextStatementAndRollsBack(String queryMode) throws Exception {
        try (Connection connection = connect(queryMode); Statement statement = connection.createStatement()) {
            connection.setAutoCommit(false);
            statement.executeQuery("SELECT 1").close();

            Thread.sleep(2_500);
            PSQLException expired = assertThrows(PSQLException.class, () -> statement.executeQuery("SELECT 2").close());

            assertEquals("54000", expired.getSQLState());
            assertEquals("transaction age limit of 1s exceeded", expired.getServerErrorMessage().getMessage());
            assertTrue(expired.getServerErrorMessage().getDetail().matches("Current transaction age \\d+s exceeds 1s"),
                    expired.getServerErrorMessage().getDetail());

            connection.rollback();
            try (ResultSet row = statement.executeQuery("SELECT 3")) {
                assertTrue(row.next());
                assertEquals(3, row.getInt(1));
            }
            connection.rollback();
        }
    }

    private Connection connect(String queryMode) throws Exception {
        Properties properties = new Properties();
        properties.setProperty("user", "admin");
        properties.setProperty("password", SigV4TokenTestHelper.createDsqlToken(host, REGION, "DbConnectAdmin",
                "test", "test", Instant.now(), 900, null));
        properties.setProperty("sslmode", "require");
        properties.setProperty("sslfactory", "org.postgresql.ssl.NonValidatingFactory");
        properties.setProperty("preferQueryMode", queryMode);
        try {
            return DriverManager.getConnection("jdbc:postgresql://127.0.0.1:" + endpoint.port() + "/postgres",
                    properties);
        } catch (SQLException e) {
            throw new IllegalStateException("Could not connect to the DSQL endpoint", e);
        }
    }

    private RequestSpecification dsql() {
        return given().port(httpPort).header("Authorization", AUTH).contentType("application/json");
    }
}
