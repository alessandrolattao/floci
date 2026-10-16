package io.github.hectorvent.floci.services.cognito.verification;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/**
 * An AWS Encryption SDK message, written the way Cognito seals the code it hands a custom sender
 * trigger. Measured on AWS (eu-west-1, 2026-10-16): message format version 1, algorithm suite
 * 0x0378 (AES_256_GCM_IV12_TAG16_HKDF_SHA384_ECDSA_P384, signed and without key commitment), one
 * data key from AWS KMS, framed content with 4096-byte frames, a header IV of zeros, frame IVs
 * that are the sequence number, and a 103-byte signature. Every Encryption SDK opens it with an
 * AWS KMS keyring under the REQUIRE_ENCRYPT_ALLOW_DECRYPT commitment policy, the policy AWS
 * documents for these triggers.
 *
 * <p>The layout and the derivations are the Encryption SDK specification's: the message format,
 * the body AAD, and the algorithm suite's HKDF-SHA384 key derivation and ECDSA P-384 signature.</p>
 */
public final class EncryptionSdkMessage {

    /** The encryption context key under which the message carries its signing key. */
    public static final String PUBLIC_KEY_CONTEXT_KEY = "aws-crypto-public-key";

    private static final byte VERSION = 0x01;
    private static final byte TYPE_CUSTOMER_AUTHENTICATED = (byte) 0x80;
    private static final short ALGORITHM_ID = 0x0378;
    private static final byte CONTENT_TYPE_FRAMED = 0x02;
    private static final int MESSAGE_ID_LENGTH = 16;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;
    private static final int FRAME_LENGTH = 4096;
    private static final int DATA_KEY_LENGTH = 32;
    private static final int FINAL_FRAME = 0xFFFFFFFF;
    // An ECDSA signature's DER length varies with its two integers. The Encryption SDK keeps s in
    // its low form and signs again until the encoding is the longest that leaves, 103 bytes for
    // P-384, so that a message's length depends on its plaintext alone.
    private static final int SIGNATURE_LENGTH = 103;
    private static final byte[] FRAME_AAD = "AWSKMSEncryptionClient Frame".getBytes(StandardCharsets.UTF_8);
    private static final byte[] FINAL_FRAME_AAD = "AWSKMSEncryptionClient Final Frame".getBytes(StandardCharsets.UTF_8);
    private static final BigInteger P384_ORDER = new BigInteger(
            "ffffffffffffffffffffffffffffffffffffffffffffffffc7634d81f4372ddf581a0db248b0a77aecec196accc52973", 16);
    private static final int P384_FIELD_BYTES = 48;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The data key the message is sealed with, and the encrypted copy a keyring opens it from. */
    public record DataKey(byte[] plaintext, String providerId, String providerInfo, byte[] encrypted) {
    }

    /** Generates the data key under the message's whole encryption context, its signing key included. */
    @FunctionalInterface
    public interface DataKeySource {
        DataKey generate(Map<String, String> encryptionContext);
    }

    private EncryptionSdkMessage() {
    }

    public static byte[] seal(byte[] plaintext, Map<String, String> encryptionContext, DataKeySource dataKeys) {
        try {
            KeyPair signingKey = signingKeyPair();
            Map<String, String> context = new TreeMap<>(Comparator.comparing(
                    (String key) -> key.getBytes(StandardCharsets.UTF_8), Arrays::compareUnsigned));
            context.putAll(encryptionContext);
            context.put(PUBLIC_KEY_CONTEXT_KEY,
                    Base64.getEncoder().encodeToString(compressedPoint((ECPublicKey) signingKey.getPublic())));
            DataKey dataKey = dataKeys.generate(Collections.unmodifiableMap(context));
            if (dataKey.plaintext().length != DATA_KEY_LENGTH) {
                throw new IllegalStateException("The algorithm suite takes a 32-byte data key");
            }

            byte[] messageId = new byte[MESSAGE_ID_LENGTH];
            RANDOM.nextBytes(messageId);
            SecretKeySpec key = deriveKey(dataKey.plaintext(), messageId);

            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(bytes);
            writeHeaderBody(out, messageId, context, dataKey);
            byte[] headerIv = new byte[IV_LENGTH];
            byte[] headerTag = gcm(key, headerIv, bytes.toByteArray(), new byte[0]);
            out.write(headerIv);
            out.write(headerTag);
            writeBody(out, key, messageId, plaintext);

            byte[] signature = sign(signingKey.getPrivate(), bytes.toByteArray());
            out.writeShort(signature.length);
            out.write(signature);
            return bytes.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not seal an Encryption SDK message", e);
        }
    }

    private static void writeHeaderBody(DataOutputStream out, byte[] messageId, Map<String, String> context,
                                        DataKey dataKey) throws IOException {
        out.writeByte(VERSION);
        out.writeByte(TYPE_CUSTOMER_AUTHENTICATED);
        out.writeShort(ALGORITHM_ID);
        out.write(messageId);
        byte[] aad = serializeContext(context);
        out.writeShort(aad.length);
        out.write(aad);
        out.writeShort(1);
        writeField(out, dataKey.providerId().getBytes(StandardCharsets.UTF_8));
        writeField(out, dataKey.providerInfo().getBytes(StandardCharsets.UTF_8));
        writeField(out, dataKey.encrypted());
        out.writeByte(CONTENT_TYPE_FRAMED);
        out.writeInt(0);
        out.writeByte(IV_LENGTH);
        out.writeInt(FRAME_LENGTH);
    }

    private static byte[] serializeContext(Map<String, String> context) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bytes);
        out.writeShort(context.size());
        for (Map.Entry<String, String> entry : context.entrySet()) {
            writeField(out, entry.getKey().getBytes(StandardCharsets.UTF_8));
            writeField(out, entry.getValue().getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }

    private static void writeField(DataOutputStream out, byte[] value) throws IOException {
        out.writeShort(value.length);
        out.write(value);
    }

    /**
     * Full frames while more than one frame's worth is left, then the final frame with the rest,
     * which is empty only for an empty plaintext.
     */
    private static void writeBody(DataOutputStream out, SecretKeySpec key, byte[] messageId, byte[] plaintext)
            throws IOException, GeneralSecurityException {
        int offset = 0;
        int sequence = 1;
        while (plaintext.length - offset > FRAME_LENGTH) {
            byte[] content = Arrays.copyOfRange(plaintext, offset, offset + FRAME_LENGTH);
            byte[] iv = frameIv(sequence);
            byte[] sealed = gcm(key, iv, frameAad(messageId, FRAME_AAD, sequence, content.length), content);
            out.writeInt(sequence);
            out.write(iv);
            out.write(sealed);
            offset += FRAME_LENGTH;
            sequence++;
        }
        byte[] content = Arrays.copyOfRange(plaintext, offset, plaintext.length);
        byte[] iv = frameIv(sequence);
        byte[] sealed = gcm(key, iv, frameAad(messageId, FINAL_FRAME_AAD, sequence, content.length), content);
        out.writeInt(FINAL_FRAME);
        out.writeInt(sequence);
        out.write(iv);
        out.writeInt(content.length);
        out.write(sealed);
    }

    private static byte[] frameIv(int sequence) {
        return ByteBuffer.allocate(IV_LENGTH).putInt(IV_LENGTH - Integer.BYTES, sequence).array();
    }

    private static byte[] frameAad(byte[] messageId, byte[] contentString, int sequence, long length) {
        return ByteBuffer.allocate(messageId.length + contentString.length + Integer.BYTES + Long.BYTES)
                .put(messageId).put(contentString).putInt(sequence).putLong(length).array();
    }

    /** AES-GCM with a 16-byte tag; the result is the ciphertext followed by the tag. */
    private static byte[] gcm(SecretKeySpec key, byte[] iv, byte[] aad, byte[] plaintext)
            throws GeneralSecurityException {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
        cipher.updateAAD(aad);
        return cipher.doFinal(plaintext);
    }

    /** HKDF-SHA384 with no salt, the info being the algorithm id followed by the message id. */
    private static SecretKeySpec deriveKey(byte[] dataKey, byte[] messageId) throws GeneralSecurityException {
        Mac extract = Mac.getInstance("HmacSHA384");
        extract.init(new SecretKeySpec(new byte[extract.getMacLength()], "HmacSHA384"));
        byte[] pseudoRandomKey = extract.doFinal(dataKey);
        Mac expand = Mac.getInstance("HmacSHA384");
        expand.init(new SecretKeySpec(pseudoRandomKey, "HmacSHA384"));
        expand.update(ByteBuffer.allocate(Short.BYTES).putShort(ALGORITHM_ID).array());
        expand.update(messageId);
        expand.update((byte) 0x01);
        return new SecretKeySpec(Arrays.copyOf(expand.doFinal(), DATA_KEY_LENGTH), "AES");
    }

    private static KeyPair signingKeyPair() throws GeneralSecurityException {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp384r1"), RANDOM);
        return generator.generateKeyPair();
    }

    /** The public key as a compressed point: 0x02 or 0x03 for the parity of y, then x in 48 bytes. */
    private static byte[] compressedPoint(ECPublicKey publicKey) {
        byte[] point = new byte[1 + P384_FIELD_BYTES];
        point[0] = (byte) (publicKey.getW().getAffineY().testBit(0) ? 0x03 : 0x02);
        byte[] x = unsigned(publicKey.getW().getAffineX(), P384_FIELD_BYTES);
        System.arraycopy(x, 0, point, 1, P384_FIELD_BYTES);
        return point;
    }

    private static byte[] sign(PrivateKey privateKey, byte[] message) throws GeneralSecurityException {
        if (!(privateKey instanceof ECPrivateKey)) {
            throw new IllegalStateException("The algorithm suite signs with ECDSA P-384");
        }
        while (true) {
            Signature signer = Signature.getInstance("SHA384withECDSA");
            signer.initSign(privateKey, RANDOM);
            signer.update(message);
            BigInteger[] rs = decodeDer(signer.sign());
            BigInteger s = rs[1].compareTo(P384_ORDER.shiftRight(1)) > 0 ? P384_ORDER.subtract(rs[1]) : rs[1];
            byte[] encoded = encodeDer(rs[0], s);
            if (encoded.length == SIGNATURE_LENGTH) {
                return encoded;
            }
        }
    }

    private static BigInteger[] decodeDer(byte[] der) {
        ByteBuffer in = ByteBuffer.wrap(der);
        if (in.get() != 0x30) {
            throw new IllegalStateException("An ECDSA signature is a DER sequence");
        }
        derLength(in);
        return new BigInteger[] {derInteger(in), derInteger(in)};
    }

    private static int derLength(ByteBuffer in) {
        int length = in.get() & 0xFF;
        if (length < 0x80) {
            return length;
        }
        int value = 0;
        for (int i = 0; i < (length & 0x7F); i++) {
            value = (value << 8) | (in.get() & 0xFF);
        }
        return value;
    }

    private static BigInteger derInteger(ByteBuffer in) {
        if (in.get() != 0x02) {
            throw new IllegalStateException("An ECDSA signature holds two DER integers");
        }
        byte[] value = new byte[derLength(in)];
        in.get(value);
        return new BigInteger(1, value);
    }

    private static byte[] encodeDer(BigInteger r, BigInteger s) {
        byte[] rBytes = r.toByteArray();
        byte[] sBytes = s.toByteArray();
        int contentLength = 2 + rBytes.length + 2 + sBytes.length;
        ByteBuffer out = ByteBuffer.allocate(2 + contentLength);
        out.put((byte) 0x30).put((byte) contentLength);
        out.put((byte) 0x02).put((byte) rBytes.length).put(rBytes);
        out.put((byte) 0x02).put((byte) sBytes.length).put(sBytes);
        return out.array();
    }

    private static byte[] unsigned(BigInteger value, int length) {
        byte[] bytes = value.toByteArray();
        if (bytes.length == length) {
            return bytes;
        }
        byte[] fixed = new byte[length];
        int copy = Math.min(bytes.length, length);
        System.arraycopy(bytes, bytes.length - copy, fixed, length - copy, copy);
        return fixed;
    }
}
