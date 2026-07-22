package com.company.personnelarchive.person;

import com.company.personnelarchive.configuration.ArchiveProperties;
import org.springframework.stereotype.Component;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;

@Component
/**
 * 身份证敏感字段保护器。
 * HMAC 提供稳定的等值检索/唯一键，AES-GCM 提供需要导出时的可逆机密性与完整性保护。
 */
public class IdentityProtection {
    private final byte[] hmacKey;
    private final byte[] encryptionKey;
    private final SecureRandom random = new SecureRandom();

    public IdentityProtection(ArchiveProperties properties) {
        this.hmacKey = required(properties.security().identityHmacKey(), "PA_IDENTITY_HMAC_KEY").getBytes(StandardCharsets.UTF_8);
        this.encryptionKey = Base64.getDecoder().decode(required(properties.security().identityEncryptionKey(), "PA_IDENTITY_ENCRYPTION_KEY"));
        if (encryptionKey.length != 32) throw new IllegalStateException("PA_IDENTITY_ENCRYPTION_KEY must decode to 32 bytes");
    }

    public byte[] hmac(String normalizedIdentityCard) {
        // 调用方必须先规范化；同一规范化证件生成稳定键，但无法从数据库键反推出明文。
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacKey, "HmacSHA256"));
            return mac.doFinal(normalizedIdentityCard.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot protect identity", exception);
        }
    }

    public byte[] encrypt(String normalizedIdentityCard) {
        try {
            // 每条记录使用新的 96-bit nonce；相同身份证的密文也不会相同。
            byte[] nonce = new byte[12];
            random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
            byte[] cipherText = cipher.doFinal(normalizedIdentityCard.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + cipherText.length).put(nonce).put(cipherText).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Cannot protect identity", exception);
        }
    }

    public String decrypt(byte[] encrypted) {
        try {
            // 存储格式固定为 nonce || ciphertext+tag，解密失败一律视为受保护数据不可读。
            ByteBuffer buffer = ByteBuffer.wrap(encrypted);
            byte[] nonce = new byte[12];
            buffer.get(nonce);
            byte[] cipherText = new byte[buffer.remaining()];
            buffer.get(cipherText);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"), new GCMParameterSpec(128, nonce));
            return new String(cipher.doFinal(cipherText), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | RuntimeException exception) {
            throw new IllegalStateException("Cannot read protected identity", exception);
        }
    }

    private static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
        return value;
    }
}
