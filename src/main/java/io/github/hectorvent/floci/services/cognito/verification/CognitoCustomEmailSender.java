package io.github.hectorvent.floci.services.cognito.verification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.core.common.AwsException;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The Custom Email Sender trigger. A pool whose {@code LambdaConfig} names one hands it every email
 * Cognito would otherwise send itself, with the code or temporary password sealed under the pool's
 * {@code KMSKeyID} by the AWS Encryption SDK, and sends nothing on its own.
 *
 * <p>Measured on AWS (eu-west-1, 2026-10-16): the function is invoked asynchronously, so the API
 * call that asked for the message neither waits for it nor fails with what it does, and a failed
 * invocation is retried as any asynchronous one. Only a function Cognito cannot invoke, or a key it
 * cannot seal the code under, fails the call ({@link CustomEmailSenderException}). The event is the same for every source:
 * {@code version} "1", {@code triggerSource} {@code CustomEmailSender_<source>}, {@code region},
 * {@code userPoolId}, {@code userName}, {@code callerContext} with the app client, and a
 * {@code request} of type {@code customEmailSenderRequestV1} with the sealed {@code code}, a
 * {@code null} {@code clientMetadata} and the user's attributes, {@code cognito:user_status}
 * among them. The data key is generated under the encryption context {@code userpool-id}.</p>
 */
public final class CognitoCustomEmailSender {

    private static final Logger LOG = Logger.getLogger(CognitoCustomEmailSender.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The {@code callerContext.clientId} AWS sends when no app client asked: AdminCreateUser's. */
    public static final String CLIENT_ID_NOT_APPLICABLE = "CLIENT_ID_NOT_APPLICABLE";

    /** Why Cognito sends the message, as the trigger names it. */
    public enum Source {
        SIGN_UP("SignUp"),
        RESEND_CODE("ResendCode"),
        FORGOT_PASSWORD("ForgotPassword"),
        UPDATE_USER_ATTRIBUTE("UpdateUserAttribute"),
        VERIFY_USER_ATTRIBUTE("VerifyUserAttribute"),
        ADMIN_CREATE_USER("AdminCreateUser"),
        AUTHENTICATION("Authentication");

        private final String triggerSource;

        Source(String name) {
            this.triggerSource = "CustomEmailSender_" + name;
        }

        public String triggerSource() {
            return triggerSource;
        }
    }

    private final LambdaService lambdaService;
    private final KmsService kmsService;
    private final String defaultRegion;

    public CognitoCustomEmailSender(LambdaService lambdaService, KmsService kmsService, String defaultRegion) {
        this.lambdaService = lambdaService;
        this.kmsService = kmsService;
        this.defaultRegion = defaultRegion;
    }

    /** Whether the pool hands its email to a Custom Email Sender. */
    public static boolean isConfigured(UserPool pool) {
        return functionArn(pool) != null;
    }

    /**
     * Hands one email to the pool's Custom Email Sender. {@code userName} is the name the event
     * reports, the user's own except for AdminCreateUser, where AWS reports the one the request
     * gave; {@code code} is {@code null} for a message that carries none, such as the invitation of
     * a user created without a password.
     */
    public void send(UserPool pool, CognitoUser user, String userName, Source source, String clientId,
                     String code) {
        String functionArn = functionArn(pool);
        String keyId = stringOrNull(pool.getLambdaConfig().get("KMSKeyID"));
        if (functionArn == null) {
            throw new IllegalStateException("User pool " + pool.getId() + " has no Custom Email Sender");
        }
        if (keyId == null) {
            LOG.warnv("User pool {0} names a Custom Email Sender without a KMSKeyID; {1} not sent",
                    pool.getId(), source.triggerSource());
            return;
        }
        String region = AwsArnUtils.regionOrDefault(pool.getArn(), defaultRegion);

        Map<String, Object> callerContext = new LinkedHashMap<>();
        callerContext.put("awsSdkVersion", "floci");
        callerContext.put("clientId", clientId);
        Map<String, String> userAttributes = new LinkedHashMap<>(user.getAttributes());
        userAttributes.put("cognito:user_status", user.getUserStatus());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("type", "customEmailSenderRequestV1");
        request.put("code", code == null ? null : seal(pool, keyId, region, code));
        request.put("clientMetadata", null);
        request.put("userAttributes", userAttributes);
        Map<String, Object> event = new LinkedHashMap<>();
        event.put("version", "1");
        event.put("triggerSource", source.triggerSource());
        event.put("region", region);
        event.put("userPoolId", pool.getId());
        event.put("userName", userName);
        event.put("callerContext", callerContext);
        event.put("request", request);

        byte[] payload;
        try {
            payload = MAPPER.writeValueAsBytes(event);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not write the Custom Email Sender event", e);
        }
        try {
            lambdaService.invoke(region, functionArn, payload, InvocationType.Event);
        } catch (AwsException e) {
            // AWS checks the function's resource policy before anything else, so a function that
            // is gone fails as one Cognito may not invoke.
            String reason = "ResourceNotFoundException".equals(e.getErrorCode())
                    ? "AccessDeniedException" : e.getErrorCode();
            throw new CustomEmailSenderException("UnexpectedLambdaException",
                    "CustomEmailSender invocation failed due to error " + reason + ".");
        }
        LOG.debugv("Handed {0} for user {1} in pool {2} to {3}",
                source.triggerSource(), userName, pool.getId(), functionArn);
    }

    /**
     * Grants Cognito the use of the pool's {@code KMSKeyID}, as AWS does when a pool first names a
     * key (measured): the grant {@code cognito-encrypt-grant} to and retirable by
     * {@code cognito-idp.<region>.amazonaws.com}, for Encrypt and GenerateDataKey under the pool's
     * {@code userpool-id}. A key the grant cannot be created on refuses the pool's request with
     * the KMS error as the reason.
     */
    public void grantKeyUse(UserPool pool) {
        String keyId = stringOrNull(pool.getLambdaConfig() == null ? null : pool.getLambdaConfig().get("KMSKeyID"));
        if (keyId == null) {
            return;
        }
        String region = AwsArnUtils.regionOrDefault(pool.getArn(), defaultRegion);
        String cognito = "cognito-idp." + region + ".amazonaws.com";
        try {
            kmsService.createGrant(keyId, cognito, List.of("Encrypt", "GenerateDataKey"), cognito,
                    "cognito-encrypt-grant", Map.of("EncryptionContextSubset", Map.of("userpool-id", pool.getId())),
                    AwsArnUtils.regionOrDefault(keyId, region));
        } catch (AwsException e) {
            throw new AwsException("InvalidParameterException",
                    "Encountered error when CreateGrant was attempted on the input KMS key - " + e.getMessage(), 400);
        }
    }

    /**
     * Refuses a {@code LambdaConfig} with a Custom Email or SMS Sender and no {@code KMSKeyID}, as
     * CreateUserPool and UpdateUserPool do on AWS (measured).
     */
    public static void requireKeyForSenders(Map<String, Object> lambdaConfig) {
        if (lambdaConfig == null || stringOrNull(lambdaConfig.get("KMSKeyID")) != null) {
            return;
        }
        if (lambdaConfig.get("CustomEmailSender") != null || lambdaConfig.get("CustomSMSSender") != null) {
            throw new AwsException("InvalidParameterException",
                    "KMSKeyId parameter is required when Custom SMS/Email Lambda trigger is used.", 400);
        }
    }

    /**
     * Seals {@code code} under the pool's key. A key Cognito cannot use fails the request that asked
     * for the message with {@code CodeDeliveryFailureException} (measured): a disabled key names
     * itself, any other state the encryption.
     */
    private String seal(UserPool pool, String keyId, String region, String code) {
        byte[] message;
        try {
            message = EncryptionSdkMessage.seal(code.getBytes(StandardCharsets.UTF_8),
                    Map.of("userpool-id", pool.getId()), context -> {
                        Map<String, Object> dataKey = kmsService.generateDataKey(keyId, "AES_256", null, context, region);
                        return new EncryptionSdkMessage.DataKey((byte[]) dataKey.get("Plaintext"), "aws-kms",
                                (String) dataKey.get("KeyId"), (byte[]) dataKey.get("CiphertextBlob"));
                    });
        } catch (AwsException e) {
            throw new CustomEmailSenderException("CodeDeliveryFailureException",
                    "DisabledException".equals(e.getErrorCode())
                            ? "Caller needs to enable the configured KMS Key"
                            : "Unable to encrypt CustomEmailSender/CustomSMSSender code using the configured KMS Key");
        }
        return Base64.getEncoder().encodeToString(message);
    }

    private static String functionArn(UserPool pool) {
        Map<String, Object> config = pool.getLambdaConfig();
        if (config == null || !(config.get("CustomEmailSender") instanceof Map<?, ?> sender)) {
            return null;
        }
        return stringOrNull(sender.get("LambdaArn"));
    }

    private static String stringOrNull(Object value) {
        return value instanceof String s && !s.isBlank() ? s : null;
    }
}
