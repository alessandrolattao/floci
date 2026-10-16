package io.github.hectorvent.floci.services.cognito;

import com.amazonaws.encryptionsdk.AwsCrypto;
import com.amazonaws.encryptionsdk.CommitmentPolicy;
import com.amazonaws.encryptionsdk.CryptoAlgorithm;
import com.amazonaws.encryptionsdk.DataKey;
import com.amazonaws.encryptionsdk.EncryptedDataKey;
import com.amazonaws.encryptionsdk.MasterKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.testing.RestAssuredJsonUtils;
import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;

import static io.github.hectorvent.floci.services.cognito.CognitoRestAssuredUtils.cognitoJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * A Custom Email Sender over the wire: a KMS key and a pool made through their APIs, an email
 * sign-in code handed to the trigger asynchronously, and the code opened as the trigger opens it,
 * by the AWS Encryption SDK with KMS Decrypt over the KMS API, then used to finish the sign-in.
 */
@QuarkusTest
class CognitoCustomEmailSenderIntegrationTest {

    private static final String SENDER_ARN = "arn:aws:lambda:us-east-1:000000000000:function:code-sender";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    @InjectMock
    LambdaService lambdaService;

    @BeforeAll
    static void configureRestAssured() {
        RestAssuredJsonUtils.configureAwsContentTypes();
    }

    @BeforeEach
    void acceptInvocations() {
        when(lambdaService.invoke(anyString(), anyString(), any(byte[].class), any(InvocationType.class)))
                .thenReturn(new InvokeResult(202, null, new byte[0], null, "request-id"));
    }

    @Test
    void anEmailSignInCodeReachesTheSenderSealedAndOpensToACodeThatSignsIn() throws Exception {
        String keyArn = RestAssuredJsonUtils.awsActionJson("TrentService", "CreateKey", "{}")
                .path("KeyMetadata").path("Arn").asText();
        String poolId = cognitoJson("CreateUserPool", """
                {
                  "PoolName": "CustomSenderPool",
                  "UserPoolTier": "ESSENTIALS",
                  "UsernameAttributes": ["email"],
                  "AutoVerifiedAttributes": ["email"],
                  "Policies": { "SignInPolicy": { "AllowedFirstAuthFactors": ["PASSWORD", "EMAIL_OTP"] } },
                  "LambdaConfig": {
                    "CustomEmailSender": { "LambdaArn": "%s", "LambdaVersion": "V1_0" },
                    "KMSKeyID": "%s"
                  }
                }
                """.formatted(SENDER_ARN, keyArn)).path("UserPool").path("Id").asText();
        String clientId = cognitoJson("CreateUserPoolClient", """
                { "UserPoolId": "%s", "ClientName": "web", "ExplicitAuthFlows": ["ALLOW_USER_AUTH"] }
                """.formatted(poolId)).path("UserPoolClient").path("ClientId").asText();
        String email = "sender-" + UUID.randomUUID() + "@example.com";
        cognitoJson("AdminCreateUser", """
                {
                  "UserPoolId": "%s",
                  "Username": "%s",
                  "MessageAction": "SUPPRESS",
                  "UserAttributes": [
                    { "Name": "email", "Value": "%s" },
                    { "Name": "email_verified", "Value": "true" }
                  ]
                }
                """.formatted(poolId, email, email));

        JsonNode challenge = cognitoJson("InitiateAuth", """
                {
                  "ClientId": "%s",
                  "AuthFlow": "USER_AUTH",
                  "AuthParameters": { "USERNAME": "%s", "PREFERRED_CHALLENGE": "EMAIL_OTP" }
                }
                """.formatted(clientId, email));
        assertEquals("EMAIL_OTP", challenge.path("ChallengeName").asText());
        assertEquals("EMAIL", challenge.path("ChallengeParameters").path("CODE_DELIVERY_DELIVERY_MEDIUM").asText());

        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService).invoke(eq("us-east-1"), eq(SENDER_ARN), payload.capture(), eq(InvocationType.Event));
        JsonNode event = MAPPER.readTree(payload.getValue());
        assertEquals("CustomEmailSender_Authentication", event.path("triggerSource").asText());
        assertEquals(poolId, event.path("userPoolId").asText());
        assertEquals(email, event.path("request").path("userAttributes").path("email").asText());
        assertFalse(event.has("response"));

        String code = open(keyArn, event.path("request").path("code").asText());
        JsonNode signedIn = cognitoJson("RespondToAuthChallenge", """
                {
                  "ClientId": "%s",
                  "ChallengeName": "EMAIL_OTP",
                  "Session": "%s",
                  "ChallengeResponses": { "USERNAME": "%s", "EMAIL_OTP_CODE": "%s" }
                }
                """.formatted(clientId, challenge.path("Session").asText(),
                challenge.path("ChallengeParameters").path("USERNAME").asText(), code));
        assertTrue(signedIn.path("AuthenticationResult").path("AccessToken").isTextual());
    }

    /** Opens a code as the trigger does, its KMS keyring calling KMS Decrypt with the message's context. */
    private static String open(String keyArn, String code) {
        AwsCrypto crypto = AwsCrypto.builder().withCommitmentPolicy(CommitmentPolicy.RequireEncryptAllowDecrypt).build();
        byte[] plaintext = crypto.decryptData(new KmsApiKey(keyArn), Base64.getDecoder().decode(code)).getResult();
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private static final class KmsApiKey extends MasterKey<KmsApiKey> {

        private final String keyArn;

        KmsApiKey(String keyArn) {
            this.keyArn = keyArn;
        }

        @Override
        public String getProviderId() {
            return "aws-kms";
        }

        @Override
        public String getKeyId() {
            return keyArn;
        }

        @Override
        public DataKey<KmsApiKey> generateDataKey(CryptoAlgorithm algorithm, Map<String, String> context) {
            throw new UnsupportedOperationException("only opens");
        }

        @Override
        public DataKey<KmsApiKey> encryptDataKey(CryptoAlgorithm algorithm, Map<String, String> context,
                                                 DataKey<?> dataKey) {
            throw new UnsupportedOperationException("only opens");
        }

        @Override
        public DataKey<KmsApiKey> decryptDataKey(CryptoAlgorithm algorithm,
                                                 Collection<? extends EncryptedDataKey> encryptedDataKeys,
                                                 Map<String, String> context) {
            for (EncryptedDataKey key : encryptedDataKeys) {
                if (!keyArn.equals(new String(key.getProviderInformation(), StandardCharsets.UTF_8))) {
                    continue;
                }
                ObjectNode request = MAPPER.createObjectNode();
                request.put("CiphertextBlob", Base64.getEncoder().encodeToString(key.getEncryptedDataKey()));
                request.put("KeyId", keyArn);
                request.set("EncryptionContext", MAPPER.valueToTree(context));
                JsonNode decrypted;
                try {
                    decrypted = RestAssuredJsonUtils.awsActionJson("TrentService", "Decrypt", request.toString());
                } catch (Exception e) {
                    throw new IllegalStateException(e);
                }
                // The KMS keyring accepts the plaintext only from the key the message names.
                assertEquals(keyArn, decrypted.path("KeyId").asText());
                byte[] plaintext = Base64.getDecoder().decode(decrypted.path("Plaintext").asText());
                return new DataKey<>(new SecretKeySpec(plaintext, algorithm.getDataKeyAlgo()),
                        key.getEncryptedDataKey(), key.getProviderInformation(), this);
            }
            return null;
        }
    }
}
