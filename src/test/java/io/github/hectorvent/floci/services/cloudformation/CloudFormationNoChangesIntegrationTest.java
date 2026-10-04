package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An update that submits what the stack already has. Measured against CloudFormation
 * (eu-west-1, 2026-10-03): UpdateStack answers ValidationError "No updates are to be performed."
 * and changes nothing, the same template re-indented included, since the template is compared as
 * a document; a CreateChangeSet for it is created FAILED, UNAVAILABLE, with "The submitted
 * information didn't contain changes. Submit different information to create a change set.". A
 * change to the Outputs or the Description alone is an update. The CDK deletes that change set and
 * deletes it again by name before its next deploy; DeleteChangeSet answers success for a change set
 * the stack does not have, by name or by ARN, and ValidationError for a stack that does not exist
 * (measured 2026-10-04).
 */
@QuarkusTest
class CloudFormationNoChangesIntegrationTest {

    private static final String TEMPLATE = """
            {"Resources": {"Topic": {"Type": "AWS::SNS::Topic", "Properties": {"TopicName": "%s"}}},
             "Outputs": {"Out": {"Value": "1"}}}""";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void anIdenticalUpdateIsRefusedAndChangesNothing() {
        String template = TEMPLATE.formatted("no-change-topic");
        create("no-change", template);
        String changeSetsBefore = listChangeSets("no-change").extract().asString();

        update("no-change", template)
                .statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("<Message>No updates are to be performed.</Message>"));
        update("no-change", template.replace("\n", "\n        ").replace(": ", ":    "))
                .statusCode(400)
                .body(containsString("No updates are to be performed."));

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("no-change").status());
        listChangeSets("no-change").body(not(containsString("<ChangeSetName>update-")));
        assertEquals(changeSetsBefore.replaceAll("<RequestId>[^<]*</RequestId>", ""),
                listChangeSets("no-change").extract().asString().replaceAll("<RequestId>[^<]*</RequestId>", ""));
    }

    @Test
    void aChangeToTheOutputsAloneIsAnUpdate() {
        String template = TEMPLATE.formatted("outputs-only-topic");
        create("outputs-only", template);

        update("outputs-only", template.replace("\"Value\": \"1\"", "\"Value\": \"2\"")).statusCode(200);

        assertEquals("UPDATE_COMPLETE", CfnStackWaits.awaitTerminal("outputs-only").status());
    }

    @Test
    void aChangeSetWithNothingToChangeIsCreatedFailed() {
        String template = TEMPLATE.formatted("change-set-same-topic");
        create("change-set-same", template);

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateChangeSet")
                .formParam("StackName", "change-set-same")
                .formParam("ChangeSetName", "same")
                .formParam("ChangeSetType", "UPDATE")
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeChangeSet")
                .formParam("StackName", "change-set-same")
                .formParam("ChangeSetName", "same")
                .when().post("/").then()
                .statusCode(200)
                .body(containsString("<Status>FAILED</Status>"))
                .body(containsString("<ExecutionStatus>UNAVAILABLE</ExecutionStatus>"))
                .body(containsString("<StatusReason>The submitted information didn"))
                .body(containsString("t contain changes. Submit different information to create a change set.</StatusReason>"));
    }

    @Test
    void theChangeSetTheCdkDeletedCanBeDeletedAgain() {
        String template = TEMPLATE.formatted("delete-again-topic");
        create("delete-again", template);
        String id = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateChangeSet")
                .formParam("StackName", "delete-again")
                .formParam("ChangeSetName", "cdk-deploy-change-set")
                .formParam("ChangeSetType", "UPDATE")
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200)
                .extract().xmlPath().getString("CreateChangeSetResponse.CreateChangeSetResult.Id");

        deleteChangeSet("delete-again", "cdk-deploy-change-set").statusCode(200);
        deleteChangeSet("delete-again", "cdk-deploy-change-set")
                .statusCode(200)
                .body(containsString("<DeleteChangeSetResult/>"));
        deleteChangeSet("delete-again", id).statusCode(200);
        deleteChangeSet("delete-again-missing", "cdk-deploy-change-set")
                .statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"));

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("delete-again").status());
    }

    private static ValidatableResponse deleteChangeSet(String stackName, String changeSetName) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteChangeSet")
                .formParam("StackName", stackName)
                .formParam("ChangeSetName", changeSetName)
                .when().post("/").then();
    }

    private static void create(String stackName, String template) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(stackName).status());
    }

    private static ValidatableResponse update(String stackName, String template) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "UpdateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", template)
                .when().post("/").then();
    }

    private static ValidatableResponse listChangeSets(String stackName) {
        return given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "ListChangeSets")
                .formParam("StackName", stackName)
                .when().post("/").then().statusCode(200);
    }
}
