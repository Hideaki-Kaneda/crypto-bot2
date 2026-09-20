package com.example.cryptobot2.indicator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * RSI（Relative Strength Index）計算クラス。
 *
 * <p>Wilder の平滑移動平均（SMMA）方式で計算する。
 * データ不足時は null を返す。
 */
@Slf4j
public class RsiCalculator {

  private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private RsiCalculator() {}

  /**
   * 終値リストから RSI を計算して返す。
   *
   * @param closes 終値リスト（古い順）
   * @param period RSI 期間
   * @return RSI 値（0〜100）、データ不足時は null
   */
  public static BigDecimal calculate(List<BigDecimal> closes, int period) {
    if (closes == null || closes.size() < period + 1) {
      log.debug("RSI計算データ不足: size={}, period={}", closes == null ? 0 : closes.size(), period);
      return null;
    }

    // 初期平均計算（最初の period 本分）
    BigDecimal avgGain = BigDecimal.ZERO;
    BigDecimal avgLoss = BigDecimal.ZERO;

    for (int i = 1; i <= period; i++) {
      BigDecimal diff = closes.get(i).subtract(closes.get(i - 1));
      if (diff.compareTo(BigDecimal.ZERO) > 0) {
        avgGain = avgGain.add(diff);
      } else {
        avgLoss = avgLoss.add(diff.abs());
      }
    }

    BigDecimal periodBd = BigDecimal.valueOf(period);
    avgGain = avgGain.divide(periodBd, MC);
    avgLoss = avgLoss.divide(periodBd, MC);

    // Wilder の平滑移動平均で残りを計算
    for (int i = period + 1; i < closes.size(); i++) {
      BigDecimal diff = closes.get(i).subtract(closes.get(i - 1));
      BigDecimal gain = diff.compareTo(BigDecimal.ZERO) > 0 ? diff : BigDecimal.ZERO;
      BigDecimal loss = diff.compareTo(BigDecimal.ZERO) < 0 ? diff.abs() : BigDecimal.ZERO;

      avgGain = avgGain.multiply(BigDecimal.valueOf(period - 1), MC)
          .add(gain).divide(periodBd, MC);
      avgLoss = avgLoss.multiply(BigDecimal.valueOf(period - 1), MC)
          .add(loss).divide(periodBd, MC);
    }

    if (avgLoss.compareTo(BigDecimal.ZERO) == 0) {
      return HUNDRED;
    }

    BigDecimal rs = avgGain.divide(avgLoss, MC);
    BigDecimal rsi = HUNDRED.subtract(
        HUNDRED.divide(BigDecimal.ONE.add(rs), MC));

    return rsi.setScale(4, RoundingMode.HALF_UP);
  }
}
