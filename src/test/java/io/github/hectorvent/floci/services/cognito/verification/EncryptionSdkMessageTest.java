package io.github.hectorvent.floci.services.cognito.verification;

import com.amazonaws.encryptionsdk.AwsCrypto;
import com.amazonaws.encryptionsdk.CommitmentPolicy;
import com.amazonaws.encryptionsdk.CryptoAlgorithm;
import com.amazonaws.encryptionsdk.CryptoResult;
import com.amazonaws.encryptionsdk.DataKey;
import com.amazonaws.encryptionsdk.EncryptedDataKey;
import com.amazonaws.encryptionsdk.MasterKey;
import com.amazonaws.encryptionsdk.model.CiphertextHeaders;
import com.amazonaws.encryptionsdk.model.ContentType;
import org.junit.jupiter.api.Test;

import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The messages are opened by the AWS Encryption SDK itself, under the commitment policy AWS
 * documents for custom sender triggers, with a master key standing in for the KMS keyring. The
 * layout checks are what Cognito's own messages showed on AWS (eu-west-1, 2026-10-16).
 */
class EncryptionSdkMessageTest {

    private static final String KEY_ARN =
            "arn:aws:kms:us-east-1:000000000000:key/1a93890f-9739-4544-9a62-d5eb8adc1d1a";
    private static final AwsCrypto CRYPTO = AwsCrypto.builder()
            .withCommitmentPolicy(CommitmentPolicy.RequireEncryptAllowDecrypt)
            .build();

    @Test
    void aCodeOpensWithTheEncryptionSdkAsCognitoSealsIt() {
        StandInKmsKey kms = new StandInKmsKey();

        byte[] message = EncryptionSdkMessage.seal("06988020".getBytes(StandardCharsets.UTF_8),
                Map.of("userpool-id", "us-east-1_abc"), kms::generate);
        CryptoResult<byte[], StandInKmsKey> opened = CRYPTO.decryptData(kms, message);

        assertEquals("06988020", new String(opened.getResult(), StandardCharsets.UTF_8));
        assertEquals(CryptoAlgorithm.ALG_AES_256_GCM_IV12_TAG16_HKDF_SHA384_ECDSA_P384, opened.getCryptoAlgorithm());
        assertEquals(Set.of("userpool-id", EncryptionSdkMessage.PUBLIC_KEY_CONTEXT_KEY),
                opened.getEncryptionContext().keySet());
        assertEquals("us-east-1_abc", opened.getEncryptionContext().get("userpool-id"));
        assertEquals(opened.getEncryptionContext(), kms.generatedUnder,
                "the data key is generated under the whole context, the signing key's included");
        assertEquals(List.of(KEY_ARN), opened.getMasterKeyIds());
        CiphertextHeaders headers = opened.getHeaders();
        assertEquals(1, headers.getVersion());
        assertEquals(ContentType.FRAME, headers.getContentType());
        assertEquals(4096, headers.getFrameLength());
        assertEquals(1, headers.getEncryptedKeyBlobCount());
        assertArrayEquals(new byte[12], headers.getHeaderNonce());
    }

    @Test
    void theSignatureIsAlwaysOneLengthSoAMessageLengthDependsOnItsPlaintextAlone() {
        StandInKmsKey kms = new StandInKmsKey();
        Set<Integer> lengths = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            byte[] message = EncryptionSdkMessage.seal("465068".getBytes(StandardCharsets.UTF_8),
                    Map.of("userpool-id", "us-east-1_abc"), kms::generate);
            lengths.add(message.length);
            int signatureLength = ByteBuffer.wrap(message, message.length - 103 - 2, 2).getShort();
            assertEquals(103, signatureLength);
        }
        assertEquals(1, lengths.size(), "every message of one plaintext length has one length");
    }

    @Test
    void aPlaintextLongerThanAFrameSpansSeveralFrames() {
        StandInKmsKey kms = new StandInKmsKey();
        byte[] plaintext = new byte[4096 * 2 + 17];
        new SecureRandom().nextBytes(plaintext);

        byte[] message = EncryptionSdkMessage.seal(plaintext, Map.of("userpool-id", "us-east-1_abc"), kms::generate);

        assertArrayEquals(plaintext, CRYPTO.decryptData(kms, message).getResult());
    }

    @Test
    void anEmptyPlaintextOpensToNothing() {
        StandInKmsKey kms = new StandInKmsKey();

        byte[] message = EncryptionSdkMessage.seal(new byte[0], Map.of("userpool-id", "us-east-1_abc"), kms::generate);

        assertEquals(0, CRYPTO.decryptData(kms, message).getResult().length);
    }

    @Test
    void anAlteredMessageDoesNotOpen() {
        StandInKmsKey kms = new StandInKmsKey();
        byte[] message = EncryptionSdkMessage.seal("06988020".getBytes(StandardCharsets.UTF_8),
                Map.of("userpool-id", "us-east-1_abc"), kms::generate);
        message[message.length - 120] ^= 0x01;

        boolean refused;
        try {
            CRYPTO.decryptData(kms, message);
            refused = false;
        } catch (RuntimeException expected) {
            // The SDK refuses a frame whose tag or signature no longer matches.
            refused = true;
        }
        assertTrue(refused);
    }

    /**
     * Hands out one data key, encrypted as an opaque blob, and opens it again only for the
     * provider, key ARN, blob and encryption context it was generated with, as KMS would.
     */
    private static final class StandInKmsKey extends MasterKey<StandInKmsKey> {

        private final byte[] dataKey = new byte[32];
        private final byte[] encrypted = new byte[184];
        private Map<String, String> generatedUnder;

        StandInKmsKey() {
            SecureRandom random = new SecureRandom();
            random.nextBytes(dataKey);
            random.nextBytes(encrypted);
        }

        EncryptionSdkMessage.DataKey generate(Map<String, String> encryptionContext) {
            generatedUnder = Map.copyOf(encryptionContext);
            return new EncryptionSdkMessage.DataKey(dataKey.clone(), "aws-kms", KEY_ARN, encrypted.clone());
        }

        @Override
        public String getProviderId() {
            return "aws-kms";
        }

        @Override
        public String getKeyId() {
            return KEY_ARN;
        }

        @Override
        public DataKey<StandInKmsKey> generateDataKey(CryptoAlgorithm algorithm, Map<String, String> context) {
            throw new UnsupportedOperationException("only opens");
        }

        @Override
        public DataKey<StandInKmsKey> encryptDataKey(CryptoAlgorithm algorithm, Map<String, String> context,
                                                     DataKey<?> dataKey) {
            throw new UnsupportedOperationException("only opens");
        }

        @Override
        public DataKey<StandInKmsKey> decryptDataKey(CryptoAlgorithm algorithm,
                                                     Collection<? extends EncryptedDataKey> encryptedDataKeys,
                                                     Map<String, String> context) {
            for (EncryptedDataKey key : encryptedDataKeys) {
                if ("aws-kms".equals(key.getProviderId())
                        && Arrays.equals(KEY_ARN.getBytes(StandardCharsets.UTF_8), key.getProviderInformation())
                        && Arrays.equals(encrypted, key.getEncryptedDataKey())
                        && context.equals(generatedUnder)) {
                    return new DataKey<>(new SecretKeySpec(dataKey, algorithm.getDataKeyAlgo()), encrypted,
                            key.getProviderInformation(), this);
                }
            }
            return null;
        }
    }
}
