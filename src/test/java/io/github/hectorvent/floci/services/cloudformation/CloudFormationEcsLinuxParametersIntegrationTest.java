package io.github.hectorvent.floci.services.cloudformation;

import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasKey;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An {@code AWS::ECS::TaskDefinition} container's {@code LinuxParameters} reach the task
 * definition as RegisterTaskDefinition keeps them: DescribeTaskDefinition shows them in the API's
 * shape, and the Fargate rules RegisterTaskDefinition applies to them apply to a template too.
 */
@QuarkusTest
class CloudFormationEcsLinuxParametersIntegrationTest {

    private static final String ECS_CT = "application/x-amz-json-1.1";
    private static final String ECS_TARGET = "AmazonEC2ContainerServiceV20141113.";

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @Test
    void linuxParametersReachTheTaskDefinition() {
        String template = """
                {
                  "Parameters": {"Shm": {"Type": "Number", "Default": "256"}},
                  "Resources": {
                    "TaskDef": {
                      "Type": "AWS::ECS::TaskDefinition",
                      "Properties": {
                        "Family": "cfn-linux-parameters",
                        "RequiresCompatibilities": ["EC2"],
                        "NetworkMode": "bridge",
                        "ContainerDefinitions": [
                          {
                            "Name": "worker", "Image": "busybox", "Memory": 512,
                            "LinuxParameters": {
                              "InitProcessEnabled": true,
                              "Capabilities": {"Add": ["SYS_PTRACE"], "Drop": ["NET_RAW"]},
                              "Devices": [{"HostPath": "/dev/fuse", "ContainerPath": "/dev/fuse",
                                           "Permissions": ["read", "write"]}],
                              "SharedMemorySize": {"Ref": "Shm"},
                              "MaxSwap": 1024,
                              "Swappiness": 10,
                              "Tmpfs": [{"ContainerPath": "/scratch", "Size": 64, "MountOptions": ["noexec"]}]
                            }
                          },
                          {"Name": "plain", "Image": "busybox", "Memory": 128, "Essential": false}
                        ]
                      }
                    }
                  }
                }
                """;
        try {
            createStack("cfn-linux-parameters", template);
            assertEquals("CREATE_COMPLETE", CfnStackWaits.awaitTerminal("cfn-linux-parameters").status());

        given().contentType(ECS_CT)
                .header("X-Amz-Target", ECS_TARGET + "DescribeTaskDefinition")
                .body("{\"taskDefinition\":\"cfn-linux-parameters\"}")
                .when().post("/").then().statusCode(200)
                .body("taskDefinition.containerDefinitions[0].name", equalTo("worker"))
                .body("taskDefinition.containerDefinitions[0].linuxParameters", equalTo(Map.of(
                        "initProcessEnabled", true,
                        "capabilities", Map.of("add", List.of("SYS_PTRACE"), "drop", List.of("NET_RAW")),
                        "devices", List.of(Map.of("hostPath", "/dev/fuse", "containerPath", "/dev/fuse",
                                "permissions", List.of("read", "write"))),
                        "sharedMemorySize", 256,
                        "maxSwap", 1024,
                        "swappiness", 10,
                        "tmpfs", List.of(Map.of("containerPath", "/scratch", "size", 64,
                                "mountOptions", List.of("noexec"))))))
                .body("taskDefinition.containerDefinitions[1].name", equalTo("plain"))
                .body("taskDefinition.containerDefinitions[1]", not(hasKey("linuxParameters")));
        } finally {
            deleteStack("cfn-linux-parameters");
        }
    }

    @Test
    void aFargateTemplateAskingForSwapFailsAsRegisterTaskDefinitionDoes() {
        String template = """
                {
                  "Resources": {
                    "TaskDef": {
                      "Type": "AWS::ECS::TaskDefinition",
                      "Properties": {
                        "Family": "cfn-linux-parameters-fargate",
                        "RequiresCompatibilities": ["FARGATE"],
                        "NetworkMode": "awsvpc",
                        "Cpu": "256",
                        "Memory": "512",
                        "ContainerDefinitions": [
                          {"Name": "worker", "Image": "busybox", "LinuxParameters": {"MaxSwap": 512, "Swappiness": 10}}
                        ]
                      }
                    }
                  }
                }
                """;
        try {
            createStack("cfn-linux-parameters-fargate", template);
            assertFargateSwapRefused(CfnStackWaits.awaitTerminal("cfn-linux-parameters-fargate"));
        } finally {
            deleteStack("cfn-linux-parameters-fargate");
        }
    }

    private static void assertFargateSwapRefused(CfnStackWaits.StackState state) {
        assertEquals("ROLLBACK_COMPLETE", state.status());
        List<String> reasons = given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DescribeStackEvents")
                .formParam("StackName", "cfn-linux-parameters-fargate")
                .when().post("/").then().statusCode(200).extract().xmlPath()
                .getList("**.findAll { it.name() == 'ResourceStatusReason' }", String.class);
        assertTrue(reasons.stream().anyMatch(reason -> reason != null
                        && reason.contains("Fargate compatible task definitions do not support maxSwap")),
                reasons.toString());
    }

    /**
     * Each test removes its stack, in a finally that also covers a create that failed, so the
     * fixed names are free when the tests run again.
     */
    private static void deleteStack(String name) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "DeleteStack")
                .formParam("StackName", name)
                .when().post("/").then().statusCode(200);
        CfnStackWaits.awaitStackDeleted(name);
    }

    private static void createStack(String name, String template) {
        given().contentType("application/x-www-form-urlencoded")
                .formParam("Action", "CreateStack")
                .formParam("StackName", name)
                .formParam("TemplateBody", template)
                .when().post("/").then().statusCode(200);
    }
}
