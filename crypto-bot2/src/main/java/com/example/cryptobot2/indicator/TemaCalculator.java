package com.example.cryptobot2.indicator;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * TEMA（Triple Exponential Moving Average）計算クラス。
 *
 * <p>計算式: TEMA = 3 * EMA1 - 3 * EMA2 + EMA3
 * <ul>
 *   <li>EMA1 = EMA(close, period)
 *   <li>EMA2 = EMA(EMA1, period)
 *   <li>EMA3 = EMA(EMA2, period)
 * </ul>
 *
 * <p>EMA より応答速度が速く、ノイズを抑えながらトレンドを追う。
 */
@Slf4j
public class TemaCalculator {

  private TemaCalculator() {}

  /** TEMA ペアの計算結果（短期・長期） */
  @Data
  @Builder
  public static class TemaResult {
    private BigDecimal temaFast;
    private BigDecimal temaSlow;
    private CrossSignal cross;

    public enum CrossSignal {
      GOLDEN, DEAD, HOLD
    }
  }

  /**
   * 終値リストから TEMA を計算して返す。
   *
   * @param closes 終値リスト（古い順）
   * @param period TEMA 期間
   * @return TEMA 値、データ不足時は null
   */
  public static BigDecimal calculate(List<BigDecimal> closes, int period) {
    // TEMA には EMA を3重に適用するため最低 3*(period-1)+1 本必要
    int minRequired = 3 * (period - 1) + 1;
    if (closes == null || closes.size() < minRequired) {
      log.debug("TEMA計算データ不足: size={}, period={}, required={}",
          closes == null ? 0 : closes.size(), period, minRequired);
      return null;
    }

    List<BigDecimal> ema1 = MacdCalculator.calcEmaList(closes, period);
    List<BigDecimal> ema2 = MacdCalculator.calcEmaList(ema1, period);
    List<BigDecimal> ema3 = MacdCalculator.calcEmaList(ema2, period);

    if (ema3.isEmpty()) {
      return null;
    }

    // TEMA = 3*EMA1 - 3*EMA2 + EMA3
    BigDecimal e1 = ema1.getLast();
    BigDecimal e2 = ema2.getLast();
    BigDecimal e3 = ema3.getLast();

    return e1.multiply(BigDecimal.valueOf(3))
        .subtract(e2.multiply(BigDecimal.valueOf(3)))
        .add(e3)
        .setScale(8, RoundingMode.HALF_UP);
  }

  /**
   * 短期・長期 TEMA とクロスシグナルをまとめて計算して返す。
   *
   * @param closes     終値リスト（古い順）
   * @param fastPeriod 短期 TEMA 期間
   * @param slowPeriod 長期 TEMA 期間
   * @return TemaResult、データ不足時は null
   */
  public static TemaResult calculatePair(
      List<BigDecimal> closes, int fastPeriod, int slowPeriod) {

    BigDecimal fast = calculate(closes, fastPeriod);
    BigDecimal slow = calculate(closes, slowPeriod);

    if (fast == null || slow == null) {
      return null;
    }

    // クロス判定（1本前と比較）
    TemaResult.CrossSignal cross = TemaResult.CrossSignal.HOLD;
    if (closes.size() >= 2) {
      List<BigDecimal> prev = closes.subList(0, closes.size() - 1);
      BigDecimal prevFast = calculate(prev, fastPeriod);
      BigDecimal prevSlow = calculate(prev, slowPeriod);

      if (prevFast != null && prevSlow != null) {
        boolean prevBelow = prevFast.compareTo(prevSlow) < 0;
        boolean currAbove = fast.compareTo(slow) > 0;
        boolean prevAbove = prevFast.compareTo(prevSlow) > 0;
        boolean currBelow = fast.compareTo(slow) < 0;

        if (prevBelow && currAbove) {
          cross = TemaResult.CrossSignal.GOLDEN;
        } else if (prevAbove && currBelow) {
          cross = TemaResult.CrossSignal.DEAD;
        }
      }
    }

    return TemaResult.builder()
        .temaFast(fast)
        .temaSlow(slow)
        .cross(cross)
        .build();
  }
}
