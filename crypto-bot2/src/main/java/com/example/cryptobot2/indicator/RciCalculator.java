package com.example.cryptobot2.indicator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import lombok.extern.slf4j.Slf4j;

/**
 * RCI（Rank Correlation Index / 順位相関指数）計算クラス。
 *
 * <p>時間の順位と価格の順位の相関係数を計算する。
 * <ul>
 *   <li>+80 以上: 強い上昇トレンド → SELL シグナル（過熱）
 *   <li>-80 以下: 強い下降トレンド → BUY シグナル（売られすぎ）
 *   <li>その他: HOLD
 * </ul>
 *
 * <p>計算式: RCI = (1 - 6 * Σd² / (n * (n²-1))) * 100
 * <p>d = 時間順位 - 価格順位、n = 期間
 */
@Slf4j
public class RciCalculator {

  private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

  private RciCalculator() {}

  /**
   * 終値リストから RCI を計算して返す。
   *
   * @param closes 終値リスト（古い順）
   * @param period RCI 期間
   * @return RCI 値（-100〜+100）、データ不足時は null
   */
  public static BigDecimal calculate(List<BigDecimal> closes, int period) {
    if (closes == null || closes.size() < period) {
      log.debug("RCI計算データ不足: size={}, period={}", closes == null ? 0 : closes.size(), period);
      return null;
    }

    // 直近 period 本を取得
    List<BigDecimal> target = closes.subList(closes.size() - period, closes.size());

    // 価格順位を計算（降順: 最高値 = 1）
    int[] priceRanks = calcRanks(target, false);

    // d² の合計: 時間順位(古い=period, 新しい=1) vs 価格順位
    long sumDSquared = 0;
    for (int i = 0; i < period; i++) {
      int timeRank = period - i; // 古いほど順位が高い（古い=period, 新しい=1）
      int priceRank = priceRanks[i];
      int d = timeRank - priceRank;
      sumDSquared += (long) d * d;
    }

    // RCI = (1 - 6 * Σd² / (n * (n²-1))) * 100
    BigDecimal n = BigDecimal.valueOf(period);
    BigDecimal numerator = BigDecimal.valueOf(6L * sumDSquared);
    BigDecimal denominator = n.multiply(n.pow(2).subtract(BigDecimal.ONE));

    BigDecimal rci = BigDecimal.ONE
        .subtract(numerator.divide(denominator, MC))
        .multiply(BigDecimal.valueOf(100))
        .setScale(4, RoundingMode.HALF_UP);

    return rci;
  }

  /**
   * 値リストの順位配列を返す。
   * 同値の場合は平均順位を使用する。
   *
   * @param values  値リスト
   * @param ascending true=昇順(小さい=1), false=降順(大きい=1)
   * @return 各要素の順位（1始まり）
   */
  private static int[] calcRanks(List<BigDecimal> values, boolean ascending) {
    int n = values.size();
    int[] ranks = new int[n];

    for (int i = 0; i < n; i++) {
      int rank = 1;
      for (int j = 0; j < n; j++) {
        if (i == j) continue;
        int cmp = values.get(j).compareTo(values.get(i));
        if (ascending ? cmp < 0 : cmp > 0) {
          rank++;
        }
      }
      ranks[i] = rank;
    }

    return ranks;
  }
}
