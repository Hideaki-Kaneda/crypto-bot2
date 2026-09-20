package com.example.cryptobot2.util;

import com.example.cryptobot2.service.EncryptionService;

/**
 * コマンドラインから平文を暗号化するユーティリティ。
 * Spring コンテキストを一切起動せず単独で動作する。
 *
 * <p>Fat JAR から以下のように実行する（-jar ではなく -cp で内部クラスパスを展開する）:
 * <pre>
 *   java -DCRYPTO_MASTER_KEY=xxx
 *        -cp "target/crypto-bot2-1.0.0.jar;target/dependency/*"
 *        com.example.cryptobot2.util.EncryptCli YOUR_PLAIN_TEXT
 * </pre>
 *
 * <p>ただし Fat JAR から直接呼ぶ場合は
 * {@code org.springframework.boot.loader.launch.PropertiesLauncher} を使う:
 * <pre>
 *   java -DCRYPTO_MASTER_KEY=xxx
 *        -Dloader.main=com.example.cryptobot2.util.EncryptCli
 *        -jar target/crypto-bot2-1.0.0.jar YOUR_PLAIN_TEXT
 * </pre>
 *
 * <p>標準出力に ENC(...) 形式の暗号化済み文字列を1行出力して終了する。
 */
public class EncryptCli {

  public static void main(String[] args) {
    if (args.length != 1 || args[0].isBlank()) {
      System.err.println("[ERROR] 暗号化する値を引数に指定してください。");
      System.err.println("使用方法: EncryptCli <plain-text>");
      System.exit(1);
    }

    String masterKey = System.getProperty("CRYPTO_MASTER_KEY");
    if (masterKey == null || masterKey.isBlank()) {
      masterKey = System.getenv("CRYPTO_MASTER_KEY");
    }
    if (masterKey == null || masterKey.isBlank()) {
      System.err.println("[ERROR] CRYPTO_MASTER_KEY が未設定です。");
      System.err.println("  -DCRYPTO_MASTER_KEY=xxx を指定してください。");
      System.exit(1);
    }

    EncryptionService service = new EncryptionService(masterKey);
    System.out.println(service.encrypt(args[0]));
  }
}
