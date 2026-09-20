package com.example.cryptobot2.strategy;

import com.example.cryptobot2.config.AppProperties;
import com.example.cryptobot2.indicator.DmiCalculator;
import com.example.cryptobot2.indicator.MacdCalculator;
import com.example.cryptobot2.indicator.RciCalculator;
import com.example.cryptobot2.indicator.RsiCalculator;
import com.example.cryptobot2.indicator.TemaCalculator;
import com.example.cryptobot2.model.TradeSignal;
import com.example.cryptobot2.model.TradeSignal.CrossSignal;
import com.example.cryptobot2.model.TradeSignal.Signal;
import com.example.cryptobot2.repository.TradeSignalRepository;
import com.example.cryptobot2.repository.TradeSignalRepository.PrevSignalRow;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 売買戦略クラス。
 *
 * <h2>売りシグナル（OR条件）</h2>
 * <ol>
 *   <li>RCI ≥ 90 かつ 前回RCI ≤ 60 かつ 前回MACDがゴールデンクロスでない</li>
 *   <li>RCI ≥ 100 かつ 前回RCI ≤ 70 かつ 前回MACDがゴールデンクロスでない</li>
 *   <li>RSI > 80 かつ MACDがゴールデンクロス</li>
 *   <li>MACDがゴールデンクロス かつ TEMAがゴールデンクロス かつ RSIシグナルがSELL</li>
 * </ol>
 *
 * <h2>買いシグナル（OR条件）</h2>
 * <ol>
 *   <li>RCI ≤ -90 かつ 前回RCI ≥ -60 かつ 前回MACDがデッドクロスでない</li>
 *   <li>RCI ≤ -100 かつ 前回RCI ≥ -70 かつ 前回MACDがデッドクロスでない</li>
 *   <li>RSI < 20 かつ MACDがデッドクロス</li>
 *   <li>MACDがデッドクロス かつ TEMAがデッドクロス かつ RSIシグナルがBUY</li>
 * </ol>
 *
 * <h2>連続利確（ドテン）</h2>
 * <ul>
 *   <li>同方向シグナルが3回連続 → 3回目を逆方向で利確＋ドテン新規</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingStrategy {

  /** 連続シグナルでドテンするカウント */
  private static final int CONSECUTIVE_REVERSE_COUNT = 3;

  private final AppProperties props;
  private final TradeSignalRepository signalRepository;

  /**
   * 指定シンボルの直近データから全指標を計算し、TradeSignal を生成・保存して返す。
   */
  public TradeSignal evaluate(
      String symbol, String interval, BigDecimal nowPrice, OffsetDateTime signalTime) {

    AppProperties.Indicator ind = props.getIndicator();
    int historySize = ind.getPriceHistorySize();

    // --- データ取得 ---
    List<BigDecimal> closes = fetchClosesAsc(symbol, interval, historySize);
    List<Map<String, Object>> hlc = signalRepository.fetchHighLowCloseHistory(
        symbol, interval, historySize);
    Collections.reverse(hlc);
    List<BigDecimal> highs = extractColumn(hlc, "high");
    List<BigDecimal> lows  = extractColumn(hlc, "low");

    // --- RSI ---
    BigDecimal rsiVal = RsiCalculator.calculate(closes, ind.getRsiPeriod());
    Signal rsiSignal  = toRsiSignal(rsiVal, ind);

    // --- MACD（今回） ---
    MacdCalculator.MacdResult macdResult = MacdCalculator.calculate(
        closes, ind.getMacdFastPeriod(), ind.getMacdSlowPeriod(), ind.getMacdSignalPeriod());
    CrossSignal macdCross = macdResult == null ? null
        : CrossSignal.valueOf(macdResult.getCross().name());

    // --- DMI ---
    DmiCalculator.DmiResult dmiResult = DmiCalculator.calculate(
        highs, lows, closes, ind.getDmiPeriod(), ind.getAdxPeriod());
    Signal dmiSignal = toDmiSignal(dmiResult, ind);

    // --- RCI（今回） ---
    BigDecimal rciVal = RciCalculator.calculate(closes, ind.getRciPeriod());
    Signal rciSignal  = toRciSignal(rciVal, ind);

    // --- TEMA（今回） ---
    TemaCalculator.TemaResult temaResult = TemaCalculator.calculatePair(
        closes, ind.getTemaFastPeriod(), ind.getTemaSlowPeriod());
    CrossSignal temaCross = temaResult == null ? null
        : CrossSignal.valueOf(temaResult.getCross().name());

    // --- 前回シグナル取得（RCI・MACDクロス・TEMAクロス参照用）---
    PrevSignalRow prev = signalRepository.fetchPrevSignal(symbol, signalTime);

    // --- 総合シグナル判定 ---
    Signal finalSignal = calcFinalSignal(
        rsiVal, rsiSignal, rciVal, rciSignal, dmiSignal, macdCross, temaCross, prev);

    // --- 連続シグナルによるドテン判定 ---
    // 直前 (CONSECUTIVE_REVERSE_COUNT - 1) 件が同方向で、今回も同じならドテン
    // → finalSignal を逆方向に上書き
    finalSignal = applyConsecutiveReverse(symbol, finalSignal);

    // --- DB 保存 ---
    TradeSignal ts = TradeSignal.builder()
        .symbol(symbol)
        .signalTime(signalTime)
        .price(nowPrice)
        .rsi(rsiVal)
        .rsiPeriod(ind.getRsiPeriod())
        .rsiSignal(rsiSignal)
        .macd(macdResult == null ? null : macdResult.getMacd())
        .macdSignal(macdResult == null ? null : macdResult.getSignal())
        .macdHistogram(macdResult == null ? null : macdResult.getHistogram())
        .macdFast(ind.getMacdFastPeriod())
        .macdSlow(ind.getMacdSlowPeriod())
        .macdSignalPeriod(ind.getMacdSignalPeriod())
        .macdCross(macdCross)
        .dmiPlus(dmiResult == null ? null : dmiResult.getPlusDi())
        .dmiMinus(dmiResult == null ? null : dmiResult.getMinusDi())
        .adx(dmiResult == null ? null : dmiResult.getAdx())
        .dmiPeriod(ind.getDmiPeriod())
        .dmiSignal(dmiSignal)
        .rci(rciVal)
        .rciPeriod(ind.getRciPeriod())
        .rciSignal(rciSignal)
        .temaFast(temaResult == null ? null : temaResult.getTemaFast())
        .temaFastPeriod(ind.getTemaFastPeriod())
        .temaSlow(temaResult == null ? null : temaResult.getTemaSlow())
        .temaSlowPeriod(ind.getTemaSlowPeriod())
        .temaCross(temaCross)
        .finalSignal(finalSignal)
        .build();

    signalRepository.save(ts);

    log.info("シグナル判定 symbol={} price={} RSI={} RCI={} MACD_CROSS={} TEMA_CROSS={} "
            + "prevRCI={} prevMACD={} FINAL={}",
        symbol, nowPrice, rsiVal, rciVal, macdCross, temaCross,
        prev == null ? null : prev.rci(),
        prev == null ? null : prev.macdCross(),
        finalSignal);

    return ts;
  }

  // -----------------------------------------------------------------------
  // Private — 総合シグナル判定
  // -----------------------------------------------------------------------

  /**
   * 売買シグナルを OR 条件で判定する。
   */
  private Signal calcFinalSignal(
      BigDecimal rsi, Signal rsiSignal,
      BigDecimal rci, Signal rciSignal,
      Signal dmiSignal,
      CrossSignal macdCross, CrossSignal temaCross,
      PrevSignalRow prev) {

    double rciD    = rci == null ? 0.0 : rci.doubleValue();
    double rsiD    = rsi == null ? 0.0 : rsi.doubleValue();
    double prevRci = (prev == null || prev.rci() == null) ? 0.0 : prev.rci().doubleValue();

    boolean macdGolden = macdCross == CrossSignal.GOLDEN;
    boolean macdDead   = macdCross == CrossSignal.DEAD;
    boolean temaGolden = temaCross == CrossSignal.GOLDEN;
    boolean temaDead   = temaCross == CrossSignal.DEAD;

    boolean prevMacdGolden = prev != null && prev.isMacdGolden();
    boolean prevMacdDead   = prev != null && prev.isMacdDead();
    boolean prevTemaGolden = prev != null && prev.isTemaGolden();
    boolean prevTemaDead   = prev != null && prev.isTemaDead();

    AppProperties.Indicator ind = props.getIndicator();

    // ========================
    // 売りシグナル（OR）
    // ========================
    // ① RCI≥90 かつ 前回RCI≤60 かつ 前回MACDがGCでない
    boolean sell1 = rciD >= 90  && prevRci <= 60  && !prevMacdGolden;
    // ② RCI≥100 かつ 前回RCI≤70 かつ 前回MACDがGCでない
    boolean sell2 = rciD >= 100 && prevRci <= 70  && !prevMacdGolden;
    // ③ RSI > overbought(80) かつ MACDがGC
    boolean sell3 = rsiD > ind.getRsiOverbought() && macdGolden;
    // ④ MACDがGC かつ TEMAがGC かつ RSIシグナルがSELL
    boolean sell4 = macdGolden && temaGolden && rsiSignal == Signal.SELL;
    // ⑤ MACDがGC かつ 前回TEMAがGC
    boolean sell5 = macdGolden && prevTemaGolden;
    // ⑥ TEMAがGC かつ 前回MACDがGC
    boolean sell6 = temaGolden && prevMacdGolden;
    // ⑦ RCIがSELL かつ TEMAがGC
    boolean sell7 = rciSignal == Signal.SELL && temaGolden;
    // ⑧ TEMAがGC かつ 前回MACDがGC（⑥と同一のため統合済み）
    // ⑨ DMIがSELL かつ RCIがSELL かつ RSI≥65
    boolean sell9 = dmiSignal == Signal.SELL && rciSignal == Signal.SELL && rsiD >= 65.0;
    // ⑩ MACDがGC かつ TEMAがGC かつ DMIがSELL
    boolean sell10 = macdGolden && temaGolden && dmiSignal == Signal.SELL;

    if (sell1 || sell2 || sell3 || sell4 || sell5 || sell6 || sell7 || sell9 || sell10) {
      log.debug("SELL条件: ①={} ②={} ③={} ④={} ⑤={} ⑥={} ⑦={} ⑨={} ⑩={}",
          sell1, sell2, sell3, sell4, sell5, sell6, sell7, sell9, sell10);
      return Signal.SELL;
    }

    // ========================
    // 買いシグナル（OR）
    // ========================
    // ① RCI≤-90 かつ 前回RCI≥-60 かつ 前回MACDがDCでない
    boolean buy1 = rciD <= -90  && prevRci >= -60 && !prevMacdDead;
    // ② RCI≤-100 かつ 前回RCI≥-70 かつ 前回MACDがDCでない
    boolean buy2 = rciD <= -100 && prevRci >= -70 && !prevMacdDead;
    // ③ RSI < oversold(20) かつ MACDがDC
    boolean buy3 = rsiD < ind.getRsiOversold() && macdDead;
    // ④ MACDがDC かつ TEMAがDC かつ RSIシグナルがBUY
    boolean buy4 = macdDead && temaDead && rsiSignal == Signal.BUY;
    // ⑤ MACDがDC かつ 前回TEMAがDC
    boolean buy5 = macdDead && prevTemaDead;
    // ⑥ TEMAがDC かつ 前回MACDがDC
    boolean buy6 = temaDead && prevMacdDead;
    // ⑦ RCIがBUY かつ TEMAがDC
    boolean buy7 = rciSignal == Signal.BUY && temaDead;
    // ⑨ DMIがBUY かつ RCIがBUY かつ RSI≤35
    boolean buy9 = dmiSignal == Signal.BUY && rciSignal == Signal.BUY && rsiD <= 35.0;
    // ⑩ MACDがDC かつ TEMAがDC かつ DMIがBUY
    boolean buy10 = macdDead && temaDead && dmiSignal == Signal.BUY;

    if (buy1 || buy2 || buy3 || buy4 || buy5 || buy6 || buy7 || buy9 || buy10) {
      log.debug("BUY条件: ①={} ②={} ③={} ④={} ⑤={} ⑥={} ⑦={} ⑨={} ⑩={}",
          buy1, buy2, buy3, buy4, buy5, buy6, buy7, buy9, buy10);
      return Signal.BUY;
    }

    return Signal.HOLD;
  }

  /**
   * 直近 CONSECUTIVE_REVERSE_COUNT 件が同方向シグナルの場合、逆方向に反転する。
   *
   * <p>例: BUY が3回連続 → 3回目を SELL（利確）として返す。
   * その後 Executor 側でポジション決済＋逆方向新規建てが行われる。
   *
   * <p>注意: このメソッドを呼ぶ時点では今回のシグナルはまだ DB に保存されていない。
   * そのため「直近 (n-1) 件が同方向 かつ 今回も同方向」で判定する。
   */
  private Signal applyConsecutiveReverse(String symbol, Signal currentSignal) {
    if (currentSignal == Signal.HOLD) return currentSignal;

    int n = CONSECUTIVE_REVERSE_COUNT - 1; // 直前 (n-1) 件をチェック
    boolean prevConsecutive = signalRepository.isConsecutiveSignal(
        symbol, currentSignal.name(), n);

    if (prevConsecutive) {
      Signal reversed = currentSignal == Signal.BUY ? Signal.SELL : Signal.BUY;
      log.info("連続{}回シグナル検出 symbol={} signal={} → {} に反転（利確＋ドテン）",
          CONSECUTIVE_REVERSE_COUNT, symbol, currentSignal, reversed);
      return reversed;
    }

    return currentSignal;
  }

  // -----------------------------------------------------------------------
  // Private — 個別指標シグナル判定
  // -----------------------------------------------------------------------

  private Signal toRsiSignal(BigDecimal rsi, AppProperties.Indicator ind) {
    if (rsi == null) return null;
    if (rsi.doubleValue() <= ind.getRsiOversold())  return Signal.BUY;
    if (rsi.doubleValue() >= ind.getRsiOverbought()) return Signal.SELL;
    return Signal.HOLD;
  }

  private Signal toDmiSignal(DmiCalculator.DmiResult result, AppProperties.Indicator ind) {
    if (result == null) return null;
    boolean adxOk = result.getAdx().doubleValue() >= ind.getDmiAdxThreshold();
    if (!adxOk) return Signal.HOLD;
    if (result.getMinusDi().compareTo(result.getPlusDi()) > 0) return Signal.BUY;
    if (result.getPlusDi().compareTo(result.getMinusDi()) > 0) return Signal.SELL;
    return Signal.HOLD;
  }

  private Signal toRciSignal(BigDecimal rci, AppProperties.Indicator ind) {
    if (rci == null) return null;
    if (rci.doubleValue() <= ind.getRciOversold())  return Signal.BUY;
    if (rci.doubleValue() >= ind.getRciOverbought()) return Signal.SELL;
    return Signal.HOLD;
  }

  // -----------------------------------------------------------------------
  // Private — データ変換ヘルパー
  // -----------------------------------------------------------------------

  private List<BigDecimal> fetchClosesAsc(String symbol, String interval, int limit) {
    List<BigDecimal> desc = signalRepository.fetchCloseHistory(symbol, interval, limit);
    List<BigDecimal> asc = new ArrayList<>(desc);
    Collections.reverse(asc);
    return asc;
  }

  private List<BigDecimal> extractColumn(List<Map<String, Object>> rows, String col) {
    return rows.stream()
        .map(r -> (BigDecimal) r.get(col))
        .toList();
  }
}
