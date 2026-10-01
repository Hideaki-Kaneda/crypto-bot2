package com.example.cryptobot2.service;

import com.example.cryptobot2.config.AppProperties;
import com.example.cryptobot2.repository.TradingConfigRepository;
import java.math.BigDecimal;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * DB の trading_config を AppProperties に反映するサービス。
 *
 * <p>スケジューラ実行ごとに呼び出すことで、再起動なしに設定変更を反映できる。
 * DB に値がない場合は application.properties の値（デフォルト）をそのまま使用する。
 *
 * <h2>管理対象の設定キー</h2>
 * <pre>
 * trade.paper-mode               … ペーパートレードモード
 * trade.size                     … 発注数量（BTC）
 * trade.stop-loss-jpy            … 損切り金額（円）
 * trade.flip-close-sell-min-bars … 符号反転利確最低保有本数（売建て）
 * trade.flip-close-buy-min-bars  … 符号反転利確最低保有本数（買建て）
 * trade.daily-profit-limit       … 1日の損益上限（円）
 * trade.daily-loss-limit         … 1日の損益下限（円）
 * indicator.signal-reverse       … シグナル反転
 * indicator.ema-trend-period     … EMAトレンドフィルター期間
 * indicator.ema-trend-reverse    … EMAフィルター反転
 * indicator.rci-entry-min        … RCI新規建てフィルター下限
 * indicator.rci-entry-max        … RCI新規建てフィルター上限
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TradingConfigService {

  private final TradingConfigRepository configRepository;

  /**
   * DB から全設定を読み込み AppProperties に反映する。
   *
   * @param props 更新対象の AppProperties
   */
  public void reload(AppProperties props) {
    Map<String, String> config = configRepository.findAll();
    if (config.isEmpty()) {
      log.debug("trading_config が空です。application.properties の値を使用します。");
      return;
    }

    AppProperties.Trade trade     = props.getTrade();
    AppProperties.Indicator ind   = props.getIndicator();

    // --- trade 設定 ---
    applyBoolean(config, "trade.paper-mode", v -> trade.setPaperMode(v));
    applyBigDecimal(config, "trade.size", v -> trade.setSize(v));
    applyBigDecimal(config, "trade.stop-loss-jpy", v -> trade.setStopLossJpy(v));
    applyInt(config,        "trade.flip-close-sell-min-bars", v -> trade.setFlipCloseSellMinBars(v));
    applyInt(config,        "trade.flip-close-buy-min-bars",  v -> trade.setFlipCloseBuyMinBars(v));
    applyBigDecimal(config, "trade.daily-profit-limit",       v -> trade.setDailyProfitLimit(v));
    applyBigDecimal(config, "trade.daily-loss-limit",         v -> trade.setDailyLossLimit(v));

    // --- indicator 設定 ---
    applyBoolean(config, "indicator.signal-reverse",    v -> ind.setSignalReverse(v));
    applyInt(config,     "indicator.ema-trend-period",  v -> ind.setEmaTrendPeriod(v));
    applyBoolean(config, "indicator.ema-trend-reverse", v -> ind.setEmaTrendReverse(v));
    applyDouble(config,  "indicator.rci-entry-min",     v -> ind.setRciEntryMin(v));
    applyDouble(config,  "indicator.rci-entry-max",     v -> ind.setRciEntryMax(v));

    log.info("trading_config 読み込み完了: {} 件", config.size());
    log.debug("現在の設定: paperMode={} size={} signalReverse={} emaPeriod={} "
            + "emaReverse={} rciMin={} rciMax={} stopLossJpy={}",
        trade.isPaperMode(), trade.getSize(),
        ind.isSignalReverse(), ind.getEmaTrendPeriod(),
        ind.isEmaTrendReverse(), ind.getRciEntryMin(), ind.getRciEntryMax(),
        trade.getStopLossJpy());
  }

  // -----------------------------------------------------------------------
  // Private ヘルパー
  // -----------------------------------------------------------------------

  private void applyBoolean(Map<String, String> config, String key,
      java.util.function.Consumer<Boolean> setter) {
    String v = config.get(key);
    if (v != null) {
      try {
        setter.accept(Boolean.parseBoolean(v.trim()));
      } catch (Exception e) {
        log.warn("trading_config 値が不正: key={} value={}", key, v);
      }
    }
  }

  private void applyInt(Map<String, String> config, String key,
      java.util.function.Consumer<Integer> setter) {
    String v = config.get(key);
    if (v != null) {
      try {
        setter.accept(Integer.parseInt(v.trim()));
      } catch (Exception e) {
        log.warn("trading_config 値が不正: key={} value={}", key, v);
      }
    }
  }

  private void applyDouble(Map<String, String> config, String key,
      java.util.function.Consumer<Double> setter) {
    String v = config.get(key);
    if (v != null) {
      try {
        setter.accept(Double.parseDouble(v.trim()));
      } catch (Exception e) {
        log.warn("trading_config 値が不正: key={} value={}", key, v);
      }
    }
  }

  private void applyBigDecimal(Map<String, String> config, String key,
      java.util.function.Consumer<BigDecimal> setter) {
    String v = config.get(key);
    if (v != null) {
      try {
        setter.accept(new BigDecimal(v.trim()));
      } catch (Exception e) {
        log.warn("trading_config 値が不正: key={} value={}", key, v);
      }
    }
  }
}
