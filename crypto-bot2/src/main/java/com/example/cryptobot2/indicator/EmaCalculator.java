package com.example.cryptobot2.indicator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * EMA（指数移動平均）計算クラス。
 *
 * <p>計算式: EMA = 前回EMA × (1 - k) + 今回終値 × k
 * ただし k = 2 / (period + 1)
 */
@Slf4j
public class EmaCalculator {

  private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

  private EmaCalculator() {}

  /**
   * EMA を計算して最新値を返す。
   *
   * @param closes 終値リスト（古い順）
   * @param period EMA 期間
   * @return EMA 値。データ不足の場合は null
   */
  public static BigDecimal calculate(List<BigDecimal> closes, int period) {
    if (closes == null || closes.size() < period) {
      log.debug("EMA計算データ不足: size={} required={}", closes == null ? 0 : closes.size(), period);
      return null;
    }

    BigDecimal k = BigDecimal.valueOf(2.0 / (period + 1));

    // 初期値: 最初の period 本の単純平均
    BigDecimal ema = BigDecimal.ZERO;
    for (int i = 0; i < period; i++) {
      ema = ema.add(closes.get(i));
    }
    ema = ema.divide(BigDecimal.valueOf(period), MC);

    // 以降は EMA で更新
    for (int i = period; i < closes.size(); i++) {
      ema = closes.get(i).multiply(k, MC)
          .add(ema.multiply(BigDecimal.ONE.subtract(k), MC));
    }

    return ema.setScale(3, RoundingMode.HALF_UP);
  }
}
