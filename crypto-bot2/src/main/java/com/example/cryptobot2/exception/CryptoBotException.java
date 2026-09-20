package com.example.cryptobot2.exception;

/**
 * crypto-bot2 アプリケーション共通例外。
 * API呼び出し失敗・DB書き込みエラー・暗号化処理エラーをラップする。
 */
public class CryptoBotException extends RuntimeException {

  public CryptoBotException(String message) {
    super(message);
  }

  public CryptoBotException(String message, Throwable cause) {
    super(message, cause);
  }
}
