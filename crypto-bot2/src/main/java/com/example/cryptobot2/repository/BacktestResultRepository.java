package com.example.cryptobot2.repository;

import com.example.cryptobot2.model.BacktestResult;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.support.GeneratedKeyHolder;
import org.springframework.jdbc.support.KeyHolder;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * バックテスト結果の保存リポジトリ。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class BacktestResultRepository {

  private final JdbcTemplate jdbcTemplate;

  private static final String INSERT_RUN_SQL = """
      INSERT INTO backtest_run
        (symbol, interval_type, start_date, end_date, initial_balance_jpy,
         final_balance_jpy, total_profit_jpy, trade_count, win_count, loss_count,
         win_rate, max_drawdown_jpy)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  private static final String INSERT_TRADE_SQL = """
      INSERT INTO backtest_trade
        (run_id, symbol, trade_time, side, price, size,
         profit_jpy, balance_jpy, close_reason, signal_conditions)
      VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
      """;

  /**
   * バックテストサマリと取引履歴を一括保存する。
   *
   * @return 生成された run_id
   */
  @Transactional
  public long save(BacktestResult result, List<BacktestResult.Trade> trades) {
    // サマリ保存
    KeyHolder keyHolder = new GeneratedKeyHolder();
    jdbcTemplate.update(con -> {
      var ps = con.prepareStatement(INSERT_RUN_SQL, new String[]{"id"});
      ps.setString(1, result.getSymbol());
      ps.setString(2, result.getIntervalType());
      ps.setObject(3, result.getStartDate());
      ps.setObject(4, result.getEndDate());
      ps.setBigDecimal(5, result.getInitialBalanceJpy());
      ps.setBigDecimal(6, result.getFinalBalanceJpy());
      ps.setBigDecimal(7, result.getTotalProfitJpy());
      ps.setInt(8, result.getTradeCount());
      ps.setInt(9, result.getWinCount());
      ps.setInt(10, result.getLossCount());
      ps.setBigDecimal(11, result.getWinRate());
      ps.setBigDecimal(12, result.getMaxDrawdownJpy());
      return ps;
    }, keyHolder);

    long runId = keyHolder.getKey().longValue();

    // 取引履歴を一括保存
    if (!trades.isEmpty()) {
      List<Object[]> batchArgs = trades.stream()
          .map(t -> new Object[]{
              runId,
              t.getSymbol(),
              t.getTradeTime(),
              t.getSide().name(),
              t.getPrice(),
              t.getSize(),
              t.getProfitJpy(),
              t.getBalanceJpy(),
              t.getCloseReason() == null ? null : t.getCloseReason().name(),
              t.getSignalConditions()
          })
          .toList();
      jdbcTemplate.batchUpdate(INSERT_TRADE_SQL, batchArgs);
    }

    log.info("バックテスト結果保存: runId={} 取引件数={}", runId, trades.size());
    return runId;
  }
}
