package com.floci.test;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.DescribeStacksRequest;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.cloudformation.model.StackEvent;
import software.amazon.awssdk.services.ecs.EcsClient;
import software.amazon.awssdk.services.ecs.model.ContainerDefinition;
import software.amazon.awssdk.services.ecs.model.LinuxParameters;
import software.amazon.awssdk.services.ecs.model.Tmpfs;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A container's {@code LinuxParameters} in an {@code AWS::ECS::TaskDefinition} reach the task
 * definition, as the ECS SDK reads it back, and a Fargate template asking for swap fails as
 * {@code RegisterTaskDefinition} refuses it.
 */
@DisplayName("CloudFormation AWS::ECS::TaskDefinition LinuxParameters")
class CloudFormationEcsLinuxParametersTest {

    private static CloudFormationClient cloudFormation;
    private static EcsClient ecs;
    private static final List<String> STACKS = new ArrayList<>();

    @BeforeAll
    static void setup() {
        cloudFormation = TestFixtures.cloudFormationClient();
        ecs = TestFixtures.ecsClient();
    }

    @AfterAll
    static void cleanup() {
        if (cloudFormation != null) {
            for (String stack : STACKS) {
                try {
                    cloudFormation.deleteStack(r -> r.stackName(stack));
                } catch (Exception e) {
                    System.err.println("CloudFormation LinuxParameters cleanup skipped for " + stack + ": "
                            + e.getMessage());
                }
            }
            cloudFormation.close();
        }
        if (ecs != null) {
            ecs.close();
        }
    }

    @Test
    void linuxParametersReachTheTaskDefinition() throws InterruptedException {
        String name = TestFixtures.uniqueName("compat-cfn-linux");
        STACKS.add(name);
        cloudFormation.createStack(r -> r.stackName(name).templateBody("""
                {"Resources": {"TaskDef": {"Type": "AWS::ECS::TaskDefinition", "Properties": {
                  "Family": "%s", "RequiresCompatibilities": ["EC2"], "NetworkMode": "bridge",
                  "ContainerDefinitions": [{"Name": "worker", "Image": "busybox", "Memory": 512,
                    "LinuxParameters": {"InitProcessEnabled": true,
                      "Capabilities": {"Add": ["SYS_PTRACE"], "Drop": ["NET_RAW"]},
                      "SharedMemorySize": 256, "MaxSwap": 1024, "Swappiness": 10,
                      "Tmpfs": [{"ContainerPath": "/scratch", "Size": 64, "MountOptions": ["noexec"]}]}}]}}}}
                """.formatted(name)));
        assertThat(waitForTerminal(name)).isEqualTo("CREATE_COMPLETE");

        ContainerDefinition container = ecs.describeTaskDefinition(r -> r.taskDefinition(name))
                .taskDefinition().containerDefinitions().get(0);
        LinuxParameters linux = container.linuxParameters();
        assertThat(linux.initProcessEnabled()).isTrue();
        assertThat(linux.capabilities().add()).containsExactly("SYS_PTRACE");
        assertThat(linux.capabilities().drop()).containsExactly("NET_RAW");
        assertThat(linux.sharedMemorySize()).isEqualTo(256);
        assertThat(linux.maxSwap()).isEqualTo(1024);
        assertThat(linux.swappiness()).isEqualTo(10);
        Tmpfs tmpfs = linux.tmpfs().get(0);
        assertThat(tmpfs.containerPath()).isEqualTo("/scratch");
        assertThat(tmpfs.size()).isEqualTo(64);
        assertThat(tmpfs.mountOptions()).containsExactly("noexec");
    }

    @Test
    void aFargateTemplateAskingForSwapFailsAsRegisterTaskDefinitionDoes() throws InterruptedException {
        String name = TestFixtures.uniqueName("compat-cfn-linux-fargate");
        STACKS.add(name);
        cloudFormation.createStack(r -> r.stackName(name).templateBody("""
                {"Resources": {"TaskDef": {"Type": "AWS::ECS::TaskDefinition", "Properties": {
                  "Family": "%s", "RequiresCompatibilities": ["FARGATE"], "NetworkMode": "awsvpc",
                  "Cpu": "256", "Memory": "512",
                  "ContainerDefinitions": [{"Name": "worker", "Image": "busybox",
                    "LinuxParameters": {"MaxSwap": 512, "Swappiness": 10}}]}}}}
                """.formatted(name)));
        assertThat(waitForTerminal(name)).isEqualTo("ROLLBACK_COMPLETE");

        List<StackEvent> events = cloudFormation.describeStackEvents(r -> r.stackName(name)).stackEvents();
        assertThat(events).anySatisfy(event -> assertThat(event.resourceStatusReason())
                .contains("Fargate compatible task definitions do not support maxSwap"));
    }

    private static String waitForTerminal(String name) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 30_000L;
        while (System.currentTimeMillis() < deadline) {
            List<Stack> stacks = cloudFormation.describeStacks(
                    DescribeStacksRequest.builder().stackName(name).build()).stacks();
            if (!stacks.isEmpty() && !stacks.get(0).stackStatusAsString().endsWith("_IN_PROGRESS")) {
                return stacks.get(0).stackStatusAsString();
            }
            Thread.sleep(500);
        }
        throw new AssertionError("Stack " + name + " did not reach a terminal state within 30s");
    }
}
