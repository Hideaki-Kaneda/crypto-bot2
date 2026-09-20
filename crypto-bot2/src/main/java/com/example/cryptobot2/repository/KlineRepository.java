package com.example.cryptobot2.repository;

import com.example.cryptobot2.model.KlineRecord;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * kline_data テーブルへの書き込みリポジトリ。
 *
 * <p>UNIQUE 制約 (symbol, interval_type, open_time) を利用し、
 * {@code ON CONFLICT DO NOTHING} で冪等な書き込みを実現する。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class KlineRepository {

  private static final String UPSERT_SQL = """
      INSERT INTO kline_data
        (symbol, interval_type, open_time, open, high, low, close, volume)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?)
      ON CONFLICT (symbol, interval_type, open_time) DO NOTHING
      """;

  private final JdbcTemplate jdbcTemplate;

  /**
   * KlineRecord リストを一括で kline_data テーブルへ保存する。
   * 既に同一 (symbol, interval_type, open_time) のレコードが存在する場合はスキップする。
   *
   * @param records 保存するレコードリスト
   * @return 実際に INSERT されたレコード数
   */
  @Transactional
  public int saveAll(List<KlineRecord> records) {
    if (records.isEmpty()) {
      return 0;
    }

    List<Object[]> batchArgs = records.stream()
        .map(r -> new Object[]{
            r.getSymbol(),
            r.getIntervalType(),
            r.getOpenTime(),
            r.getOpen(),
            r.getHigh(),
            r.getLow(),
            r.getClose(),
            r.getVolume()
        })
        .toList();

    int[] results = jdbcTemplate.batchUpdate(UPSERT_SQL, batchArgs);

    int inserted = 0;
    for (int result : results) {
      if (result > 0) {
        inserted++;
      }
    }

    log.debug("DB保存: 対象={} 件, INSERT={} 件, SKIP={} 件",
        records.size(), inserted, records.size() - inserted);

    return inserted;
  }
}
