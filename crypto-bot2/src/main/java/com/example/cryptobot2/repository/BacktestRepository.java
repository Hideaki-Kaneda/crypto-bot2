package com.example.cryptobot2.repository;

import com.example.cryptobot2.model.KlineRecord;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/**
 * バックテスト用 kline_data 取得リポジトリ。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class BacktestRepository {

  private final JdbcTemplate jdbcTemplate;

  /**
   * 指定期間の KLine データを時系列昇順で取得する。
   *
   * <p>GMOコインの日付切り替えは JST 06:00 基準のため、
   * from/to は JST 06:00 単位で渡すこと。
   */
  public List<KlineRecord> fetchRange(String symbol, String interval,
      OffsetDateTime from, OffsetDateTime to) {
    String sql = """
        SELECT symbol, interval_type, open_time, open, high, low, close, volume
        FROM kline_data
        WHERE symbol = ? AND interval_type = ?
          AND open_time >= ? AND open_time < ?
        ORDER BY open_time ASC
        """;
    return jdbcTemplate.query(sql, klineRowMapper(), symbol, interval, from, to);
  }

  /**
   * 指定 GMO 日付（JST 06:00〜翌 05:55）のデータが DB に存在するか確認する。
   *
   * <p>GMO の date=YYYYMMDD は JST 06:00〜翌 05:55 のデータを返す。
   * open_time（UTC）で言うと前日 21:00〜当日 20:55 に相当する。
   * JST 変換して 06:00〜翌 05:59 の範囲でカウントする。
   *
   * @param gmoDate GMO API の date パラメータに対応するカレンダー日付（JST 基準）
   */
  public boolean existsByGmoDate(String symbol, String interval, LocalDate gmoDate) {
    // JST 06:00 〜 翌日 JST 05:59 を UTC に変換
    // JST = UTC+9 なので JST 06:00 = UTC 前日 21:00
    OffsetDateTime from = gmoDate.atTime(6, 0).atOffset(java.time.ZoneOffset.ofHours(9))
        .withOffsetSameInstant(ZoneOffset.UTC);
    OffsetDateTime to   = gmoDate.plusDays(1).atTime(5, 59, 59)
        .atOffset(java.time.ZoneOffset.ofHours(9))
        .withOffsetSameInstant(ZoneOffset.UTC);

    String sql = """
        SELECT COUNT(*) FROM kline_data
        WHERE symbol = ? AND interval_type = ?
          AND open_time >= ? AND open_time <= ?
        """;
    Integer count = jdbcTemplate.queryForObject(sql, Integer.class,
        symbol, interval, from, to);
    return count != null && count > 0;
  }

  /**
   * 指標計算用の直近 n 件の終値・高値・安値を昇順で取得する。
   * open_time が指定時刻より前のデータを対象とする。
   */
  public List<KlineRecord> fetchHistoryBefore(String symbol, String interval,
      OffsetDateTime before, int limit) {
    String sql = """
        SELECT symbol, interval_type, open_time, open, high, low, close, volume
        FROM kline_data
        WHERE symbol = ? AND interval_type = ? AND open_time < ?
        ORDER BY open_time DESC
        LIMIT ?
        """;
    List<KlineRecord> desc = jdbcTemplate.query(sql, klineRowMapper(),
        symbol, interval, before, limit);
    // 昇順に並べ直す
    List<KlineRecord> asc = new java.util.ArrayList<>(desc);
    java.util.Collections.reverse(asc);
    return asc;
  }

  private RowMapper<KlineRecord> klineRowMapper() {
    return (rs, rowNum) -> KlineRecord.builder()
        .symbol(rs.getString("symbol"))
        .intervalType(rs.getString("interval_type"))
        .openTime(toOffsetDateTime(rs, "open_time"))
        .open(rs.getBigDecimal("open"))
        .high(rs.getBigDecimal("high"))
        .low(rs.getBigDecimal("low"))
        .close(rs.getBigDecimal("close"))
        .volume(rs.getBigDecimal("volume"))
        .build();
  }

  private OffsetDateTime toOffsetDateTime(ResultSet rs, String col) throws SQLException {
    var ts = rs.getTimestamp(col);
    return ts == null ? null : ts.toInstant().atOffset(ZoneOffset.UTC);
  }
}
