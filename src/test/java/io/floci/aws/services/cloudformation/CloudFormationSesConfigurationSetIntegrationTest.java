package io.floci.aws.services.cloudformation;

import io.floci.aws.services.ses.SesConfigurationSetService;
import io.floci.aws.services.ses.SesIdentityService;
import io.floci.aws.testing.AwsTestClients;
import io.floci.aws.testing.CfnStackWaits;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudformation.CloudFormationClient;
import software.amazon.awssdk.services.cloudformation.model.CloudFormationException;
import software.amazon.awssdk.services.cloudformation.model.Parameter;
import software.amazon.awssdk.services.cloudformation.model.Stack;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.DeliveryOptions;
import software.amazon.awssdk.services.sesv2.model.NotFoundException;
import software.amazon.awssdk.services.sesv2.model.TlsPolicy;

import java.time.Duration;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@QuarkusTest
class CloudFormationSesConfigurationSetIntegrationTest {
    private CloudFormationClient cfn;
    private SesV2Client ses;
    private String stackName;
    private String dedicatedIpPool;
    private boolean deleted;

    @Inject
    SesConfigurationSetService configurationSets;

    @Inject
    SesIdentityService identities;

    @BeforeEach
    void setUp() {
        cfn = AwsTestClients.create(CloudFormationClient.builder(), "test", Region.US_EAST_1);
        ses = AwsTestClients.create(SesV2Client.builder(), "test", Region.US_EAST_1);
        stackName = "cfn-ses-set-" + Long.toString(System.nanoTime(), 36);
        dedicatedIpPool = null;
        deleted = false;
    }

    @AfterEach
    void cleanUp() {
        try {
            if (!deleted) {
                cfn.deleteStack(request -> request.stackName(stackName));
                await().atMost(Duration.ofSeconds(15)).until(this::stackIsGone);
            }
            if (dedicatedIpPool != null) {
                ses.deleteDedicatedIpPool(request -> request.poolName(dedicatedIpPool));
            }
        } finally {
            cfn.close();
            ses.close();
        }
    }

    @Test
    void configurationSetRefCreatesTheSetBeforeItsEmailIdentityAndDeletesBoth() {
        String setName = stackName + "-delivery";
        String email = stackName + "@example.com";
        String template = """
                {
                  "AWSTemplateFormatVersion": "2010-09-09",
                  "Resources": {
                    "ConfigurationSet": {
                      "Type": "AWS::SES::ConfigurationSet",
                      "Properties": {"Name": "%s"}
                    },
                    "Identity": {
                      "Type": "AWS::SES::EmailIdentity",
                      "Properties": {
                        "EmailIdentity": "%s",
                        "ConfigurationSetAttributes": {
                          "ConfigurationSetName": {"Ref": "ConfigurationSet"}
                        }
                      }
                    }
                  },
                  "Outputs": {
                    "ConfigurationSetRef": {"Value": {"Ref": "ConfigurationSet"}}
                  }
                }
                """.formatted(setName, email);

        cfn.createStack(request -> request.stackName(stackName).templateBody(template));
        Stack stack = CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");

        assertEquals(setName, stack.outputs().getFirst().outputValue());
        assertEquals(setName, configurationSets.get(setName, "us-east-1").getName());
        assertEquals(setName, identities.getIdentityVerificationAttributes(email, "us-east-1")
                .getConfigurationSetName());

        cfn.deleteStack(request -> request.stackName(stackName));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            assertNull(configurationSets.find(setName, "us-east-1").orElse(null));
            assertNull(identities.getIdentityVerificationAttributes(email, "us-east-1"));
        });
        await().atMost(Duration.ofSeconds(15)).until(this::stackIsGone);
        deleted = true;
    }

    @Test
    void addingTheExistingGeneratedNameIsRefusedAndRollsBack() {
        String generated = createUnnamedConfigurationSet();
        String updatedTemplate = configurationSetTemplate("{\"Name\": \"" + generated + "\"}");
        cfn.updateStack(request -> request.stackName(stackName).templateBody(updatedTemplate));

        Stack rolledBack = CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_ROLLBACK_COMPLETE");
        assertTrue(CfnStackWaits.failedResourceReason(stackName).contains("custom-named resource requires"));
        assertEquals(generated, rolledBack.outputs().getFirst().outputValue());
        assertEquals(generated, configurationSets.get(generated, "us-east-1").getName());
    }

    @Test
    void replacingTheGeneratedNameRollsBackAfterADependentResourceFails() {
        String generated = createUnnamedConfigurationSet();
        String replacement = stackName + "-named";
        updateWithDependentFailure("{\"Name\": \"" + replacement + "\"}");

        Stack rolledBack = CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(generated, rolledBack.outputs().getFirst().outputValue());
        assertEquals(generated, configurationSets.get(generated, "us-east-1").getName());
        assertNull(configurationSets.find(replacement, "us-east-1").orElse(null));
    }

    @Test
    void deliveryOptionsAreSetOnCreateAndUpdatedInPlaceUntilAnUpdateRemovesThem() {
        String setName = stackName + "-tls";
        dedicatedIpPool = stackName + "-pool";
        ses.createDedicatedIpPool(request -> request.poolName(dedicatedIpPool));

        Stack created = CfnStackWaits.createStack(cfn, stackName, configurationSetTemplate("""
                {"Name": "%s", "DeliveryOptions":
                  {"TlsPolicy": "REQUIRE", "SendingPoolName": "%s", "MaxDeliverySeconds": 600}}
                """.formatted(setName, dedicatedIpPool)), "CREATE_COMPLETE");

        assertEquals(setName, created.outputs().getFirst().outputValue());
        DeliveryOptions options = deliveryOptions(setName);
        assertEquals(TlsPolicy.REQUIRE, options.tlsPolicy());
        assertEquals(dedicatedIpPool, options.sendingPoolName());
        assertEquals(600L, options.maxDeliverySeconds());

        Stack updated = CfnStackWaits.updateStack(cfn, stackName, configurationSetTemplate("""
                {"Name": "%s", "DeliveryOptions": {"TlsPolicy": "OPTIONAL"}}
                """.formatted(setName)), "UPDATE_COMPLETE");

        assertEquals(setName, updated.outputs().getFirst().outputValue());
        options = deliveryOptions(setName);
        assertEquals(TlsPolicy.OPTIONAL, options.tlsPolicy());
        assertNull(options.sendingPoolName());
        assertNull(options.maxDeliverySeconds());

        CfnStackWaits.updateStack(cfn, stackName,
                configurationSetTemplate("{\"Name\": \"" + setName + "\"}"), "UPDATE_COMPLETE");

        assertNull(deliveryOptions(setName));
    }

    @Test
    void inPlaceDeliveryOptionsUpdateRollsBackAfterADependentResourceFails() {
        String setName = stackName + "-tls";
        CfnStackWaits.createStack(cfn, stackName, configurationSetTemplate("""
                {"Name": "%s", "DeliveryOptions": {"TlsPolicy": "REQUIRE"}}
                """.formatted(setName)), "CREATE_COMPLETE");

        updateWithDependentFailure("""
                {"Name": "%s", "DeliveryOptions": {"TlsPolicy": "OPTIONAL"}}
                """.formatted(setName));

        CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_ROLLBACK_COMPLETE");
        assertEquals(TlsPolicy.REQUIRE, deliveryOptions(setName).tlsPolicy());
    }

    @Test
    void conditionalDeliveryOptionsFollowTheirCondition() {
        String setName = stackName + "-tls";
        String template = """
                {
                  "Parameters": {"Strict": {"Type": "String", "AllowedValues": ["true", "false"]}},
                  "Conditions": {"IsStrict": {"Fn::Equals": [{"Ref": "Strict"}, "true"]}},
                  "Resources": {
                    "ConfigurationSet": {
                      "Type": "AWS::SES::ConfigurationSet",
                      "Properties": {
                        "Name": "%s",
                        "DeliveryOptions": {"Fn::If": ["IsStrict", {"TlsPolicy": "REQUIRE"}, {"Ref": "AWS::NoValue"}]}
                      }
                    }
                  }
                }
                """.formatted(setName);

        cfn.createStack(request -> request.stackName(stackName).templateBody(template)
                .parameters(Parameter.builder().parameterKey("Strict").parameterValue("true").build()));
        CfnStackWaits.awaitStatus(cfn, stackName, "CREATE_COMPLETE");
        assertEquals(TlsPolicy.REQUIRE, deliveryOptions(setName).tlsPolicy());

        cfn.updateStack(request -> request.stackName(stackName).templateBody(template)
                .parameters(Parameter.builder().parameterKey("Strict").parameterValue("false").build()));
        CfnStackWaits.awaitStatus(cfn, stackName, "UPDATE_COMPLETE");
        assertNull(deliveryOptions(setName));
    }

    @Test
    void invalidTlsPolicyFailsTheStackWithTheSesV2Validation() {
        String setName = stackName + "-tls";
        cfn.createStack(request -> request.stackName(stackName).templateBody(configurationSetTemplate("""
                {"Name": "%s", "DeliveryOptions": {"TlsPolicy": "STRICT"}}
                """.formatted(setName))));

        CfnStackWaits.awaitStatus(cfn, stackName, "ROLLBACK_COMPLETE");
        assertTrue(CfnStackWaits.failedResourceReason(stackName).contains("Value at 'tlsPolicy' failed to "
                + "satisfy constraint: Member must satisfy enum value set: [OPTIONAL, REQUIRE]"));
        assertThrows(NotFoundException.class,
                () -> ses.getConfigurationSet(request -> request.configurationSetName(setName)));
    }

    private DeliveryOptions deliveryOptions(String setName) {
        return ses.getConfigurationSet(request -> request.configurationSetName(setName)).deliveryOptions();
    }

    private static String configurationSetTemplate(String properties) {
        return """
                {
                  "Resources": {
                    "ConfigurationSet": {"Type": "AWS::SES::ConfigurationSet", "Properties": %s}
                  },
                  "Outputs": {"ConfigurationSetRef": {"Value": {"Ref": "ConfigurationSet"}}}
                }
                """.formatted(properties);
    }

    private String createUnnamedConfigurationSet() {
        return CfnStackWaits.createStack(cfn, stackName, configurationSetTemplate("{}"), "CREATE_COMPLETE")
                .outputs().getFirst().outputValue();
    }

    private void updateWithDependentFailure(String configurationSetProperties) {
        String updatedTemplate = """
                {
                  "Resources": {
                    "ConfigurationSet": {
                      "Type": "AWS::SES::ConfigurationSet",
                      "Properties": %s
                    },
                    "BadSecret": {
                      "Type": "AWS::SecretsManager::Secret",
                      "Properties": {
                        "SecretString": {"Ref": "ConfigurationSet"},
                        "GenerateSecretString": {"PasswordLength": 8}
                      }
                    }
                  },
                  "Outputs": {"ConfigurationSetRef": {"Value": {"Ref": "ConfigurationSet"}}}
                }
                """.formatted(configurationSetProperties);
        cfn.updateStack(request -> request.stackName(stackName).templateBody(updatedTemplate));
    }

    private boolean stackIsGone() {
        try {
            return cfn.describeStacks(request -> request.stackName(stackName)).stacks().isEmpty();
        } catch (CloudFormationException expected) {
            return expected.statusCode() == 400;
        }
    }
}
