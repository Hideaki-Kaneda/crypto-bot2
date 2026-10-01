package com.example.cryptobot2.repository;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * trading_config テーブルへのアクセスリポジトリ。
 *
 * <p>スケジューラ実行ごとに全設定を読み込み、AppProperties を上書きする。
 * DB に値がない場合は application.properties の値をフォールバックとして使用する。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TradingConfigRepository {

  private final JdbcTemplate jdbcTemplate;

  /**
   * 全設定を Map で取得する。
   *
   * @return key → value の Map
   */
  public Map<String, String> findAll() {
    return jdbcTemplate.queryForList(
            "SELECT key, value FROM trading_config ORDER BY key")
        .stream()
        .collect(Collectors.toMap(
            r -> (String) r.get("key"),
            r -> (String) r.get("value")
        ));
  }

  /**
   * 指定キーの値を取得する。
   */
  public Optional<String> findByKey(String key) {
    var list = jdbcTemplate.queryForList(
        "SELECT value FROM trading_config WHERE key = ?", String.class, key);
    return list.isEmpty() ? Optional.empty() : Optional.of(list.get(0));
  }

  /**
   * 設定値を更新する（key が存在しない場合は INSERT）。
   */
  public void upsert(String key, String value) {
    jdbcTemplate.update("""
        INSERT INTO trading_config (key, value, updated_at)
        VALUES (?, ?, NOW())
        ON CONFLICT (key) DO UPDATE SET value = EXCLUDED.value, updated_at = NOW()
        """, key, value);
    log.info("trading_config 更新: {}={}", key, value);
  }
}
