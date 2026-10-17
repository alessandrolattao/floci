package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.core.common.XmlParser;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code AWS::DSQL::Cluster}, measured on CloudFormation (eu-west-1, 2026-10-16): Ref is the
 * identifier, the read-only properties are attributes, a cluster created without
 * DeletionProtectionEnabled is protected, and deleting the stack then fails.
 */
@QuarkusTest
class CloudFormationDsqlIntegrationTest {

    private static final String TEMPLATE = """
            {"Resources": {"Cluster": {"Type": "AWS::DSQL::Cluster", "Properties": %s}},
             "Outputs": {
               "Ref": {"Value": {"Ref": "Cluster"}},
               "Identifier": {"Value": {"Fn::GetAtt": ["Cluster", "Identifier"]}},
               "ResourceArn": {"Value": {"Fn::GetAtt": ["Cluster", "ResourceArn"]}},
               "Status": {"Value": {"Fn::GetAtt": ["Cluster", "Status"]}},
               "Endpoint": {"Value": {"Fn::GetAtt": ["Cluster", "Endpoint"]}},
               "VpcEndpoint": {"Value": {"Fn::GetAtt": ["Cluster", "VpcEndpoint"]}},
               "VpcEndpointServiceName": {"Value": {"Fn::GetAtt": ["Cluster", "VpcEndpointServiceName"]}},
               "CreationTime": {"Value": {"Fn::GetAtt": ["Cluster", "CreationTime"]}}}}
            """;

    @Test
    void aClusterGetsItsReferenceAndAttributes() {
        createStack("dsql-cluster", TEMPLATE.formatted("""
                {"DeletionProtectionEnabled": false, "Tags": [{"Key": "team", "Value": "data"}]}"""));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("dsql-cluster").status());

        Map<String, String> outputs = outputs("dsql-cluster");
        String identifier = outputs.get("Ref");
        assertThat(identifier, matchesPattern("[a-z2-7]{26}"));
        assertEquals(identifier, outputs.get("Identifier"));
        assertEquals("arn:aws:dsql:us-east-1:000000000000:cluster/" + identifier, outputs.get("ResourceArn"));
        assertEquals("ACTIVE", outputs.get("Status"));
        assertEquals(identifier + ".dsql.us-east-1.localhost.floci.io", outputs.get("Endpoint"));
        assertThat(outputs.get("VpcEndpoint"), matchesPattern(identifier + "\\.dsql-[a-z2-7]{4}\\.us-east-1\\..+"));
        assertThat(outputs.get("VpcEndpointServiceName"), matchesPattern("com\\.amazonaws\\.us-east-1\\.dsql-[a-z2-7]{4}"));
        assertThat(outputs.get("CreationTime"), matchesPattern("\\d{4}-\\d\\d-\\d\\dT\\d\\d:\\d\\d:\\d\\d\\.\\d{3}Z"));

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack").formParam("StackName", "dsql-cluster")
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted("dsql-cluster");
    }

    @Test
    void aProtectedClusterKeepsItsStackFromBeingDeleted() {
        createStack("dsql-protected", TEMPLATE.formatted("{}"));
        assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("dsql-protected").status());

        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack").formParam("StackName", "dsql-protected")
                .when().post("/").then().statusCode(200);
        CfnStackWaits.StackState state = CfnStackWaits.awaitTerminal("dsql-protected");
        assertEquals("DELETE_FAILED", state.status());
        assertThat(state.reason(), containsString("failed to delete"));
    }

    private static void createStack(String name, String template) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack").formParam("StackName", name).formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
    }

    private static Map<String, String> outputs(String name) {
        String xml = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStacks").formParam("StackName", name)
                .when().post("/").then().statusCode(200).extract().asString();
        return XmlParser.extractPairs(xml, "Outputs", "OutputKey", "OutputValue");
    }
}
