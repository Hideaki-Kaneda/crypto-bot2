package com.example.cryptobot2.repository;

import com.example.cryptobot2.model.Position;
import com.example.cryptobot2.model.Position.CloseReason;
import com.example.cryptobot2.model.Position.PositionStatus;
import com.example.cryptobot2.model.TradeHistory;
import com.example.cryptobot2.model.TradeHistory.Side;
import com.example.cryptobot2.model.TradeHistory.TradeStatus;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * trade_history / position テーブルへの CRUD リポジトリ。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class TradeRepository {

  private final JdbcTemplate jdbcTemplate;

  // -----------------------------------------------------------------------
  // trade_history
  // -----------------------------------------------------------------------

  private static final String INSERT_TRADE_SQL = """
      INSERT INTO trade_history
        (symbol, trade_time, side, price, amount, amount_jpy, fee, status, order_id, signal_id, note, profit_jpy)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  /**
   * 売買履歴を保存して生成された ID を返す。
   */
  @Transactional
  public long saveTradeHistory(TradeHistory th) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(con -> {
      var ps = con.prepareStatement(INSERT_TRADE_SQL, new String[]{"id"});
      ps.setString(1, th.getSymbol());
      ps.setObject(2, th.getTradeTime());
      ps.setString(3, th.getSide().name());
      ps.setBigDecimal(4, th.getPrice());
      ps.setBigDecimal(5, th.getAmount());
      ps.setBigDecimal(6, th.getAmountJpy());
      ps.setBigDecimal(7, th.getFee());
      ps.setString(8, th.getStatus().name());
      ps.setString(9, th.getOrderId());
      ps.setObject(10, th.getSignalId());
      ps.setString(11, th.getNote());
      ps.setBigDecimal(12, th.getProfitJpy());
      return ps;
    }, keyHolder);

    long id = keyHolder.getKey().longValue();
    log.debug("TradeHistory保存: id={} symbol={} side={} status={}",
        id, th.getSymbol(), th.getSide(), th.getStatus());
    return id;
  }

  // -----------------------------------------------------------------------
  // position
  // -----------------------------------------------------------------------

  private static final String INSERT_POSITION_SQL = """
      INSERT INTO position
        (symbol, side, open_time, open_price, amount, amount_jpy,
         status, is_paper, trade_history_id)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String UPDATE_CLOSE_SQL = """
      UPDATE position SET
        close_time       = ?,
        close_price      = ?,
        profit_jpy       = ?,
        status           = 'CLOSED',
        close_reason     = ?,
        updated_at       = NOW()
      WHERE id = ?
      """;

  private static final String SELECT_OPEN_POSITION_SQL = """
      SELECT * FROM position
      WHERE symbol = ? AND status = 'OPEN' AND is_paper = ?
      ORDER BY open_time ASC
      LIMIT 1
      """;

  /**
   * オープンポジションを保存して生成された ID を返す。
   */
  @Transactional
  public long openPosition(Position pos) {
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(con -> {
      var ps = con.prepareStatement(INSERT_POSITION_SQL, new String[]{"id"});
      ps.setString(1, pos.getSymbol());
      ps.setString(2, pos.getSide().name());
      ps.setObject(3, pos.getOpenTime());
      ps.setBigDecimal(4, pos.getOpenPrice());
      ps.setBigDecimal(5, pos.getAmount());
      ps.setBigDecimal(6, pos.getAmountJpy());
      ps.setString(7, PositionStatus.OPEN.name());
      ps.setBoolean(8, pos.isPaper());
      ps.setObject(9, pos.getTradeHistoryId());
      return ps;
    }, keyHolder);

    long id = keyHolder.getKey().longValue();
    log.info("ポジションオープン: id={} symbol={} side={} price={} paper={}",
        id, pos.getSymbol(), pos.getSide(), pos.getOpenPrice(), pos.isPaper());
    return id;
  }

  /**
   * ポジションをクローズする。
   */
  @Transactional
  public void closePosition(long positionId, OffsetDateTime closeTime,
      BigDecimal closePrice, BigDecimal profitJpy, CloseReason reason) {
    int updated = jdbcTemplate.update(UPDATE_CLOSE_SQL,
        closeTime, closePrice, profitJpy, reason.name(), positionId);

    log.info("ポジションクローズ: id={} closePrice={} profitJpy={} reason={}",
        positionId, closePrice, profitJpy, reason);

    if (updated == 0) {
      log.warn("ポジションが見つかりませんでした: id={}", positionId);
    }
  }

  /**
   * 指定シンボルのオープンポジション（最古）を取得する。
   */
  public Optional<Position> findOpenPosition(String symbol, boolean isPaper) {
    List<Position> result = jdbcTemplate.query(
        SELECT_OPEN_POSITION_SQL, positionRowMapper(), symbol, isPaper);
    return result.isEmpty() ? Optional.empty() : Optional.of(result.get(0));
  }

  /**
   * 指定シンボルにオープンポジションが存在するかチェックする。
   */
  public boolean hasOpenPosition(String symbol, boolean isPaper) {
    return findOpenPosition(symbol, isPaper).isPresent();
  }

  // -----------------------------------------------------------------------
  // RowMapper
  // -----------------------------------------------------------------------

  private RowMapper<Position> positionRowMapper() {
    return (rs, rowNum) -> Position.builder()
        .id(rs.getLong("id"))
        .symbol(rs.getString("symbol"))
        .side(Side.valueOf(rs.getString("side")))
        .openTime(toOffsetDateTime(rs, "open_time"))
        .openPrice(rs.getBigDecimal("open_price"))
        .amount(rs.getBigDecimal("amount"))
        .amountJpy(rs.getBigDecimal("amount_jpy"))
        .closeTime(toOffsetDateTime(rs, "close_time"))
        .closePrice(rs.getBigDecimal("close_price"))
        .profitJpy(rs.getBigDecimal("profit_jpy"))
        .status(PositionStatus.valueOf(rs.getString("status")))
        .closeReason(rs.getString("close_reason") == null
            ? null : CloseReason.valueOf(rs.getString("close_reason")))
        .isPaper(rs.getBoolean("is_paper"))
        .tradeHistoryId(rs.getObject("trade_history_id", Long.class))
        .build();
  }

  private OffsetDateTime toOffsetDateTime(ResultSet rs, String col) throws SQLException {
    Timestamp ts = rs.getTimestamp(col);
    return ts == null ? null
        : ts.toInstant().atOffset(ZoneOffset.UTC);
  }
}
