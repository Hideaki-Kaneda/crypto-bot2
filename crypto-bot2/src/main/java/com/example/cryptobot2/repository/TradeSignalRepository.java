package com.example.cryptobot2.repository;

import com.example.cryptobot2.model.TradeSignal;
import com.example.cryptobot2.model.TradeSignal.CrossSignal;
import com.example.cryptobot2.model.TradeSignal.Signal;
import java.math.BigDecimal;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * trade_signal テーブルへの書き込みリポジトリ。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TradeSignalRepository {

  private static final String UPSERT_SQL = """
      INSERT INTO trade_signal (
          symbol, signal_time, price,
          rsi, rsi_period, rsi_signal,
          macd, macd_signal, macd_histogram, macd_fast, macd_slow, macd_signal_period, macd_cross,
          dmi_plus, dmi_minus, adx, dmi_period, dmi_signal,
          rci, rci_period, rci_signal,
          tema_fast, tema_fast_period,
          tema_slow, tema_slow_period,
          tema_cross,
          final_signal
      ) VALUES (
          ?, ?, ?,
          ?, ?, ?,
          ?, ?, ?, ?, ?, ?, ?,
          ?, ?, ?, ?, ?,
          ?, ?, ?,
          ?, ?,
          ?, ?,
          ?,
          ?
      )
      ON CONFLICT (symbol, signal_time) DO UPDATE SET
          price            = EXCLUDED.price,
          rsi              = EXCLUDED.rsi,
          rsi_signal       = EXCLUDED.rsi_signal,
          macd             = EXCLUDED.macd,
          macd_signal      = EXCLUDED.macd_signal,
          macd_histogram   = EXCLUDED.macd_histogram,
          macd_cross       = EXCLUDED.macd_cross,
          dmi_plus         = EXCLUDED.dmi_plus,
          dmi_minus        = EXCLUDED.dmi_minus,
          adx              = EXCLUDED.adx,
          dmi_signal       = EXCLUDED.dmi_signal,
          rci              = EXCLUDED.rci,
          rci_signal       = EXCLUDED.rci_signal,
          tema_fast        = EXCLUDED.tema_fast,
          tema_slow        = EXCLUDED.tema_slow,
          tema_cross       = EXCLUDED.tema_cross,
          final_signal     = EXCLUDED.final_signal
      """;

  private static final String SELECT_PREV_SIGNAL_SQL = """
      SELECT rci, macd_cross, tema_cross, tema_fast, final_signal
      FROM trade_signal
      WHERE symbol = ? AND signal_time < ?
      ORDER BY signal_time DESC
      LIMIT 1
      """;

  private static final String SELECT_CONSECUTIVE_COUNT_SQL = """
      SELECT COUNT(*) FROM (
          SELECT final_signal FROM trade_signal
          WHERE symbol = ?
          ORDER BY signal_time DESC
          LIMIT ?
      ) sub
      WHERE final_signal = ?
      """;

  /**
   * 指定シグナル時刻より前の直近シグナルを1件取得する。
   * データなしの場合は null を返す。
   */
  public PrevSignalRow fetchPrevSignal(String symbol, java.time.OffsetDateTime before) {
    List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList(
        SELECT_PREV_SIGNAL_SQL, symbol, before);
    if (rows.isEmpty()) return null;
    java.util.Map<String, Object> row = rows.get(0);
    return new PrevSignalRow(
        toBigDecimal(row.get("rci")),
        toStr(row.get("macd_cross")),
        toStr(row.get("tema_cross")),
        toBigDecimal(row.get("tema_fast")),
        toStr(row.get("final_signal"))
    );
  }

  /**
   * 直近 n 件の BUY/SELL シグナル（HOLD 除外）がすべて指定シグナルかどうかを確認する。
   * HOLD はスキップして BUY/SELL のみを対象にカウントする。
   */
  public boolean isConsecutiveSignal(String symbol, String signal, int count) {
    List<String> recent = jdbcTemplate.queryForList("""
        SELECT final_signal FROM trade_signal
        WHERE symbol = ? AND final_signal != 'HOLD'
        ORDER BY signal_time DESC
        LIMIT ?
        """, String.class, symbol, count);

    if (recent.size() < count) return false;
    return recent.stream().allMatch(s -> signal.equals(s));
  }

  /** 直前シグナル行 */
  public record PrevSignalRow(
      java.math.BigDecimal rci,
      String macdCross,
      String temaCross,
      java.math.BigDecimal temaFast,
      String finalSignal
  ) {
    public boolean isMacdGolden() { return "GOLDEN".equals(macdCross); }
    public boolean isMacdDead()   { return "DEAD".equals(macdCross); }
    public boolean isTemaGolden() { return "GOLDEN".equals(temaCross); }
    public boolean isTemaDead()   { return "DEAD".equals(temaCross); }
  }

  private static final String SELECT_PRICE_HISTORY_SQL = """
      SELECT close FROM kline_data
      WHERE symbol = ? AND interval_type = ?
      ORDER BY open_time DESC
      LIMIT ?
      """;

  private static final String SELECT_HIGH_LOW_SQL = """
      SELECT high, low, close FROM kline_data
      WHERE symbol = ? AND interval_type = ?
      ORDER BY open_time DESC
      LIMIT ?
      """;

  private final JdbcTemplate jdbcTemplate;

  /**
   * TradeSignal を trade_signal テーブルへ保存する。
   * 同一 (symbol, signal_time) は上書き更新する。
   */
  @Transactional
  public void save(TradeSignal signal) {
    jdbcTemplate.update(UPSERT_SQL,
        signal.getSymbol(),
        signal.getSignalTime(),
        signal.getPrice(),
        // RSI
        signal.getRsi(),
        signal.getRsiPeriod(),
        toStr(signal.getRsiSignal()),
        // MACD
        signal.getMacd(),
        signal.getMacdSignal(),
        signal.getMacdHistogram(),
        signal.getMacdFast(),
        signal.getMacdSlow(),
        signal.getMacdSignalPeriod(),
        toStr(signal.getMacdCross()),
        // DMI
        signal.getDmiPlus(),
        signal.getDmiMinus(),
        signal.getAdx(),
        signal.getDmiPeriod(),
        toStr(signal.getDmiSignal()),
        // RCI
        signal.getRci(),
        signal.getRciPeriod(),
        toStr(signal.getRciSignal()),
        // TEMA fast
        signal.getTemaFast(),
        signal.getTemaFastPeriod(),
        // TEMA slow
        signal.getTemaSlow(),
        signal.getTemaSlowPeriod(),
        // TEMA cross
        toStr(signal.getTemaCross()),
        // final
        signal.getFinalSignal().name()
    );

    log.debug("TradeSignal保存: symbol={}, time={}, final={}",
        signal.getSymbol(), signal.getSignalTime(), signal.getFinalSignal());
  }

  /**
   * 終値履歴を取得する（新しい順 → 呼び出し側で逆順化する）。
   */
  public List<java.math.BigDecimal> fetchCloseHistory(String symbol, String interval, int limit) {
    return jdbcTemplate.queryForList(SELECT_PRICE_HISTORY_SQL,
        java.math.BigDecimal.class, symbol, interval, limit);
  }

  /**
   * 高値・安値・終値履歴を取得する（新しい順）。
   */
  public List<java.util.Map<String, Object>> fetchHighLowCloseHistory(
      String symbol, String interval, int limit) {
    return jdbcTemplate.queryForList(SELECT_HIGH_LOW_SQL, symbol, interval, limit);
  }

  // -----------------------------------------------------------------------
  // helpers
  // -----------------------------------------------------------------------

  /**
   * 指定シグナル時刻より前の直近終値を2件取得する（新しい順）。
   * インデックス0が直前、インデックス1が前々回。
   * 利確判定の「価格変化方向の反転」チェックに使用する。
   */
  public List<BigDecimal> fetchPrevCloses(String symbol, String interval,
      java.time.OffsetDateTime before) {
    List<java.util.Map<String, Object>> rows = jdbcTemplate.queryForList("""
        SELECT close FROM kline_data
        WHERE symbol = ? AND interval_type = ? AND open_time < ?
        ORDER BY open_time DESC
        LIMIT 2
        """, symbol, interval, before);
    return rows.stream()
        .map(r -> toBigDecimal(r.get("close")))
        .toList();
  }

  /**
   * 指定シグナル時刻より前の直近 TEMA_FAST 値を n 件取得する（新しい順）。
   * 利確判定の「TEMA_FAST 変化方向の反転」チェックに使用する。
   *
   * @return Map のリスト。各要素に "tema_fast" キーで BigDecimal 値を持つ
   */
  public List<java.util.Map<String, Object>> fetchPrevTemaFast(
      String symbol, String interval, java.time.OffsetDateTime before, int limit) {
    // trade_signal の signal_time で近似取得（kline の open_time と対応）
    return jdbcTemplate.queryForList("""
        SELECT tema_fast FROM trade_signal
        WHERE symbol = ? AND signal_time < ?
        ORDER BY signal_time DESC
        LIMIT ?
        """, symbol, before, limit);
  }

  private String toStr(Signal s) {
    return s == null ? null : s.name();
  }

  private String toStr(CrossSignal cs) {
    return cs == null ? null : cs.name();
  }

  private String toStr(Object obj) {
    return obj == null ? null : obj.toString();
  }

  private java.math.BigDecimal toBigDecimal(Object obj) {
    if (obj == null) return null;
    if (obj instanceof java.math.BigDecimal bd) return bd;
    return new java.math.BigDecimal(obj.toString());
  }
}
