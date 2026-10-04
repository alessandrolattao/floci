package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.services.s3.S3Service;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code Fn::GetStackOutput} reads another stack's output, without an export, in the same
 * account and Region, another Region, or another account through a role. A stack or an output
 * that does not exist fails the deploy; it never resolves to an empty string.
 */
@QuarkusTest
class CloudFormationGetStackOutputIntegrationTest {

    private static final String DEFAULT_ACCOUNT = "000000000000";
    private static final String OTHER_ACCOUNT = "000000000042";
    private static final String US_EAST_1 = "us-east-1";
    private static final String EU_WEST_1 = "eu-west-1";
    private static final String SSM_CONTENT_TYPE = "application/x-amz-json-1.1";

    @Inject
    S3Service s3Service;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void sameAccountSameRegion_resourcePropertyGetsTheOutput() {
        createProducer(DEFAULT_ACCOUNT, US_EAST_1, "gso-producer", "vpc-0123456789");

        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-consumer", consumer("/gso/same",
                "{\"Fn::GetStackOutput\": {\"StackName\": \"gso-producer\", \"OutputName\": \"VpcId\"}}"));

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("gso-consumer").status());
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, "/gso/same").body("Parameter.Value", equalTo("vpc-0123456789"));
    }

    @Test
    void yamlShortFormInsideJoin_resolves() {
        createProducer(DEFAULT_ACCOUNT, US_EAST_1, "gso-yaml-producer", "subnet-42");
        String template = """
                Resources:
                  Copy:
                    Type: AWS::SSM::Parameter
                    Properties:
                      Name: /gso/yaml
                      Type: String
                      Value: !Join
                        - "-"
                        - - !GetStackOutput
                            StackName: gso-yaml-producer
                            OutputName: VpcId
                          - copy
                """;

        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-yaml-consumer", template);

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("gso-yaml-consumer").status());
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, "/gso/yaml").body("Parameter.Value", equalTo("subnet-42-copy"));
    }

    @Test
    void missingOutput_failsTheDeploy() {
        createProducer(DEFAULT_ACCOUNT, US_EAST_1, "gso-missing-output-producer", "vpc-1");

        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-missing-output", consumer("/gso/missing-output",
                "{\"Fn::GetStackOutput\": {\"StackName\": \"gso-missing-output-producer\", \"OutputName\": \"Nope\"}}"));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal("gso-missing-output");
        assertEquals("ROLLBACK_COMPLETE", state.status());
        assertThat(state.reason(), containsString("TemplateError: Fn::GetStackOutput references output Nope from stack"
                + " gso-missing-output-producer, but this output was not found. The output may have been deleted."));
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, "/gso/missing-output").statusCode(400);
    }

    @Test
    void missingStack_failsTheDeploy() {
        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-missing-stack", consumer("/gso/missing-stack",
                "{\"Fn::GetStackOutput\": {\"StackName\": \"gso-never-created\", \"OutputName\": \"VpcId\"}}"));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal("gso-missing-stack");
        assertEquals("ROLLBACK_COMPLETE", state.status());
        assertThat(state.reason(), containsString("Stack with id gso-never-created does not exist (Service:"
                + " AmazonCloudFormation; Status Code: 400; Error Code: ValidationError; Request ID: "));
        assertThat(state.reason(), endsWith("; Proxy: null)"));
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, "/gso/missing-stack").statusCode(400);
    }

    @Test
    void crossRegion_readsTheNamedRegionOnly() {
        createProducer(DEFAULT_ACCOUNT, EU_WEST_1, "gso-region-producer", "vpc-eu");

        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-region-consumer", consumer("/gso/region",
                "{\"Fn::GetStackOutput\": {\"StackName\": \"gso-region-producer\", \"OutputName\": \"VpcId\","
                        + " \"Region\": \"eu-west-1\"}}"));
        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-region-default", consumer("/gso/region-default",
                "{\"Fn::GetStackOutput\": {\"StackName\": \"gso-region-producer\", \"OutputName\": \"VpcId\"}}"));

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("gso-region-consumer").status());
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, "/gso/region").body("Parameter.Value", equalTo("vpc-eu"));
        assertEquals("ROLLBACK_COMPLETE", CfnStackWaits.awaitTerminal("gso-region-default").status());
    }

    @Test
    void crossAccount_readsThroughTheRoleAndFailsWithoutIt() {
        createRole(OTHER_ACCOUNT, "gso-reader");
        createProducer(OTHER_ACCOUNT, US_EAST_1, "gso-account-producer", "vpc-other-account");
        String roleArn = "arn:aws:iam::" + OTHER_ACCOUNT + ":role/gso-reader";

        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-account-consumer", consumer("/gso/account",
                "{\"Fn::GetStackOutput\": {\"StackName\": \"gso-account-producer\", \"OutputName\": \"VpcId\","
                        + " \"RoleArn\": \"" + roleArn + "\"}}"));
        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-account-no-role", consumer("/gso/account-no-role",
                "{\"Fn::GetStackOutput\": {\"StackName\": \"gso-account-producer\", \"OutputName\": \"VpcId\"}}"));
        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-account-missing-role", consumer("/gso/account-missing-role",
                "{\"Fn::GetStackOutput\": {\"StackName\": \"gso-account-producer\", \"OutputName\": \"VpcId\","
                        + " \"RoleArn\": \"arn:aws:iam::" + OTHER_ACCOUNT + ":role/gso-nobody\"}}"));

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("gso-account-consumer").status());
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, "/gso/account").body("Parameter.Value", equalTo("vpc-other-account"));
        assertThat(CfnStackWaits.awaitTerminal("gso-account-no-role").reason(),
                containsString("Stack with id gso-account-producer does not exist"));
        CfnStackWaits.StackState missingRole = CfnStackWaits.awaitTerminal("gso-account-missing-role");
        assertEquals("ROLLBACK_COMPLETE", missingRole.status());
        assertThat(missingRole.reason(), containsString("is not authorized to perform: sts:AssumeRole on resource: arn:aws:iam::"
                + OTHER_ACCOUNT + ":role/gso-nobody (Service: AWSSecurityTokenService; Status Code: 403;"
                + " Error Code: AccessDenied; Request ID: "));
    }

    /**
     * CloudFormation assumes the role to read the output, so the role's trust policy has to let
     * the consuming account in; a role that only trusts a service is refused like one that does
     * not exist.
     */
    @Test
    void crossAccount_aRoleThatDoesNotTrustTheAccountIsRefused() {
        createRole(OTHER_ACCOUNT, "gso-lambda-only", "{\"Service\":\"lambda.amazonaws.com\"}");
        createProducer(OTHER_ACCOUNT, US_EAST_1, "gso-untrusted-producer", "vpc-untrusted");
        String roleArn = "arn:aws:iam::" + OTHER_ACCOUNT + ":role/gso-lambda-only";

        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-untrusted-consumer", consumer("/gso/untrusted",
                "{\"Fn::GetStackOutput\": {\"StackName\": \"gso-untrusted-producer\", \"OutputName\": \"VpcId\","
                        + " \"RoleArn\": \"" + roleArn + "\"}}"));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal("gso-untrusted-consumer");
        assertEquals("ROLLBACK_COMPLETE", state.status());
        assertThat(state.reason(), containsString("User: arn:aws:iam::" + DEFAULT_ACCOUNT
                + ":root is not authorized to perform: sts:AssumeRole on resource: " + roleArn));
    }

    /**
     * Measured (eu-west-1, 2026-10-04): an output that cannot be read fails the create after the
     * resources exist, and the stack rolls back, deleting them.
     */
    @Test
    void anOutputThatCannotBeReadRollsTheStackBack() {
        createProducer(DEFAULT_ACCOUNT, US_EAST_1, "gso-output-producer", "vpc-output");
        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-output-consumer", """
                {"Resources": {"Copy": {"Type": "AWS::SSM::Parameter",
                  "Properties": {"Name": "/gso/output-rollback", "Type": "String", "Value": "created"}}},
                 "Outputs": {"Missing": {"Value": {"Fn::GetStackOutput": {
                   "StackName": "gso-output-producer", "OutputName": "Missing"}}}}}
                """);

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal("gso-output-consumer");
        assertEquals("ROLLBACK_COMPLETE", state.status());
        assertThat(state.reason(), containsString("TemplateError: Fn::GetStackOutput references output Missing"));
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, "/gso/output-rollback")
                .statusCode(400)
                .body(containsString("ParameterNotFound"));
    }

    /**
     * Measured (eu-west-1, 2026-10-04): CloudFormation does not resolve a dynamic reference inside
     * Fn::GetStackOutput; the stack name reaches DescribeStacks as written, which refuses it.
     */
    @Test
    void aDynamicReferenceInTheStackNameIsNotResolved() {
        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-dynamic-consumer", consumer("/gso/dynamic",
                "{\"Fn::GetStackOutput\": {\"StackName\": \"{{resolve:ssm:/gso/producer-name}}\","
                        + " \"OutputName\": \"VpcId\"}}"));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal("gso-dynamic-consumer");
        assertEquals("ROLLBACK_COMPLETE", state.status());
        assertThat(state.reason(), containsString("1 validation error detected: Value '{{resolve:ssm:/gso/producer-name}}'"
                + " at 'stackName' failed to satisfy constraint: Member must satisfy regular expression pattern:"
                + " [a-zA-Z][-a-zA-Z0-9]*|arn:[-a-zA-Z0-9:/._+]* (Service: AmazonCloudFormation; Status Code: 400;"
                + " Error Code: ValidationError; Request ID: "));
    }

    @Test
    void crossPartitionRegion_failsTheDeploy() {
        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-partition", consumer("/gso/partition",
                "{\"Fn::GetStackOutput\": {\"StackName\": \"gso-producer\", \"OutputName\": \"VpcId\","
                        + " \"Region\": \"cn-north-1\"}}"));

        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal("gso-partition");
        assertEquals("ROLLBACK_COMPLETE", state.status());
        assertThat(state.reason(), containsString("The security token included in the request is invalid (Service:"
                + " AmazonCloudFormation; Status Code: 403; Error Code: InvalidClientTokenId; Request ID: "));
    }

    @Test
    void directOutputsValue_resolves() {
        createProducer(DEFAULT_ACCOUNT, US_EAST_1, "gso-outputs-producer", "vpc-out");
        String template = """
                {"Outputs": {"Copied": {"Value":
                  {"Fn::GetStackOutput": {"StackName": "gso-outputs-producer", "OutputName": "VpcId"}}}}}
                """;

        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-outputs-consumer", template);

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("gso-outputs-consumer").status());
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks")
                .formParam("StackName", "gso-outputs-consumer")
                .when().post("/").then()
                .statusCode(200)
                .body(containsString("<OutputValue>vpc-out</OutputValue>"));
    }

    @Test
    void anywhereInConditions_isRefusedOnCreate() {
        String template = """
                {"Conditions": {"IsProd": {"Fn::Not": [{"Fn::Equals": [
                  {"Fn::GetStackOutput": {"StackName": "gso-producer", "OutputName": "VpcId"}}, "prod"]}]}},
                 "Resources": {"Topic": {"Type": "AWS::SNS::Topic", "Condition": "IsProd"}}}
                """;

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", "gso-conditions")
                .formParam("TemplateBody", template)
                .when().post("/").then()
                .statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("Template error: Cannot use Fn::GetStackOutput in Conditions."));
    }

    /** The placement rules apply to the template as processed, AWS::Include fragments merged. */
    @Test
    void inConditionsThroughAnInclude_isRefusedOnCreate() {
        String bucket = "gso-include-" + System.nanoTime();
        s3Service.createBucket(bucket, US_EAST_1);
        s3Service.putObject(bucket, "conditions.json", """
                {"IsProd": {"Fn::Equals": [
                  {"Fn::GetStackOutput": {"StackName": "gso-producer", "OutputName": "VpcId"}}, "prod"]}}
                """.getBytes(StandardCharsets.UTF_8), "application/json", Map.of());
        String template = """
                {"Conditions": {"Fn::Transform": {"Name": "AWS::Include",
                   "Parameters": {"Location": "s3://%s/conditions.json"}}},
                 "Resources": {"Topic": {"Type": "AWS::SNS::Topic", "Condition": "IsProd"}}}
                """.formatted(bucket);
        try {
            given().contentType("application/x-www-form-urlencoded")
                    .formParam("Action", "CreateStack")
                    .formParam("StackName", "gso-include-conditions")
                    .formParam("TemplateBody", template)
                    .when().post("/").then()
                    .statusCode(400)
                    .body(containsString("Template error: Cannot use Fn::GetStackOutput in Conditions."));
        } finally {
            s3Service.deleteObject(bucket, "conditions.json");
            s3Service.deleteBucket(bucket);
        }
    }

    @Test
    void insideImportValue_isRefusedOnCreate() {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", "gso-import")
                .formParam("TemplateBody", consumer("/gso/import", "{\"Fn::ImportValue\": {\"Fn::GetStackOutput\":"
                        + " {\"StackName\": \"gso-producer\", \"OutputName\": \"VpcId\"}}}"))
                .when().post("/").then()
                .statusCode(400)
                .body(containsString("<Code>ValidationError</Code>"))
                .body(containsString("Template error: the attribute in Fn::ImportValue must not depend on any"
                        + " resources, imported values, or Fn::GetAZs"));
    }

    /**
     * What AWS resolves beyond its documentation: a resource's Ref in a parameter, an Fn::Sub
     * variable map, Fn::Base64, and a key the function does not define, which is ignored.
     */
    @Test
    void parametersMayNameAResource_andEveryPositionResolves() {
        createProducer(DEFAULT_ACCOUNT, US_EAST_1, "gso-ref-producer", "vpc-ref");
        String template = """
                {"Resources": {
                  "Name": {"Type": "AWS::SSM::Parameter",
                    "Properties": {"Name": "gso-ref-producer", "Type": "String", "Value": "unused"}},
                  "ByRef": {"Type": "AWS::SSM::Parameter", "Properties": {"Name": "/gso/by-ref", "Type": "String",
                    "Value": {"Fn::GetStackOutput": {"StackName": {"Ref": "Name"}, "OutputName": "VpcId", "Extra": 1}}}},
                  "BySub": {"Type": "AWS::SSM::Parameter", "Properties": {"Name": "/gso/by-sub", "Type": "String",
                    "Value": {"Fn::Sub": ["x-${V}", {"V": {"Fn::GetStackOutput":
                      {"StackName": "gso-ref-producer", "OutputName": "VpcId"}}}]}}},
                  "ByBase64": {"Type": "AWS::SSM::Parameter", "Properties": {"Name": "/gso/by-base64", "Type": "String",
                    "Value": {"Fn::Base64": {"Fn::GetStackOutput":
                      {"StackName": "gso-ref-producer", "OutputName": "VpcId"}}}}}}}
                """;

        createStack(DEFAULT_ACCOUNT, US_EAST_1, "gso-ref-consumer", template);

        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("gso-ref-consumer").status());
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, "/gso/by-ref").body("Parameter.Value", equalTo("vpc-ref"));
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, "/gso/by-sub").body("Parameter.Value", equalTo("x-vpc-ref"));
        parameterValue(DEFAULT_ACCOUNT, US_EAST_1, "/gso/by-base64").body("Parameter.Value", equalTo("dnBjLXJlZg=="));
    }

    private static String consumer(String parameterName, String value) {
        return """
                {"Resources": {"Copy": {"Type": "AWS::SSM::Parameter",
                  "Properties": {"Name": "%s", "Type": "String", "Value": %s}}}}
                """.formatted(parameterName, value);
    }

    private static void createProducer(String account, String region, String stackName, String vpcId) {
        createStack(account, region, stackName,
                "{\"Outputs\": {\"VpcId\": {\"Value\": \"" + vpcId + "\"}}}");
        assertEquals("CREATE_COMPLETE",
                CfnStackWaits.awaitTerminal(stackName, auth(account, region, "cloudformation")).status());
    }

    private static void createStack(String account, String region, String stackName, String template) {
        given().header("Authorization", auth(account, region, "cloudformation"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", stackName)
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
    }

    private static void createRole(String account, String roleName) {
        createRole(account, roleName, "{\"AWS\":\"arn:aws:iam::" + DEFAULT_ACCOUNT + ":root\"}");
    }

    private static void createRole(String account, String roleName, String principal) {
        given().header("Authorization", auth(account, US_EAST_1, "iam"))
                .contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateRole")
                .formParam("RoleName", roleName)
                .formParam("AssumeRolePolicyDocument", "{\"Version\":\"2012-10-17\",\"Statement\":[{\"Effect\":"
                        + "\"Allow\",\"Principal\":" + principal + ",\"Action\":\"sts:AssumeRole\"}]}")
                .when().post("/").then().statusCode(200);
    }

    private static ValidatableResponse parameterValue(
            String account, String region, String name) {
        return given().header("Authorization", auth(account, region, "ssm"))
                .header("X-Amz-Target", "AmazonSSM.GetParameter")
                .contentType(SSM_CONTENT_TYPE)
                .body("{\"Name\": \"" + name + "\"}")
                .when().post("/").then();
    }

    private static String auth(String accessKeyId, String region, String service) {
        return "AWS4-HMAC-SHA256 Credential=" + accessKeyId
                + "/20260907/" + region + "/" + service + "/aws4_request,"
                + " SignedHeaders=host, Signature=abc";
    }
}
