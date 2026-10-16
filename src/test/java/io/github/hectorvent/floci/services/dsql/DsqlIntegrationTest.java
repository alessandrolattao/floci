package io.github.hectorvent.floci.services.dsql;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.specification.RequestSpecification;
import org.junit.jupiter.api.Test;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * Aurora DSQL's control plane over its REST-JSON paths, with the answers measured on AWS
 * (eu-west-1, 2026-10-16). The test profile runs clusters without a database, so they are ACTIVE
 * at once.
 */
@QuarkusTest
class DsqlIntegrationTest {

    private static final String ACCOUNT = "111122223333";
    private static final String AUTH = "AWS4-HMAC-SHA256 Credential=" + ACCOUNT
            + "/20261016/eu-west-1/dsql/aws4_request, SignedHeaders=host, Signature=abc";

    @Test
    void aClusterIsCreatedProtectedReadUpdatedAndDeleted() {
        String identifier = dsql().body(Map.of("tags", Map.of("Purpose", "test")))
                .post("/cluster").then().statusCode(200)
                .body("identifier", matchesPattern("[a-z2-7]{26}"))
                .body("arn", matchesPattern("arn:aws:dsql:eu-west-1:" + ACCOUNT + ":cluster/[a-z2-7]{26}"))
                .body("status", equalTo("CREATING"))
                .body("creationTime", instanceOf(Number.class))
                .body("encryptionDetails.encryptionType", equalTo("AWS_OWNED_KMS_KEY"))
                .body("encryptionDetails.encryptionStatus", equalTo("ENABLED"))
                .body("deletionProtectionEnabled", equalTo(true))
                .extract().path("identifier");

        dsql().get("/cluster/" + identifier).then().statusCode(200)
                .body("status", equalTo("ACTIVE"))
                .body("tags.Purpose", equalTo("test"))
                .body("endpoint", equalTo(identifier + ".dsql.eu-west-1.localhost.floci.io"));
        dsql().get("/cluster").then().statusCode(200)
                .body("clusters.identifier", hasItem(identifier));

        dsql().delete("/cluster/" + identifier).then().statusCode(400)
                .body("__type", equalTo("ValidationException"))
                .body("message", equalTo("Can't delete cluster: Deletion protection is enabled. To proceed,"
                        + " disable deletion protection and try again."))
                .body("reason", equalTo("deletionProtectionEnabled"));

        dsql().body(Map.of("deletionProtectionEnabled", false)).post("/cluster/" + identifier)
                .then().statusCode(200)
                .body("status", equalTo("UPDATING"))
                .body("deletionProtectionEnabled", nullValue());
        dsql().get("/cluster/" + identifier).then().statusCode(200)
                .body("status", equalTo("ACTIVE"))
                .body("deletionProtectionEnabled", equalTo(false));

        dsql().delete("/cluster/" + identifier).then().statusCode(200)
                .body("status", equalTo("DELETING"));
        dsql().get("/cluster/" + identifier).then().statusCode(404)
                .body("__type", equalTo("ResourceNotFoundException"))
                .body("resourceType", equalTo("cluster"));
    }

    @Test
    void identifiersAreCheckedAsDsqlChecksThem() {
        dsql().get("/cluster/abc").then().statusCode(400)
                .body("message", equalTo("1 validation error detected: Value at 'identifier' failed to satisfy"
                        + " constraint: Member must satisfy regular expression pattern: [a-z0-9]{26}"));
        dsql().get("/cluster/abcdefghijklmnopqrstuvwxyz").then().statusCode(400)
                .body("message", equalTo("Invalid input 'identifier: abcdefghijklmnopqrstuvwxyz'. The cluster"
                        + " identifier format is invalid. Provide a valid cluster identifier and try again."));
        dsql().get("/cluster/aaaaaaaaaaaaaaaaaaaaaaaaaa").then().statusCode(404)
                .body("message", equalTo("The resource with ARN arn:aws:dsql:eu-west-1:" + ACCOUNT
                        + ":cluster/aaaaaaaaaaaaaaaaaaaaaaaaaa doesn't exist. Verify the ARN and try again."))
                .body("resourceId", equalTo("aaaaaaaaaaaaaaaaaaaaaaaaaa"));
    }

    @Test
    void aClientTokenMakesCreateIdempotent() {
        String first = dsql().body(Map.of("clientToken", "same-request", "deletionProtectionEnabled", false))
                .post("/cluster").then().statusCode(200).extract().path("identifier");
        dsql().body(Map.of("clientToken", "same-request", "deletionProtectionEnabled", false))
                .post("/cluster").then().statusCode(200).body("identifier", equalTo(first));
        dsql().delete("/cluster/" + first).then().statusCode(200);
    }

    @Test
    void tagsPoliciesAndTheVpcEndpointServiceName() {
        String identifier = dsql().body(Map.of("deletionProtectionEnabled", false))
                .post("/cluster").then().statusCode(200).extract().path("identifier");
        String arn = "arn:aws:dsql:eu-west-1:" + ACCOUNT + ":cluster/" + identifier;
        String tagsPath = "/tags/" + URLEncoder.encode(arn, StandardCharsets.UTF_8);

        dsql().body(Map.of("tags", Map.of("team", "data"))).urlEncodingEnabled(false)
                .post(tagsPath).then().statusCode(200);
        dsql().urlEncodingEnabled(false).get(tagsPath).then().statusCode(200)
                .body("tags.team", equalTo("data"));
        dsql().urlEncodingEnabled(false).queryParam("tagKeys", "team")
                .delete(tagsPath).then().statusCode(200);
        dsql().urlEncodingEnabled(false).get(tagsPath).then().statusCode(200)
                .body("tags", not(hasItem("team")));

        dsql().get("/clusters/" + identifier + "/vpc-endpoint-service-name").then().statusCode(200)
                .body("serviceName", matchesPattern("com\\.amazonaws\\.eu-west-1\\.dsql-[a-z2-7]{4}"))
                .body("clusterVpcEndpoint", matchesPattern(identifier + "\\.dsql-[a-z2-7]{4}\\.eu-west-1\\..+"));

        dsql().body(Map.of("policy", "{\"Version\":\"2012-10-17\",\"Statement\":[]}"))
                .post("/cluster/" + identifier + "/policy").then().statusCode(200)
                .body("policyVersion", equalTo("1"));
        dsql().get("/cluster/" + identifier + "/policy").then().statusCode(200)
                .body("policy", equalTo("{\"Version\":\"2012-10-17\",\"Statement\":[]}"))
                .body("policyVersion", equalTo("1"));
        dsql().delete("/cluster/" + identifier + "/policy").then().statusCode(200)
                .body("policyVersion", equalTo("2"));
        dsql().get("/cluster/" + identifier + "/policy").then().statusCode(404);

        dsql().delete("/cluster/" + identifier).then().statusCode(200);
    }

    private static RequestSpecification dsql() {
        return given().header("Authorization", AUTH).contentType("application/json");
    }
}
