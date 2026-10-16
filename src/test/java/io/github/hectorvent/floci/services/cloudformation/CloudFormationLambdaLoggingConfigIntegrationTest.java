package io.github.hectorvent.floci.services.cloudformation;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code AWS::Lambda::Function.LoggingConfig} through CloudFormation. Measured on AWS (eu-west-1,
 * 2026-10-16): the function reads back the LogGroup the template names; a change to JSON with an
 * ApplicationLogLevel updates it in place, SystemLogLevel reading back INFO; and removing the
 * property puts it back on Text and {@code /aws/lambda/<name>}, again in place.
 */
@QuarkusTest
class CloudFormationLambdaLoggingConfigIntegrationTest {

    private static final String CFN_AUTH =
            "AWS4-HMAC-SHA256 Credential=test/20260205/us-east-1/cloudformation/aws4_request";
    private static final String LAMBDA = "/2015-03-31/functions/";

    @Test
    void loggingConfigIsCreatedUpdatedAndRemovedInPlace() {
        String stackName = "cfn-lambda-logging-" + Long.toString(System.nanoTime(), 36);

        deploy("CreateStack", stackName, template("""
                ,"LoggingConfig": {"LogGroup": "/floci/cfn-a"}
                """));
        String created = functionName(stackName);
        given()
        .when()
            .get(LAMBDA + created + "/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogFormat", equalTo("Text"))
            .body("LoggingConfig.LogGroup", equalTo("/floci/cfn-a"))
            .body("LoggingConfig.ApplicationLogLevel", nullValue());

        deploy("UpdateStack", stackName, template("""
                ,"LoggingConfig": {"LogGroup": "/floci/cfn-b", "LogFormat": "JSON", "ApplicationLogLevel": "WARN"}
                """));
        assertEquals(created, functionName(stackName), "a changed LoggingConfig updates the function in place");
        given()
        .when()
            .get(LAMBDA + created + "/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogFormat", equalTo("JSON"))
            .body("LoggingConfig.ApplicationLogLevel", equalTo("WARN"))
            .body("LoggingConfig.SystemLogLevel", equalTo("INFO"))
            .body("LoggingConfig.LogGroup", equalTo("/floci/cfn-b"));

        deploy("UpdateStack", stackName, template(""));
        assertEquals(created, functionName(stackName), "removing LoggingConfig updates the function in place");
        given()
        .when()
            .get(LAMBDA + created + "/configuration")
        .then()
            .statusCode(200)
            .body("LoggingConfig.LogFormat", equalTo("Text"))
            .body("LoggingConfig.ApplicationLogLevel", nullValue())
            .body("LoggingConfig.LogGroup", equalTo("/aws/lambda/" + created));
    }

    private static String template(String loggingConfig) {
        return """
                {
                  "Resources": {
                    "Fn": {
                      "Type": "AWS::Lambda::Function",
                      "Properties": {
                        "Runtime": "python3.14",
                        "Handler": "index.handler",
                        "Role": "arn:aws:iam::000000000000:role/lambda-role",
                        "Code": {"ZipFile": "def handler(e, c): return 'ok'"}
                        %s
                      }
                    }
                  },
                  "Outputs": {"Name": {"Value": {"Ref": "Fn"}}}
                }
                """.formatted(loggingConfig);
    }

    private static void deploy(String action, String stackName, String template) {
        given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", action)
            .formParam("StackName", stackName)
            .formParam("TemplateBody", template)
        .when()
            .post("/")
        .then()
            .statusCode(200);
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal(stackName);
        assertEquals("CreateStack".equals(action) ? "CREATE_COMPLETE" : "UPDATE_COMPLETE", state.status(),
                state.reason());
    }

    private static String functionName(String stackName) {
        return given()
            .contentType("application/x-www-form-urlencoded")
            .header("Authorization", CFN_AUTH)
            .formParam("Action", "DescribeStacks")
            .formParam("StackName", stackName)
        .when()
            .post("/")
        .then()
            .statusCode(200)
            .extract().xmlPath().getString("**.find { it.name() == 'OutputValue' }");
    }
}
