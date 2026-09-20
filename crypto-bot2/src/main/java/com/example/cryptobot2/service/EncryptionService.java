package com.example.cryptobot2.service;

import com.example.cryptobot2.exception.CryptoBotException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.spec.KeySpec;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * AES-256-GCM による暗号化・復号サービス。
 *
 * <p>鍵導出: PBKDF2WithHmacSHA256（iterations=310000）
 * <p>フォーマット: {@code ENC(<Base64(IV[12bytes] + ciphertext)>)}
 *
 * <p>マスターパスワードは環境変数 {@code CRYPTO_MASTER_KEY} から取得。
 */
@Slf4j
@Service
public class EncryptionService {

  private static final String ALGORITHM = "AES/GCM/NoPadding";
  private static final String KEY_DERIVATION = "PBKDF2WithHmacSHA256";
  private static final int ITERATIONS = 310_000;
  private static final int KEY_LENGTH_BITS = 256;
  private static final int GCM_IV_LENGTH = 12;
  private static final int GCM_TAG_LENGTH_BITS = 128;
  private static final String ENC_PREFIX = "ENC(";
  private static final String ENC_SUFFIX = ")";

  /** PBKDF2 用固定ソルト（アプリ名を含める） */
  private static final byte[] FIXED_SALT =
      "crypto-bot2-kline-salt-v1".getBytes(StandardCharsets.UTF_8);

  private final SecretKey secretKey;

  public EncryptionService(@Value("${CRYPTO_MASTER_KEY:}") String masterKey) {
    if (masterKey == null || masterKey.isBlank()) {
      log.warn("環境変数 CRYPTO_MASTER_KEY が未設定です。暗号化済み値の復号はできません。");
      this.secretKey = null;
    } else {
      this.secretKey = deriveKey(masterKey);
      log.info("EncryptionService: 鍵の導出が完了しました。");
    }
  }

  /**
   * 平文を暗号化して {@code ENC(...)} 形式の文字列を返す。
   *
   * @param plainText 平文
   * @return 暗号化済み文字列
   */
  public String encrypt(String plainText) {
    requireKey();
    try {
      byte[] iv = new byte[GCM_IV_LENGTH];
      new SecureRandom().nextBytes(iv);

      Cipher cipher = Cipher.getInstance(ALGORITHM);
      cipher.init(Cipher.ENCRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

      byte[] cipherText = cipher.doFinal(plainText.getBytes(StandardCharsets.UTF_8));

      // IV + 暗号文 を結合して Base64 エンコード
      byte[] combined = ByteBuffer.allocate(iv.length + cipherText.length)
          .put(iv)
          .put(cipherText)
          .array();

      return ENC_PREFIX + Base64.getEncoder().encodeToString(combined) + ENC_SUFFIX;
    } catch (Exception e) {
      throw new CryptoBotException("暗号化に失敗しました。", e);
    }
  }

  /**
   * {@code ENC(...)} 形式の暗号化済み文字列を復号して平文を返す。
   * {@code ENC(...)} 形式でない場合はそのまま返す（平文として扱う）。
   *
   * @param encryptedValue 暗号化済み文字列（または平文）
   * @return 復号された平文
   */
  public String decrypt(String encryptedValue) {
    if (!isEncrypted(encryptedValue)) {
      return encryptedValue;
    }
    requireKey();
    try {
      String base64 = encryptedValue
          .substring(ENC_PREFIX.length(), encryptedValue.length() - ENC_SUFFIX.length());
      byte[] combined = Base64.getDecoder().decode(base64);

      ByteBuffer buffer = ByteBuffer.wrap(combined);
      byte[] iv = new byte[GCM_IV_LENGTH];
      buffer.get(iv);
      byte[] cipherText = new byte[buffer.remaining()];
      buffer.get(cipherText);

      Cipher cipher = Cipher.getInstance(ALGORITHM);
      cipher.init(Cipher.DECRYPT_MODE, secretKey, new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv));

      byte[] plainBytes = cipher.doFinal(cipherText);
      return new String(plainBytes, StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new CryptoBotException("復号に失敗しました。CRYPTO_MASTER_KEY を確認してください。", e);
    }
  }

  /** 値が {@code ENC(...)} 形式かどうかを判定する。 */
  public boolean isEncrypted(String value) {
    return value != null && value.startsWith(ENC_PREFIX) && value.endsWith(ENC_SUFFIX);
  }

  // -----------------------------------------------------------------------
  // Private helpers
  // -----------------------------------------------------------------------

  private SecretKey deriveKey(String masterKey) {
    try {
      SecretKeyFactory factory = SecretKeyFactory.getInstance(KEY_DERIVATION);
      KeySpec spec = new PBEKeySpec(
          masterKey.toCharArray(), FIXED_SALT, ITERATIONS, KEY_LENGTH_BITS);
      byte[] keyBytes = factory.generateSecret(spec).getEncoded();
      return new SecretKeySpec(keyBytes, "AES");
    } catch (Exception e) {
      throw new CryptoBotException("鍵の導出に失敗しました。", e);
    }
  }

  private void requireKey() {
    if (secretKey == null) {
      throw new CryptoBotException(
          "環境変数 CRYPTO_MASTER_KEY が未設定のため暗号化/復号できません。");
    }
  }
}
