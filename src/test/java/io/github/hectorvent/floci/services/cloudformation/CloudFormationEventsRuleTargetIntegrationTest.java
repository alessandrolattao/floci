package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.path.json.JsonPath;
import io.restassured.response.ValidatableResponse;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * An {@code AWS::Events::Rule} target's {@code DeadLetterConfig}, {@code RetryPolicy},
 * {@code RoleArn} and {@code EcsParameters} reach the rule as PutTargets would set them, and
 * delivery honours the dead-letter queue the stack declares.
 */
@QuarkusTest
class CloudFormationEventsRuleTargetIntegrationTest {

    private static final String EB_CT = "application/x-amz-json-1.1";
    private static final String SQS_CT = "application/x-amz-json-1.0";
    private static final String STACK = "cfn-rule-targets";
    private static final String BUS = "cfn-rule-targets-bus";

    private static final String TEMPLATE = """
            {
              "Resources": {
                "Dlq": {"Type": "AWS::SQS::Queue", "Properties": {"QueueName": "cfn-rule-targets-dlq"}},
                "Bus": {"Type": "AWS::Events::EventBus", "Properties": {"Name": "cfn-rule-targets-bus"}},
                "Undeliverable": {
                  "Type": "AWS::Events::Rule",
                  "Properties": {
                    "Name": "cfn-rule-targets-undeliverable",
                    "EventBusName": {"Ref": "Bus"},
                    "EventPattern": {"source": ["cfn.rule.targets"]},
                    "Targets": [{
                      "Id": "MissingQueue",
                      "Arn": {"Fn::Sub": "arn:${AWS::Partition}:sqs:${AWS::Region}:${AWS::AccountId}:cfn-rule-targets-missing"},
                      "DeadLetterConfig": {"Arn": {"Fn::GetAtt": ["Dlq", "Arn"]}},
                      "RetryPolicy": {"MaximumRetryAttempts": 2, "MaximumEventAgeInSeconds": 60}
                    }]
                  }
                },
                "RunTask": {
                  "Type": "AWS::Events::Rule",
                  "Properties": {
                    "Name": "cfn-rule-targets-ecs",
                    "EventBusName": {"Ref": "Bus"},
                    "EventPattern": {"source": ["cfn.rule.targets.never"]},
                    "Targets": [{
                      "Id": "Jobs",
                      "Arn": {"Fn::Sub": "arn:${AWS::Partition}:ecs:${AWS::Region}:${AWS::AccountId}:cluster/jobs"},
                      "RoleArn": {"Fn::Sub": "arn:${AWS::Partition}:iam::${AWS::AccountId}:role/events-run-task"},
                      "EcsParameters": {
                        "TaskDefinitionArn": "arn:aws:ecs:us-east-1:000000000000:task-definition/job:1",
                        "TaskCount": 1,
                        "LaunchType": "FARGATE",
                        "NetworkConfiguration": {"AwsVpcConfiguration": {
                          "Subnets": ["subnet-1"], "SecurityGroups": ["sg-1"], "AssignPublicIp": "DISABLED"}},
                        "PlacementStrategies": [{"Type": "spread", "Field": "attribute:ecs.availability-zone"}],
                        "TagList": [{"Key": "team", "Value": "jobs"}]
                      }
                    }]
                  }
                }
              }
            }
            """;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void ruleTargetsKeepTheirDeliverySettingsAndTheDeadLetterQueueReceivesUndeliverableEvents() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", STACK)
                .formParam("TemplateBody", TEMPLATE)
                .when().post("/").then().statusCode(200);
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal(STACK).status());

        String dlqUrl = given().contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.GetQueueUrl")
                .body("{\"QueueName\":\"cfn-rule-targets-dlq\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("QueueUrl");
        String dlqArn = given().contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.GetQueueAttributes")
                .body("{\"QueueUrl\":\"" + dlqUrl + "\",\"AttributeNames\":[\"QueueArn\"]}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Attributes.QueueArn");

        listTargets("cfn-rule-targets-undeliverable")
                .body("Targets", hasSize(1))
                .body("Targets[0].DeadLetterConfig.Arn", equalTo(dlqArn))
                .body("Targets[0].RetryPolicy.MaximumRetryAttempts", equalTo(2))
                .body("Targets[0].RetryPolicy.MaximumEventAgeInSeconds", equalTo(60))
                .body("Targets[0]", not(hasKey("RoleArn")));
        listTargets("cfn-rule-targets-ecs")
                .body("Targets[0].RoleArn", equalTo("arn:aws:iam::000000000000:role/events-run-task"))
                .body("Targets[0].EcsParameters.TaskDefinitionArn",
                        equalTo("arn:aws:ecs:us-east-1:000000000000:task-definition/job:1"))
                .body("Targets[0].EcsParameters.NetworkConfiguration.awsvpcConfiguration.Subnets[0]",
                        equalTo("subnet-1"))
                .body("Targets[0].EcsParameters.PlacementStrategy[0].type", equalTo("spread"))
                .body("Targets[0].EcsParameters.Tags[0].Key", equalTo("team"))
                .body("Targets[0]", not(hasKey("RetryPolicy")))
                .body("Targets[0]", not(hasKey("DeadLetterConfig")));

        given().contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.PutEvents")
                .body("""
                        {"Entries": [{"EventBusName": "%s", "Source": "cfn.rule.targets",
                          "DetailType": "Undeliverable", "Detail": "{\\"orderId\\":\\"o-9\\"}"}]}
                        """.formatted(BUS))
                .when().post("/").then().statusCode(200)
                .body("FailedEntryCount", equalTo(0));

        String ruleArn = given().contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.DescribeRule")
                .body("{\"Name\":\"cfn-rule-targets-undeliverable\",\"EventBusName\":\"" + BUS + "\"}")
                .when().post("/").then().statusCode(200)
                .extract().jsonPath().getString("Arn");
        JsonPath received = given().contentType(SQS_CT)
                .header("X-Amz-Target", "AmazonSQS.ReceiveMessage")
                .body("{\"QueueUrl\":\"" + dlqUrl + "\",\"MaxNumberOfMessages\":10,"
                        + "\"MessageAttributeNames\":[\"All\"]}")
                .when().post("/").then().statusCode(200)
                .body("Messages", hasSize(1))
                .body("Messages[0].MessageAttributes.RULE_ARN.StringValue", equalTo(ruleArn))
                .body("Messages[0].MessageAttributes.TARGET_ARN.StringValue",
                        equalTo(dlqArn.replace("cfn-rule-targets-dlq", "cfn-rule-targets-missing")))
                .body("Messages[0].MessageAttributes.ERROR_CODE.StringValue", equalTo("NO_RESOURCE"))
                .body("Messages[0].MessageAttributes.ERROR_MESSAGE.StringValue", not(emptyOrNullString()))
                .body("Messages[0].MessageAttributes.RETRY_ATTEMPTS.StringValue", equalTo("0"))
                .body("Messages[0].MessageAttributes", not(hasKey("EXHAUSTED_RETRY_CONDITION")))
                .extract().jsonPath();
        assertEquals("o-9", new JsonPath(received.getString("Messages[0].Body")).getString("detail.orderId"));
    }

    /**
     * With the real engine: a member whose Fn::If takes AWS::NoValue is left out of the target,
     * and a literal empty string, a tag's Value, is kept.
     */
    @Test
    void aMemberAnFnIfDropsIsLeftOutAndAnEmptyStringKept() {
        String template = """
                {"Conditions": {"Never": {"Fn::Equals": ["a", "b"]}},
                 "Resources": {"Rule": {"Type": "AWS::Events::Rule", "Properties": {
                   "Name": "cfn-rule-targets-novalue",
                   "EventPattern": {"source": ["cfn.rule.targets.novalue"]},
                   "Targets": [{"Id": "Ecs", "Arn": "arn:aws:ecs:us-east-1:000000000000:cluster/jobs",
                     "RoleArn": {"Fn::If": ["Never", "arn:aws:iam::000000000000:role/never",
                                            {"Ref": "AWS::NoValue"}]},
                     "RetryPolicy": {"MaximumRetryAttempts": {"Fn::If": ["Never", 3, {"Ref": "AWS::NoValue"}]},
                                     "MaximumEventAgeInSeconds": 120},
                     "EcsParameters": {"TaskDefinitionArn": "arn:aws:ecs:us-east-1:000000000000:task-definition/job:1",
                                       "TagList": [{"Key": "team", "Value": ""}]}}]}}}}
                """;
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", "cfn-rule-targets-novalue")
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
        try {
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("cfn-rule-targets-novalue").status());
            listTargets("cfn-rule-targets-novalue", "default")
                    .body("Targets[0]", not(hasKey("RoleArn")))
                    .body("Targets[0].RetryPolicy", not(hasKey("MaximumRetryAttempts")))
                    .body("Targets[0].RetryPolicy.MaximumEventAgeInSeconds", equalTo(120))
                    .body("Targets[0].EcsParameters.Tags[0].Value", equalTo(""));
        } finally {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "DeleteStack")
                    .formParam("StackName", "cfn-rule-targets-novalue")
                    .when().post("/").then().statusCode(200);
        }
    }

    private static ValidatableResponse listTargets(String rule) {
        return listTargets(rule, BUS);
    }

    private static ValidatableResponse listTargets(String rule, String bus) {
        return given().contentType(EB_CT)
                .header("X-Amz-Target", "AWSEvents.ListTargetsByRule")
                .body("{\"Rule\":\"" + rule + "\",\"EventBusName\":\"" + bus + "\"}")
                .when().post("/").then().statusCode(200);
    }
}
