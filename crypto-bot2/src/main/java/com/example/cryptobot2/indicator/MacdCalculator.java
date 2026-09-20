package com.example.cryptobot2.indicator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * MACD（Moving Average Convergence Divergence）計算クラス。
 *
 * <p>EMA(fast) - EMA(slow) = MACD line
 * <p>EMA(MACD, signal期間) = Signal line
 * <p>MACD - Signal = Histogram
 */
@Slf4j
public class MacdCalculator {

  private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

  private MacdCalculator() {}

  /** MACD 計算結果 */
  @Data
  @Builder
  public static class MacdResult {
    private BigDecimal macd;
    private BigDecimal signal;
    private BigDecimal histogram;
    /** 前回との比較によるクロス判定（null = 前回データなし） */
    private CrossType cross;

    public enum CrossType {
      GOLDEN, DEAD, HOLD
    }
  }

  /**
   * MACD を計算して返す。
   *
   * @param closes       終値リスト（古い順）
   * @param fastPeriod   短期 EMA 期間
   * @param slowPeriod   長期 EMA 期間
   * @param signalPeriod シグナル EMA 期間
   * @return MacdResult、データ不足時は null
   */
  public static MacdResult calculate(
      List<BigDecimal> closes, int fastPeriod, int slowPeriod, int signalPeriod) {

    int minRequired = slowPeriod + signalPeriod - 1;
    if (closes == null || closes.size() < minRequired) {
      log.debug("MACD計算データ不足: size={}, required={}", closes == null ? 0 : closes.size(), minRequired);
      return null;
    }

    List<BigDecimal> fastEmas = calcEmaList(closes, fastPeriod);
    List<BigDecimal> slowEmas = calcEmaList(closes, slowPeriod);

    // MACD line = fast EMA - slow EMA（slow EMA が計算できる区間のみ）
    int offset = fastPeriod - slowPeriod; // 通常負値（slow > fast）
    List<BigDecimal> macdLine = new ArrayList<>();
    for (int i = 0; i < slowEmas.size(); i++) {
      int fastIdx = i + (slowPeriod - fastPeriod);
      if (fastIdx < 0 || fastIdx >= fastEmas.size()) continue;
      macdLine.add(fastEmas.get(fastIdx).subtract(slowEmas.get(i)));
    }

    if (macdLine.size() < signalPeriod) {
      log.debug("MACDシグナル計算データ不足: macdLine.size={}, signalPeriod={}", macdLine.size(), signalPeriod);
      return null;
    }

    List<BigDecimal> signalLine = calcEmaList(macdLine, signalPeriod);

    BigDecimal macdVal = macdLine.getLast();
    BigDecimal signalVal = signalLine.getLast();
    BigDecimal histogram = macdVal.subtract(signalVal).setScale(8, RoundingMode.HALF_UP);

    // クロス判定（1本前と比較）
    MacdResult.CrossType cross = MacdResult.CrossType.HOLD;
    if (macdLine.size() >= 2 && signalLine.size() >= 2) {
      BigDecimal prevMacd = macdLine.get(macdLine.size() - 2);
      BigDecimal prevSignal = signalLine.get(signalLine.size() - 2);
      boolean prevBelow = prevMacd.compareTo(prevSignal) < 0;
      boolean currAbove = macdVal.compareTo(signalVal) > 0;
      boolean prevAbove = prevMacd.compareTo(prevSignal) > 0;
      boolean currBelow = macdVal.compareTo(signalVal) < 0;

      if (prevBelow && currAbove) {
        cross = MacdResult.CrossType.GOLDEN;
      } else if (prevAbove && currBelow) {
        cross = MacdResult.CrossType.DEAD;
      }
    }

    return MacdResult.builder()
        .macd(macdVal.setScale(8, RoundingMode.HALF_UP))
        .signal(signalVal.setScale(8, RoundingMode.HALF_UP))
        .histogram(histogram)
        .cross(cross)
        .build();
  }

  /** EMA リストを計算して返す */
  public static List<BigDecimal> calcEmaList(List<BigDecimal> values, int period) {
    List<BigDecimal> emas = new ArrayList<>();
    if (values.size() < period) return emas;

    // 初期 SMA
    BigDecimal sum = BigDecimal.ZERO;
    for (int i = 0; i < period; i++) {
      sum = sum.add(values.get(i));
    }
    BigDecimal ema = sum.divide(BigDecimal.valueOf(period), MC);
    emas.add(ema);

    // 乗数 k = 2 / (period + 1)
    BigDecimal k = BigDecimal.valueOf(2.0 / (period + 1));

    for (int i = period; i < values.size(); i++) {
      ema = values.get(i).multiply(k, MC).add(ema.multiply(BigDecimal.ONE.subtract(k), MC));
      emas.add(ema);
    }

    return emas;
  }
}
