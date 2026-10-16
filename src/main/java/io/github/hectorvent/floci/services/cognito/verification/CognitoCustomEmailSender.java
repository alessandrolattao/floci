package io.github.hectorvent.floci.services.cognito.verification;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.hectorvent.floci.core.common.AwsArnUtils;
import io.github.hectorvent.floci.services.cognito.model.CognitoUser;
import io.github.hectorvent.floci.services.cognito.model.UserPool;
import io.github.hectorvent.floci.services.kms.KmsService;
import io.github.hectorvent.floci.services.lambda.LambdaService;
import io.github.hectorvent.floci.services.lambda.model.InvocationType;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * The Custom Email Sender trigger. A pool whose {@code LambdaConfig} names one hands it every email
 * Cognito would otherwise send itself, with the code or temporary password sealed under the pool's
 * {@code KMSKeyID} by the AWS Encryption SDK, and sends nothing on its own.
 *
 * <p>Measured on AWS (eu-west-1, 2026-10-16): the function is invoked asynchronously, so the API
 * call that asked for the message neither waits for it nor fails with it, and a failed invocation
 * is retried as any asynchronous one. The event is the same for every source:
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
        lambdaService.invoke(region, functionArn, payload, InvocationType.Event);
        LOG.debugv("Handed {0} for user {1} in pool {2} to {3}",
                source.triggerSource(), userName, pool.getId(), functionArn);
    }

    private String seal(UserPool pool, String keyId, String region, String code) {
        byte[] message = EncryptionSdkMessage.seal(code.getBytes(StandardCharsets.UTF_8),
                Map.of("userpool-id", pool.getId()), context -> {
                    Map<String, Object> dataKey = kmsService.generateDataKey(keyId, "AES_256", null, context, region);
                    return new EncryptionSdkMessage.DataKey((byte[]) dataKey.get("Plaintext"), "aws-kms",
                            (String) dataKey.get("KeyId"), (byte[]) dataKey.get("CiphertextBlob"));
                });
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
