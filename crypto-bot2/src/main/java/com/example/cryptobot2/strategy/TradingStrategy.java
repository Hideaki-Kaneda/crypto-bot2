package com.example.cryptobot2.strategy;

import com.example.cryptobot2.config.AppProperties;
import com.example.cryptobot2.indicator.DmiCalculator;
import com.example.cryptobot2.indicator.EmaCalculator;
import com.example.cryptobot2.indicator.MacdCalculator;
import com.example.cryptobot2.indicator.RciCalculator;
import com.example.cryptobot2.indicator.RsiCalculator;
import com.example.cryptobot2.indicator.TemaCalculator;
import com.example.cryptobot2.model.TradeSignal;
import com.example.cryptobot2.model.TradeSignal.CrossSignal;
import com.example.cryptobot2.model.TradeSignal.Signal;
import com.example.cryptobot2.repository.TradeSignalRepository;
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
 * <h2>新規建てシグナル</h2>
 * <ul>
 *   <li>BUY  : TEMA(5/9) がゴールデンクロス（ポジションなし時のみ）</li>
 *   <li>SELL : TEMA(5/9) がデッドクロス（ポジションなし時のみ）</li>
 * </ul>
 *
 * <h2>利確シグナル</h2>
 * <ul>
 *   <li>買建て中 → 利確:
 *     <ol>
 *       <li>(終値 - 前回終値) &lt; 0（価格がマイナスに反転）</li>
 *       <li>TEMAがデッドクロス</li>
 *     </ol>
 *   </li>
 *   <li>売建て中 → 利確:
 *     <ol>
 *       <li>(終値 - 前回終値) &gt; 0（価格がプラスに反転）</li>
 *       <li>TEMAがゴールデンクロス</li>
 *     </ol>
 *   </li>
 * </ul>
 *
 * <p>途転なし。利確後は次のクロスシグナルで新規建てする。
 * GC→DC→GC と連続した場合は自然と途転に近い形になる。
 *
 * <h2>損切りライン</h2>
 * <p>なし（利確条件のみ）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TradingStrategy {

  private final AppProperties props;
  private final TradeSignalRepository signalRepository;

  /**
   * 指定シンボルの直近データから全指標を計算し、TradeSignal を生成・保存して返す。
   *
   * <p>{@link TradeSignal#getFinalSignal()} の意味:
   * <ul>
   *   <li>BUY  : ポジションなし→買建て新規 / 売建て中→利確</li>
   *   <li>SELL : ポジションなし→売建て新規 / 買建て中→利確</li>
   *   <li>HOLD : 何もしない</li>
   * </ul>
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

    // --- RSI（DB記録用）---
    BigDecimal rsiVal = RsiCalculator.calculate(closes, ind.getRsiPeriod());
    Signal rsiSignal  = toRsiSignal(rsiVal, ind);

    // --- MACD（DB記録用）---
    MacdCalculator.MacdResult macdResult = MacdCalculator.calculate(
        closes, ind.getMacdFastPeriod(), ind.getMacdSlowPeriod(), ind.getMacdSignalPeriod());
    CrossSignal macdCross = macdResult == null ? null
        : CrossSignal.valueOf(macdResult.getCross().name());

    // --- DMI（DB記録用）---
    DmiCalculator.DmiResult dmiResult = DmiCalculator.calculate(
        highs, lows, closes, ind.getDmiPeriod(), ind.getAdxPeriod());
    Signal dmiSignal = toDmiSignal(dmiResult, ind);

    // --- RCI（DB記録用）---
    BigDecimal rciVal = RciCalculator.calculate(closes, ind.getRciPeriod());
    Signal rciSignal  = toRciSignal(rciVal, ind);

    // --- TEMA（売買判定の中心指標）---
    TemaCalculator.TemaResult temaResult = TemaCalculator.calculatePair(
        closes, ind.getTemaFastPeriod(), ind.getTemaSlowPeriod());
    CrossSignal temaCross = temaResult == null ? null
        : CrossSignal.valueOf(temaResult.getCross().name());

    // --- 前回・前々回 TEMA_FAST（利確の反転判定用）---
    // 直前シグナルから前回 TEMA_FAST を取得
    // 前々回は trade_signal から2件目を取得
    List<java.util.Map<String, Object>> prevTemaRows =
        signalRepository.fetchPrevTemaFast(symbol, interval, signalTime, 2);
    BigDecimal prevTemaFast     = prevTemaRows.size() > 0
        ? (BigDecimal) prevTemaRows.get(0).get("tema_fast") : null;
    BigDecimal prevPrevTemaFast = prevTemaRows.size() > 1
        ? (BigDecimal) prevTemaRows.get(1).get("tema_fast") : null;

    // --- EMA トレンドフィルター ---
    int emaPeriod = ind.getEmaTrendPeriod();
    BigDecimal emaVal = emaPeriod > 0
        ? EmaCalculator.calculate(closes, emaPeriod) : null;
    BigDecimal temaFastVal = temaResult == null ? null : temaResult.getTemaFast();

    boolean emaAbove = emaVal != null && temaFastVal != null
        && temaFastVal.compareTo(emaVal) > 0; // TEMA_FAST > EMA
    boolean emaBelow = emaVal != null && temaFastVal != null
        && temaFastVal.compareTo(emaVal) < 0; // TEMA_FAST < EMA

    boolean reverse = ind.isEmaTrendReverse();
    // フィルターなし（emaVal==null）の場合は両方 true にして制限なし
    boolean emaBullish, emaBearish;
    if (emaVal == null || temaFastVal == null) {
      emaBullish = true;
      emaBearish = true;
    } else if (reverse) {
      // 逆張り: TEMA_FAST > EMA → SELL 許可 / TEMA_FAST < EMA → BUY 許可
      emaBullish = emaBelow;  // BUY 許可は TEMA_FAST < EMA の時
      emaBearish = emaAbove;  // SELL 許可は TEMA_FAST > EMA の時
    } else {
      // 順張り: TEMA_FAST > EMA → BUY 許可 / TEMA_FAST < EMA → SELL 許可
      emaBullish = emaAbove;
      emaBearish = emaBelow;
    }

    // --- finalSignal 判定 ---
    Signal finalSignal = calcFinalSignal(
        temaCross, temaFastVal,
        prevTemaFast, prevPrevTemaFast,
        emaBullish, emaBearish);

    // シグナル反転（逆張りモード）
    if (ind.isSignalReverse() && finalSignal != Signal.HOLD) {
      finalSignal = finalSignal == Signal.BUY ? Signal.SELL : Signal.BUY;
      log.debug("シグナル反転: {} → {}", finalSignal == Signal.BUY ? Signal.SELL : Signal.BUY, finalSignal);
    }

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
        .emaBullish(emaBullish)
        .emaBearish(emaBearish)
        .finalSignal(finalSignal)
        .build();

    signalRepository.save(ts);

    log.info("シグナル判定 symbol={} price={} temaFast={} ema={} emaBullish={} TEMA_CROSS={} FINAL={}",
        symbol, nowPrice, temaFastVal, emaVal, emaBullish, temaCross, finalSignal);

    return ts;
  }

  // -----------------------------------------------------------------------
  // Private — シグナル判定
  // -----------------------------------------------------------------------

  /**
   * finalSignal を判定する。
   *
   * <p>BUY を返す条件（OR）:
   * <ul>
   *   <li>TEMAがゴールデンクロス</li>
   *   <li>TEMA_FAST の変化がマイナスに反転（前々回→前回がプラス かつ 前回→今回がマイナス）
   *       → 売建て中の利確タイミング</li>
   * </ul>
   *
   * <p>SELL を返す条件（OR）:
   * <ul>
   *   <li>TEMAがデッドクロス</li>
   *   <li>TEMA_FAST の変化がプラスに反転（前々回→前回がマイナス かつ 前回→今回がプラス）
   *       → 買建て中の利確タイミング</li>
   * </ul>
   */
  private Signal calcFinalSignal(CrossSignal temaCross,
      BigDecimal nowTemaFast, BigDecimal prevTemaFast, BigDecimal prevPrevTemaFast,
      boolean emaBullish, boolean emaBearish) {

    boolean temaGolden = temaCross == CrossSignal.GOLDEN;
    boolean temaDead   = temaCross == CrossSignal.DEAD;

    // TEMA_FAST の変化方向の反転判定
    boolean temaFlipToPlus  = false; // マイナス→プラスに反転 → BUY（売建て利確）
    boolean temaFlipToMinus = false; // プラス→マイナスに反転 → SELL（買建て利確）

    if (nowTemaFast != null && prevTemaFast != null && prevPrevTemaFast != null) {
      int diff1Sign = prevTemaFast.compareTo(prevPrevTemaFast);
      int diff2Sign = nowTemaFast.compareTo(prevTemaFast);
      temaFlipToPlus  = diff1Sign < 0 && diff2Sign > 0;
      temaFlipToMinus = diff1Sign > 0 && diff2Sign < 0;
    }

    // EMA トレンドフィルター適用:
    // 新規エントリー方向のみ制限（利確は制限しない）
    // ① 符号反転 → 利確なので制限なし
    // ② GC/DC → 新規建てトリガーなのでトレンド方向と一致する場合のみ許可
    //   GC（BUY）: emaBullish でない場合は HOLD
    //   DC（SELL）: emaBearish でない場合は HOLD

    // 符号反転を優先
    if (temaFlipToPlus)  return Signal.BUY;
    if (temaFlipToMinus) return Signal.SELL;

    // クロスシグナル: EMA トレンドフィルターを適用
    if (temaGolden) {
      if (!emaBullish) {
        log.debug("GC を EMA フィルターでスキップ（下降トレンド中）: temaFast={}", nowTemaFast);
        return Signal.HOLD;
      }
      return Signal.BUY;
    }
    if (temaDead) {
      if (!emaBearish) {
        log.debug("DC を EMA フィルターでスキップ（上昇トレンド中）: temaFast={}", nowTemaFast);
        return Signal.HOLD;
      }
      return Signal.SELL;
    }

    return Signal.HOLD;
  }

  // -----------------------------------------------------------------------
  // Private — 個別指標シグナル判定（DB記録用）
  // -----------------------------------------------------------------------

  private Signal toRsiSignal(BigDecimal rsi, AppProperties.Indicator ind) {
    if (rsi == null) return null;
    if (rsi.doubleValue() <= ind.getRsiOversold())   return Signal.BUY;
    if (rsi.doubleValue() >= ind.getRsiOverbought()) return Signal.SELL;
    return Signal.HOLD;
  }

  private Signal toDmiSignal(DmiCalculator.DmiResult result, AppProperties.Indicator ind) {
    if (result == null) return null;
    if (result.getAdx().doubleValue() < ind.getDmiAdxThreshold()) return Signal.HOLD;
    if (result.getMinusDi().compareTo(result.getPlusDi()) > 0) return Signal.BUY;
    if (result.getPlusDi().compareTo(result.getMinusDi()) > 0) return Signal.SELL;
    return Signal.HOLD;
  }

  private Signal toRciSignal(BigDecimal rci, AppProperties.Indicator ind) {
    if (rci == null) return null;
    if (rci.doubleValue() <= ind.getRciOversold())   return Signal.BUY;
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
