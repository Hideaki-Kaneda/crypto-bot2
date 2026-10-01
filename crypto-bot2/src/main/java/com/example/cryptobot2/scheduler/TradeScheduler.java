package com.example.cryptobot2.scheduler;

import com.example.cryptobot2.config.AppProperties;
import com.example.cryptobot2.exception.CryptoBotException;
import com.example.cryptobot2.model.TradeSignal;
import com.example.cryptobot2.service.TradingConfigService;
import com.example.cryptobot2.trade.PaperTradeExecutor;
import com.example.cryptobot2.trade.TradeExecutor;
import com.example.cryptobot2.strategy.TradingStrategy;
import com.example.cryptobot2.util.ScheduleGuard;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 自動売買スケジューラ。
 *
 * <p>cron 式は {@code gmo.scheduler.trade-cron} で設定する（デフォルト: 10分ごと）。
 * <ol>
 *   <li>最新価格を DB から取得</li>
 *   <li>TradingStrategy で全指標計算・シグナル生成・DB保存</li>
 *   <li>paperMode に応じて PaperTradeExecutor / TradeExecutor を呼び出す</li>
 * </ol>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradeScheduler {

  private static final String LATEST_PRICE_SQL = """
      SELECT close FROM kline_data
      WHERE symbol = ? AND interval_type = ?
      ORDER BY open_time DESC
      LIMIT 1
      """;

  private final AppProperties props;
  private final TradingConfigService tradingConfigService;
  private final TradingStrategy tradingStrategy;
  private final PaperTradeExecutor paperTradeExecutor;
  private final TradeExecutor tradeExecutor;
  private final JdbcTemplate jdbcTemplate;

  @Scheduled(
      cron = "${gmo.scheduler.trade-cron}",
      zone = "${gmo.scheduler.timezone}"
  )
  public void run() {
    ZoneId zone = ZoneId.of(props.getScheduler().getTimezone());

    if (ScheduleGuard.isMaintenanceTime(zone)) {
      return;
    }

    // DB から最新設定を読み込む（再起動なしに設定変更を反映）
    tradingConfigService.reload(props);

    List<String> symbols = props.getKline().getSymbols();
    String interval = props.getKline().getInterval();
    boolean isPaper = props.getTrade().isPaperMode();

    log.info("--- 自動売買スケジューラ起動 symbols={} interval={} mode={} ---",
        symbols, interval, isPaper ? "PAPER" : "本番");

    for (String symbol : symbols) {
      try {
        BigDecimal latestPrice = fetchLatestPrice(symbol, interval);
        if (latestPrice == null) {
          log.warn("最新価格が取得できませんでした。symbol={} interval={}", symbol, interval);
          continue;
        }

        // signalTime は最新 kline の open_time を使う
        // （バックテストと同じ基準にすることで trade_signal の時系列整合性を保つ）
        OffsetDateTime signalTime = fetchLatestOpenTime(symbol, interval);
        if (signalTime == null) {
          log.warn("最新open_timeが取得できませんでした。symbol={} interval={}", symbol, interval);
          continue;
        }

        TradeSignal signal = tradingStrategy.evaluate(symbol, interval, latestPrice, signalTime);

        log.info("symbol={} finalSignal={} price={} mode={}",
            symbol, signal.getFinalSignal(), latestPrice, isPaper ? "PAPER" : "本番");

        if (isPaper) {
          paperTradeExecutor.execute(signal);
        } else {
          tradeExecutor.execute(signal);
        }

      } catch (CryptoBotException e) {
        log.error("自動売買でエラー symbol={}: {}", symbol, e.getMessage(), e);
      } catch (Throwable t) {
        log.error("自動売買で予期しないエラー symbol={}: {}", symbol, t.getMessage(), t);
      }
    }
  }

  private static final String LATEST_OPEN_TIME_SQL = """
      SELECT open_time FROM kline_data
      WHERE symbol = ? AND interval_type = ?
      ORDER BY open_time DESC
      LIMIT 1
      """;

  private BigDecimal fetchLatestPrice(String symbol, String interval) {
    List<BigDecimal> result = jdbcTemplate.queryForList(
        LATEST_PRICE_SQL, BigDecimal.class, symbol, interval);
    return result.isEmpty() ? null : result.get(0);
  }

  private OffsetDateTime fetchLatestOpenTime(String symbol, String interval) {
    List<java.sql.Timestamp> result = jdbcTemplate.queryForList(
        LATEST_OPEN_TIME_SQL, java.sql.Timestamp.class, symbol, interval);
    if (result.isEmpty() || result.get(0) == null) return null;
    return result.get(0).toInstant().atOffset(java.time.ZoneOffset.UTC);
  }
}
