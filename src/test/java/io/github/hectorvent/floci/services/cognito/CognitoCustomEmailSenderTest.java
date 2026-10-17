package io.github.hectorvent.floci.services.cognito;

import com.amazonaws.encryptionsdk.AwsCrypto;
import com.amazonaws.encryptionsdk.CommitmentPolicy;
import com.amazonaws.encryptionsdk.CryptoAlgorithm;
import com.amazonaws.encryptionsdk.DataKey;
import com.amazonaws.encryptionsdk.EncryptedDataKey;
import com.amazonaws.encryptionsdk.MasterKey;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.config.TlsCertificateManager;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.core.common.RegionResolver;
import io.github.hectorvent.floci.core.storage.InMemoryStorage;
import io.github.hectorvent.floci.services.acm.AcmService;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.cognito.verification.CognitoCustomEmailSender;
import io.github.hectorvent.floci.services.cognito.verification.CognitoMessageDispatcher;
import io.github.hectorvent.floci.services.cognito.verification.VerificationCodeService;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import io.github.hectorvent.floci.services.lambda.model.InvokeResult;
import io.github.hectorvent.floci.services.ses.SesService;
import io.github.hectorvent.floci.services.sns.SnsService;
import io.github.hectorvent.floci.testing.InMemoryStorageFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.Base64;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * A pool whose LambdaConfig names a Custom Email Sender hands it every email, with the code sealed
 * under the pool's KMS key. Each event is checked against what AWS sent (eu-west-1, 2026-10-16),
 * each code is opened by the AWS Encryption SDK through Floci's KMS, as the trigger's keyring opens
 * it, and then used: it confirms the sign-up, completes the sign-in, resets the password.
 */
class CognitoCustomEmailSenderTest {

    private static final String REGION = "us-east-1";
    private static final String SENDER_ARN = "arn:aws:lambda:us-east-1:000000000000:function:code-sender";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final AwsCrypto CRYPTO = AwsCrypto.builder()
            .withCommitmentPolicy(CommitmentPolicy.RequireEncryptAllowDecrypt)
            .build();

    private LambdaService lambdaService;
    private KmsService kmsService;
    private SesService ses;
    private SnsService sns;
    private CognitoService service;
    private String keyArn;
    private UserPool pool;
    private String clientId;

    @BeforeEach
    void setUp() {
        RegionResolver regionResolver = new RegionResolver(REGION, "000000000000");
        InMemoryStorageFactory storageFactory = new InMemoryStorageFactory("000000000000");
        lambdaService = mock(LambdaService.class);
        when(lambdaService.invoke(anyString(), anyString(), any(byte[].class), eq(InvocationType.Event)))
                .thenReturn(new InvokeResult(202, null, new byte[0], null, "request-id"));
        kmsService = new KmsService(storageFactory, regionResolver);
        ses = mock(SesService.class);
        sns = mock(SnsService.class);
        service = new CognitoService(
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(), new InMemoryStorage<>(),
                new InMemoryStorage<>(), new InMemoryStorage<>(),
                "http://localhost:4566", null, regionResolver, lambdaService, mock(AcmService.class),
                new VerificationCodeService(storageFactory, Clock.systemUTC()),
                new CognitoMessageDispatcher(ses, sns, REGION),
                new CognitoCustomEmailSender(lambdaService, kmsService, REGION),
                mock(TlsCertificateManager.class), Clock.systemUTC(), true);

        keyArn = kmsService.createKey("seals the codes", REGION).getArn();
        Map<String, Object> request = new HashMap<>();
        request.put("PoolName", "custom-sender");
        request.put("UserPoolTier", "ESSENTIALS");
        request.put("UsernameAttributes", List.of("email"));
        request.put("AutoVerifiedAttributes", List.of("email"));
        request.put("Policies", Map.of("SignInPolicy", Map.of("AllowedFirstAuthFactors", List.of("PASSWORD", "EMAIL_OTP"))));
        request.put("AccountRecoverySetting",
                Map.of("RecoveryMechanisms", List.of(Map.of("Name", "verified_email", "Priority", 1))));
        request.put("LambdaConfig", Map.of(
                "CustomEmailSender", Map.of("LambdaArn", SENDER_ARN, "LambdaVersion", "V1_0"),
                "KMSKeyID", keyArn));
        pool = service.createUserPool(request, REGION);
        clientId = service.createUserPoolClient(pool.getId(), "web", false, false, List.of(), List.of(), null,
                List.of(), null, List.of("ALLOW_USER_AUTH", "ALLOW_USER_PASSWORD_AUTH", "ALLOW_REFRESH_TOKEN_AUTH"),
                null, null, List.of(), null, List.of(), null, null, null, List.of(), null, null).getClientId();
    }

    @Test
    void signUpHandsTheCodeToTheSenderAndTheCodeConfirmsTheUser() throws Exception {
        CognitoUser user = service.signUp(clientId, "ann@example.com", "Probe-Passw0rd!x",
                Map.of("email", "ann@example.com"), Map.of(), Map.of());

        JsonNode event = lastEvent();
        assertEquals("1", event.path("version").asText());
        assertEquals("CustomEmailSender_SignUp", event.path("triggerSource").asText());
        assertEquals(REGION, event.path("region").asText());
        assertEquals(pool.getId(), event.path("userPoolId").asText());
        assertEquals(user.getUsername(), event.path("userName").asText());
        assertEquals(clientId, event.path("callerContext").path("clientId").asText());
        JsonNode request = event.path("request");
        assertEquals("customEmailSenderRequestV1", request.path("type").asText());
        assertTrue(request.path("clientMetadata").isNull());
        assertEquals("ann@example.com", request.path("userAttributes").path("email").asText());
        assertEquals("UNCONFIRMED", request.path("userAttributes").path("cognito:user_status").asText());
        assertTrue(!event.has("response"), "the event AWS sends has no response member");

        String code = open(request.path("code").asText());
        assertTrue(code.matches("[0-9]{6}"), code);
        service.confirmSignUp(clientId, "ann@example.com", code);
        assertEquals("CONFIRMED", service.adminGetUser(pool.getId(), "ann@example.com").getUserStatus());
        verifyNoInteractions(ses);
    }

    @Test
    void anEmailSignInCodeGoesToTheSenderAndCompletesTheSignIn() throws Exception {
        service.adminCreateUser(pool.getId(), "bob@example.com",
                Map.of("email", "bob@example.com", "email_verified", "true"), null, "SUPPRESS");

        Map<String, Object> challenge = service.initiateAuth(clientId, "USER_AUTH",
                Map.of("USERNAME", "bob@example.com", "PREFERRED_CHALLENGE", "EMAIL_OTP"));

        assertEquals("EMAIL_OTP", challenge.get("ChallengeName"));
        JsonNode event = lastEvent();
        assertEquals("CustomEmailSender_Authentication", event.path("triggerSource").asText());
        assertEquals(clientId, event.path("callerContext").path("clientId").asText());
        assertEquals("CONFIRMED", event.path("request").path("userAttributes").path("cognito:user_status").asText());
        @SuppressWarnings("unchecked")
        Map<String, String> parameters = (Map<String, String>) challenge.get("ChallengeParameters");
        Map<String, Object> result = service.respondToAuthChallenge(clientId, "EMAIL_OTP",
                (String) challenge.get("Session"), Map.of("USERNAME", parameters.get("USERNAME"),
                        "EMAIL_OTP_CODE", open(event.path("request").path("code").asText())));
        assertNotNull(result.get("AuthenticationResult"));
        verifyNoInteractions(ses);
    }

    @Test
    void anInvitationNamesTheUserAsTheRequestDidAndCarriesTheTemporaryPassword() throws Exception {
        service.adminCreateUser(pool.getId(), "cy@example.com", Map.of("email", "cy@example.com"),
                "Temp-Passw0rd!x", null, false, List.of("EMAIL"), Map.of(), Map.of());

        JsonNode event = lastEvent();
        assertEquals("CustomEmailSender_AdminCreateUser", event.path("triggerSource").asText());
        assertEquals("cy@example.com", event.path("userName").asText());
        assertEquals(CognitoCustomEmailSender.CLIENT_ID_NOT_APPLICABLE,
                event.path("callerContext").path("clientId").asText());
        assertEquals("FORCE_CHANGE_PASSWORD",
                event.path("request").path("userAttributes").path("cognito:user_status").asText());
        assertEquals("Temp-Passw0rd!x", open(event.path("request").path("code").asText()));
        verifyNoInteractions(ses, sns);
    }

    @Test
    void anInvitationWithoutAPasswordCarriesNoCode() throws Exception {
        service.adminCreateUser(pool.getId(), "dee@example.com", Map.of("email", "dee@example.com"),
                null, null, false, List.of("EMAIL"), Map.of(), Map.of());

        JsonNode event = lastEvent();
        assertEquals("CustomEmailSender_AdminCreateUser", event.path("triggerSource").asText());
        assertTrue(event.path("request").path("code").isNull());
    }

    @Test
    void resendAndForgotPasswordCodesGoToTheSenderAndWork() throws Exception {
        service.signUp(clientId, "eve@example.com", "Probe-Passw0rd!x", Map.of("email", "eve@example.com"), Map.of(), Map.of());
        service.resendConfirmationCode(clientId, "eve@example.com");
        JsonNode resend = lastEvent();
        assertEquals("CustomEmailSender_ResendCode", resend.path("triggerSource").asText());
        service.confirmSignUp(clientId, "eve@example.com", open(resend.path("request").path("code").asText()));

        service.forgotPassword(clientId, "eve@example.com");
        JsonNode forgot = lastEvent();
        assertEquals("CustomEmailSender_ForgotPassword", forgot.path("triggerSource").asText());
        service.confirmForgotPassword(clientId, "eve@example.com", open(forgot.path("request").path("code").asText()),
                "Other-Passw0rd!x");
        Map<String, Object> signedIn = service.initiateAuth(clientId, "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "eve@example.com", "PASSWORD", "Other-Passw0rd!x"));
        assertNotNull(signedIn.get("AuthenticationResult"));
        verifyNoInteractions(ses);
    }

    @Test
    void attributeCodesNameTheirSourceAndTheTokenClient() throws Exception {
        service.signUp(clientId, "fay@example.com", "Probe-Passw0rd!x", Map.of("email", "fay@example.com"), Map.of(), Map.of());
        service.confirmSignUp(clientId, "fay@example.com", open(lastEvent().path("request").path("code").asText()));
        @SuppressWarnings("unchecked")
        Map<String, Object> tokens = (Map<String, Object>) service.initiateAuth(clientId, "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "fay@example.com", "PASSWORD", "Probe-Passw0rd!x")).get("AuthenticationResult");
        String accessToken = (String) tokens.get("AccessToken");

        service.getUserAttributeVerificationCode(accessToken, "email");
        JsonNode verify = lastEvent();
        assertEquals("CustomEmailSender_VerifyUserAttribute", verify.path("triggerSource").asText());
        assertEquals(clientId, verify.path("callerContext").path("clientId").asText());
        service.verifyUserAttribute(accessToken, "email", open(verify.path("request").path("code").asText()));

        service.updateUserAttributes(accessToken, new HashMap<>(Map.of("email", "fay2@example.com")));
        JsonNode update = lastEvent();
        assertEquals("CustomEmailSender_UpdateUserAttribute", update.path("triggerSource").asText());
        assertEquals("fay2@example.com", update.path("request").path("userAttributes").path("email").asText());
        assertEquals("false", update.path("request").path("userAttributes").path("email_verified").asText());
        verifyNoInteractions(ses);
    }

    /** Measured: a sender needs a KMSKeyID, on CreateUserPool and on UpdateUserPool alike. */
    @Test
    void aPoolWithASenderNeedsAKey() {
        Map<String, Object> senderOnly = Map.of(
                "CustomEmailSender", Map.of("LambdaArn", SENDER_ARN, "LambdaVersion", "V1_0"));
        AwsException created = assertThrows(AwsException.class, () -> service.createUserPool(
                new HashMap<>(Map.of("PoolName", "no-key", "LambdaConfig", senderOnly)), REGION));
        assertEquals("InvalidParameterException", created.getErrorCode());
        assertEquals("KMSKeyId parameter is required when Custom SMS/Email Lambda trigger is used.",
                created.getMessage());
        AwsException updated = assertThrows(AwsException.class, () -> service.updateUserPool(
                new HashMap<>(Map.of("UserPoolId", pool.getId(), "LambdaConfig", senderOnly)), REGION));
        assertEquals("InvalidParameterException", updated.getErrorCode());
    }

    /**
     * Measured: naming a key grants Cognito Encrypt and GenerateDataKey on it under the pool's id,
     * and a key the grant cannot be created on refuses the pool.
     */
    @Test
    void namingAKeyGrantsCognitoItsUse() {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> grants = (List<Map<String, Object>>) kmsService
                .listGrants(keyArn, REGION, null, null, null, null).get("Grants");
        assertEquals(1, grants.size());
        Map<String, Object> grant = grants.get(0);
        assertEquals("cognito-encrypt-grant", grant.get("Name"));
        assertEquals("cognito-idp.us-east-1.amazonaws.com", grant.get("GranteePrincipal"));
        assertEquals("cognito-idp.us-east-1.amazonaws.com", grant.get("RetiringPrincipal"));
        assertEquals(List.of("Encrypt", "GenerateDataKey"), grant.get("Operations"));
        assertEquals(Map.of("EncryptionContextSubset", Map.of("userpool-id", pool.getId())), grant.get("Constraints"));

        String missing = "arn:aws:kms:us-east-1:000000000000:key/00000000-0000-0000-0000-000000000000";
        AwsException refused = assertThrows(AwsException.class, () -> service.createUserPool(new HashMap<>(Map.of(
                "PoolName", "missing-key", "LambdaConfig", Map.of(
                        "CustomEmailSender", Map.of("LambdaArn", SENDER_ARN, "LambdaVersion", "V1_0"),
                        "KMSKeyID", missing))), REGION));
        assertEquals("InvalidParameterException", refused.getErrorCode());
        assertEquals("Encountered error when CreateGrant was attempted on the input KMS key - Key '" + missing
                + "' does not exist", refused.getMessage());
    }

    /**
     * Measured: a disabled key fails each request that sends a code with CodeDeliveryFailureException,
     * a sign-in included, while the sign-up it was part of keeps its user.
     */
    @Test
    void aDisabledKeyFailsTheRequestButKeepsTheSignUp() {
        service.adminCreateUser(pool.getId(), "gil@example.com",
                Map.of("email", "gil@example.com", "email_verified", "true"), null, "SUPPRESS");
        kmsService.disableKey(keyArn, REGION);

        AwsException signUp = assertThrows(AwsException.class, () -> service.signUp(clientId, "hal@example.com",
                "Probe-Passw0rd!x", Map.of("email", "hal@example.com"), Map.of(), Map.of()));
        assertEquals("CodeDeliveryFailureException", signUp.getErrorCode());
        assertEquals("Caller needs to enable the configured KMS Key", signUp.getMessage());
        assertEquals("UNCONFIRMED", service.adminGetUser(pool.getId(), "hal@example.com").getUserStatus());

        AwsException signIn = assertThrows(AwsException.class, () -> service.initiateAuth(clientId, "USER_AUTH",
                Map.of("USERNAME", "gil@example.com", "PREFERRED_CHALLENGE", "EMAIL_OTP")));
        assertEquals("CodeDeliveryFailureException", signIn.getErrorCode());
        AwsException forgot = assertThrows(AwsException.class, () -> service.forgotPassword(clientId, "gil@example.com"));
        assertEquals("Caller needs to enable the configured KMS Key", forgot.getMessage());
    }

    /** Measured: a key pending deletion fails the request with the encryption as the reason. */
    @Test
    void aKeyPendingDeletionFailsTheRequest() {
        kmsService.scheduleKeyDeletion(keyArn, 7, REGION);

        AwsException signUp = assertThrows(AwsException.class, () -> service.signUp(clientId, "ida@example.com",
                "Probe-Passw0rd!x", Map.of("email", "ida@example.com"), Map.of(), Map.of()));
        assertEquals("CodeDeliveryFailureException", signUp.getErrorCode());
        assertEquals("Unable to encrypt CustomEmailSender/CustomSMSSender code using the configured KMS Key",
                signUp.getMessage());
    }

    /** Measured: a function Cognito cannot invoke, a deleted one included, fails as AccessDenied. */
    @Test
    void aFunctionThatIsGoneFailsTheRequest() {
        when(lambdaService.invoke(anyString(), anyString(), any(byte[].class), eq(InvocationType.Event)))
                .thenThrow(new AwsException("ResourceNotFoundException", "Function not found: " + SENDER_ARN, 404));

        AwsException signUp = assertThrows(AwsException.class, () -> service.signUp(clientId, "jon@example.com",
                "Probe-Passw0rd!x", Map.of("email", "jon@example.com"), Map.of(), Map.of()));
        assertEquals("UnexpectedLambdaException", signUp.getErrorCode());
        assertEquals("CustomEmailSender invocation failed due to error AccessDeniedException.", signUp.getMessage());
    }

    /** Measured: an email change whose code cannot be sent is applied, and the request fails. */
    @Test
    void anAttributeUpdateIsAppliedWhenItsCodeCannotBeSent() throws Exception {
        service.signUp(clientId, "kim@example.com", "Probe-Passw0rd!x", Map.of("email", "kim@example.com"), Map.of(), Map.of());
        service.confirmSignUp(clientId, "kim@example.com", open(lastEvent().path("request").path("code").asText()));
        @SuppressWarnings("unchecked")
        Map<String, Object> tokens = (Map<String, Object>) service.initiateAuth(clientId, "USER_PASSWORD_AUTH",
                Map.of("USERNAME", "kim@example.com", "PASSWORD", "Probe-Passw0rd!x")).get("AuthenticationResult");
        kmsService.disableKey(keyArn, REGION);

        AwsException update = assertThrows(AwsException.class, () -> service.updateUserAttributes(
                (String) tokens.get("AccessToken"), new HashMap<>(Map.of("email", "kim2@example.com"))));
        assertEquals("CodeDeliveryFailureException", update.getErrorCode());
        CognitoUser user = service.adminGetUser(pool.getId(), "kim2@example.com");
        assertEquals("kim2@example.com", user.getAttributes().get("email"));
        assertEquals("false", user.getAttributes().get("email_verified"));
    }

    private JsonNode lastEvent() throws Exception {
        ArgumentCaptor<byte[]> payload = ArgumentCaptor.forClass(byte[].class);
        verify(lambdaService, atLeastOnce()).invoke(eq(REGION), eq(SENDER_ARN), payload.capture(),
                eq(InvocationType.Event));
        verify(lambdaService, never()).invoke(anyString(), eq(SENDER_ARN), any(byte[].class),
                eq(InvocationType.RequestResponse));
        List<byte[]> payloads = payload.getAllValues();
        return MAPPER.readTree(payloads.get(payloads.size() - 1));
    }

    /** Opens a code as the trigger does: the Encryption SDK, with KMS opening the data key. */
    private String open(String code) {
        byte[] message = Base64.getDecoder().decode(code);
        return new String(CRYPTO.decryptData(new FlociKmsKey(), message).getResult(), StandardCharsets.UTF_8);
    }

    private final class FlociKmsKey extends MasterKey<FlociKmsKey> {

        @Override
        public String getProviderId() {
            return "aws-kms";
        }

        @Override
        public String getKeyId() {
            return keyArn;
        }

        @Override
        public DataKey<FlociKmsKey> generateDataKey(CryptoAlgorithm algorithm, Map<String, String> context) {
            throw new UnsupportedOperationException("only opens");
        }

        @Override
        public DataKey<FlociKmsKey> encryptDataKey(CryptoAlgorithm algorithm, Map<String, String> context,
                                                   DataKey<?> dataKey) {
            throw new UnsupportedOperationException("only opens");
        }

        @Override
        public DataKey<FlociKmsKey> decryptDataKey(CryptoAlgorithm algorithm,
                                                   Collection<? extends EncryptedDataKey> encryptedDataKeys,
                                                   Map<String, String> context) {
            for (EncryptedDataKey key : encryptedDataKeys) {
                if ("aws-kms".equals(key.getProviderId())
                        && keyArn.equals(new String(key.getProviderInformation(), StandardCharsets.UTF_8))) {
                    byte[] plaintext = kmsService.decrypt(key.getEncryptedDataKey(), context, REGION);
                    return new DataKey<>(new SecretKeySpec(plaintext, algorithm.getDataKeyAlgo()),
                            key.getEncryptedDataKey(), key.getProviderInformation(), this);
                }
            }
            return null;
        }
    }
}
