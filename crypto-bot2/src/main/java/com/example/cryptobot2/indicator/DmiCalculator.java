package com.example.cryptobot2.indicator;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import lombok.Builder;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;

/**
 * DMI（Directional Movement Index）/ ADX 計算クラス。
 *
 * <p>+DI、-DI、ADX を Wilder の平滑移動平均で計算する。
 */
@Slf4j
public class DmiCalculator {

  private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

  private DmiCalculator() {}

  /** DMI 計算結果 */
  @Data
  @Builder
  public static class DmiResult {
    private BigDecimal plusDi;
    private BigDecimal minusDi;
    private BigDecimal adx;
  }

  /**
   * DMI / ADX を計算して返す。
   *
   * @param highs     高値リスト（古い順）
   * @param lows      安値リスト（古い順）
   * @param closes    終値リスト（古い順）
   * @param period    DI 計算期間（+DI / -DI の Wilder 平滑化期間）
   * @param adxPeriod ADX 平滑化期間（DX を平滑化する期間、デフォルト 9）
   * @return DmiResult、データ不足時は null
   */
  public static DmiResult calculate(
      List<BigDecimal> highs, List<BigDecimal> lows, List<BigDecimal> closes,
      int period, int adxPeriod) {

    int minRequired = period + adxPeriod;
    if (highs == null || highs.size() < minRequired) {
      log.debug("DMI計算データ不足: size={}, required={}", highs == null ? 0 : highs.size(), minRequired);
      return null;
    }

    int n = highs.size();

    // TR、+DM、-DM を計算
    BigDecimal[] tr = new BigDecimal[n];
    BigDecimal[] plusDm = new BigDecimal[n];
    BigDecimal[] minusDm = new BigDecimal[n];

    tr[0] = BigDecimal.ZERO;
    plusDm[0] = BigDecimal.ZERO;
    minusDm[0] = BigDecimal.ZERO;

    for (int i = 1; i < n; i++) {
      BigDecimal highDiff = highs.get(i).subtract(highs.get(i - 1));
      BigDecimal lowDiff = lows.get(i - 1).subtract(lows.get(i));

      // +DM / -DM
      plusDm[i] = (highDiff.compareTo(lowDiff) > 0 && highDiff.compareTo(BigDecimal.ZERO) > 0)
          ? highDiff : BigDecimal.ZERO;
      minusDm[i] = (lowDiff.compareTo(highDiff) > 0 && lowDiff.compareTo(BigDecimal.ZERO) > 0)
          ? lowDiff : BigDecimal.ZERO;

      // TR = max(H-L, |H-PC|, |L-PC|)
      BigDecimal hl = highs.get(i).subtract(lows.get(i)).abs();
      BigDecimal hpc = highs.get(i).subtract(closes.get(i - 1)).abs();
      BigDecimal lpc = lows.get(i).subtract(closes.get(i - 1)).abs();
      tr[i] = hl.max(hpc).max(lpc);
    }

    // +DI / -DI: Wilder 平滑化（period）
    BigDecimal smTr = BigDecimal.ZERO;
    BigDecimal smPlusDm = BigDecimal.ZERO;
    BigDecimal smMinusDm = BigDecimal.ZERO;
    BigDecimal periodBd = BigDecimal.valueOf(period);

    for (int i = 1; i <= period; i++) {
      smTr = smTr.add(tr[i]);
      smPlusDm = smPlusDm.add(plusDm[i]);
      smMinusDm = smMinusDm.add(minusDm[i]);
    }

    BigDecimal[] dx = new BigDecimal[n];

    for (int i = period; i < n; i++) {
      if (i > period) {
        smTr = smTr.subtract(smTr.divide(periodBd, MC)).add(tr[i]);
        smPlusDm = smPlusDm.subtract(smPlusDm.divide(periodBd, MC)).add(plusDm[i]);
        smMinusDm = smMinusDm.subtract(smMinusDm.divide(periodBd, MC)).add(minusDm[i]);
      }

      BigDecimal plusDi = smTr.compareTo(BigDecimal.ZERO) == 0
          ? BigDecimal.ZERO
          : smPlusDm.divide(smTr, MC).multiply(HUNDRED);
      BigDecimal minusDi = smTr.compareTo(BigDecimal.ZERO) == 0
          ? BigDecimal.ZERO
          : smMinusDm.divide(smTr, MC).multiply(HUNDRED);

      BigDecimal diSum = plusDi.add(minusDi);
      dx[i] = diSum.compareTo(BigDecimal.ZERO) == 0
          ? BigDecimal.ZERO
          : plusDi.subtract(minusDi).abs().divide(diSum, MC).multiply(HUNDRED);
    }

    // ADX = DX の Wilder 平滑移動平均（adxPeriod で平滑化）
    BigDecimal adxPeriodBd = BigDecimal.valueOf(adxPeriod);
    BigDecimal adx = BigDecimal.ZERO;
    int adxStart = period; // DX が有効になる最初のインデックス

    // ADX の初期値: adxStart から adxPeriod 本分の DX を単純平均
    for (int i = adxStart; i < adxStart + adxPeriod && i < n; i++) {
      adx = adx.add(dx[i] == null ? BigDecimal.ZERO : dx[i]);
    }
    adx = adx.divide(adxPeriodBd, MC);

    // ADX の Wilder 平滑化
    for (int i = adxStart + adxPeriod; i < n; i++) {
      adx = adx.multiply(BigDecimal.valueOf(adxPeriod - 1), MC)
          .add(dx[i]).divide(adxPeriodBd, MC);
    }

    // 最終 +DI / -DI
    BigDecimal finalPlusDi = smTr.compareTo(BigDecimal.ZERO) == 0
        ? BigDecimal.ZERO
        : smPlusDm.divide(smTr, MC).multiply(HUNDRED);
    BigDecimal finalMinusDi = smTr.compareTo(BigDecimal.ZERO) == 0
        ? BigDecimal.ZERO
        : smMinusDm.divide(smTr, MC).multiply(HUNDRED);

    return DmiResult.builder()
        .plusDi(finalPlusDi.setScale(4, RoundingMode.HALF_UP))
        .minusDi(finalMinusDi.setScale(4, RoundingMode.HALF_UP))
        .adx(adx.setScale(4, RoundingMode.HALF_UP))
        .build();
  }
}
