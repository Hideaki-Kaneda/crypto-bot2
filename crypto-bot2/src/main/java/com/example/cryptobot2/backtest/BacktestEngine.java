package com.example.cryptobot2.backtest;

import com.example.cryptobot2.client.GmoCoinApiClient;
import com.example.cryptobot2.config.AppProperties;
import com.example.cryptobot2.indicator.DmiCalculator;
import com.example.cryptobot2.indicator.MacdCalculator;
import com.example.cryptobot2.indicator.RciCalculator;
import com.example.cryptobot2.indicator.RsiCalculator;
import com.example.cryptobot2.indicator.TemaCalculator;
import com.example.cryptobot2.model.BacktestResult;
import com.example.cryptobot2.model.KlineRecord;
import com.example.cryptobot2.model.Position.CloseReason;
import com.example.cryptobot2.model.TradeHistory.Side;
import com.example.cryptobot2.model.TradeSignal;
import com.example.cryptobot2.model.TradeSignal.CrossSignal;
import com.example.cryptobot2.model.TradeSignal.Signal;
import com.example.cryptobot2.repository.BacktestRepository;
import com.example.cryptobot2.repository.BacktestResultRepository;
import com.example.cryptobot2.repository.KlineRepository;
import com.example.cryptobot2.repository.TradeSignalRepository;
import com.example.cryptobot2.util.ScheduleGuard;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * バックテストエンジン。
 *
 * <p>kline_data を使って指定期間の売買シミュレーションを行う。
 * データが不足する日付は GMO API から自動取得して kline_data に保存する。
 *
 * <p>売買ロジックは TradingStrategy と同一の条件を使用する。
 * ただし DB への trade_signal の書き込みは行わず、バックテスト専用テーブルに保存する。
 *
 * <p>起動: {@code java -jar crypto-bot2.jar --backtest}
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BacktestEngine {

  private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);
  private static final int CONSECUTIVE_REVERSE_COUNT = 3;
  private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd");
  private static final DateTimeFormatter YEAR_FMT = DateTimeFormatter.ofPattern("yyyy");
  private static final List<String> DAILY_OR_LONGER =
      List.of("1day", "1week", "1month");

  private final AppProperties props;
  private final GmoCoinApiClient apiClient;
  private final KlineRepository klineRepository;
  private final BacktestRepository backtestRepository;
  private final BacktestResultRepository resultRepository;
  private final TradeSignalRepository signalRepository;

  /**
   * バックテストを実行する。
   *
   * @param startDateStr 開始日（yyyy-MM-dd）
   * @param endDateStr   終了日（yyyy-MM-dd）
   */
  public void run(String startDateStr, String endDateStr) {
    String symbol   = props.getKline().getSymbols().get(0);
    String interval = props.getKline().getInterval();
    BigDecimal initialBalance = props.getBacktest().getInitialBalanceJpy();

    LocalDate startDate = LocalDate.parse(startDateStr);
    LocalDate endDate   = LocalDate.parse(endDateStr);

    log.info("=== バックテスト開始 symbol={} interval={} {} 〜 {} ===",
        symbol, interval, startDate, endDate);

    // Step1: データ補完（kline_dataにないデータをAPIから取得）
    fetchMissingKlineData(symbol, interval, startDate, endDate);

    // Step2: 対象期間の kline_data を全件取得
    // GMO の date=startDate は JST startDate 06:00 〜 (endDate+1) 05:55 のデータを含む
    // UTC に変換: JST 06:00 = UTC 前日 21:00
    OffsetDateTime from = startDate.atTime(6, 0)
        .atOffset(java.time.ZoneOffset.ofHours(9))
        .withOffsetSameInstant(ZoneOffset.UTC);
    OffsetDateTime to = endDate.plusDays(1).atTime(6, 0)
        .atOffset(java.time.ZoneOffset.ofHours(9))
        .withOffsetSameInstant(ZoneOffset.UTC);
    List<KlineRecord> klines = backtestRepository.fetchRange(symbol, interval, from, to);

    if (klines.isEmpty()) {
      log.warn("バックテスト対象データが0件です。期間または設定を確認してください。");
      return;
    }

    log.info("バックテスト対象KLine件数: {}", klines.size());

    // Step3: シミュレーション実行
    SimulationResult sim = simulate(symbol, interval, klines, initialBalance);

    // Step4: 結果集計・保存・出力
    BacktestResult result = buildResult(symbol, interval, startDate, endDate, initialBalance, sim);
    long runId = resultRepository.save(result, sim.trades());

    printSummary(result, runId);
  }

  // -----------------------------------------------------------------------
  // Step1: 不足データの補完
  // -----------------------------------------------------------------------

  private void fetchMissingKlineData(String symbol, String interval,
      LocalDate startDate, LocalDate endDate) {

    log.info("KLineデータ補完チェック開始...");
    int fetched = 0;

    LocalDate cursor = startDate;
    while (!cursor.isAfter(endDate)) {
      if (!backtestRepository.existsByGmoDate(symbol, interval, cursor)) {
        String dateParam = buildDateParam(interval, cursor);
        log.info("データ取得: symbol={} interval={} date={}", symbol, interval, dateParam);
        try {
          List<KlineRecord> records = apiClient.fetchKlines(symbol, interval, dateParam);
          if (!records.isEmpty()) {
            int inserted = klineRepository.saveAll(records);
            fetched += inserted;
            log.info("  → {} 件取得・保存", inserted);
          } else {
            log.info("  → データなし（休場日等）");
          }
          // APIレート制限対策
          Thread.sleep(props.getApi().getRetry().getDelayMs());
        } catch (Exception e) {
          log.warn("  → 取得失敗: {}", e.getMessage());
        }
      }
      // インターバルに応じて日付を進める
      cursor = nextDate(cursor, interval);
    }

    log.info("KLineデータ補完完了: 新規取得 {} 件", fetched);
  }

  private String buildDateParam(String interval, LocalDate date) {
    if (DAILY_OR_LONGER.contains(interval)) {
      return String.valueOf(date.getYear());
    }
    return date.format(DATE_FMT);
  }

  /**
   * インターバルに応じて日付を進める。
   * 1day 以上の足は API が年単位のため年の先頭へジャンプする。
   */
  private LocalDate nextDate(LocalDate current, String interval) {
    return switch (interval) {
      case "1day", "1week", "1month" -> current.withDayOfYear(1).plusYears(1);
      default -> current.plusDays(1);
    };
  }

  // -----------------------------------------------------------------------
  // Step3: シミュレーション
  // -----------------------------------------------------------------------

  private SimulationResult simulate(String symbol, String interval,
      List<KlineRecord> klines, BigDecimal initialBalance) {

    AppProperties.Indicator ind = props.getIndicator();
    AppProperties.Trade trade   = props.getTrade();
    int historySize = ind.getPriceHistorySize();

    List<BacktestResult.Trade> trades = new ArrayList<>();

    // 仮想ポジション管理
    BigDecimal balance     = initialBalance;
    BigDecimal peakBalance = initialBalance;
    BigDecimal maxDrawdown = BigDecimal.ZERO;

    VirtualPosition position = null; // null=ポジションなし
    List<Signal> recentSignals = new ArrayList<>(); // 連続シグナル管理（HOLD除く）

    for (int i = 0; i < klines.size(); i++) {
      KlineRecord current = klines.get(i);
      BigDecimal price    = current.getClose();
      OffsetDateTime time = current.getOpenTime();

      // 指標計算用の直前データを取得（現在足を含まない）
      List<KlineRecord> history = getHistory(klines, i, historySize);
      if (history.size() < 2) continue; // データ不足はスキップ

      List<BigDecimal> closes = history.stream().map(KlineRecord::getClose).toList();
      List<BigDecimal> highs  = history.stream().map(KlineRecord::getHigh).toList();
      List<BigDecimal> lows   = history.stream().map(KlineRecord::getLow).toList();

      // 指標計算
      BigDecimal rsiVal = RsiCalculator.calculate(closes, ind.getRsiPeriod());
      Signal rsiSignal  = toRsiSignal(rsiVal, ind);

      MacdCalculator.MacdResult macdResult = MacdCalculator.calculate(
          closes, ind.getMacdFastPeriod(), ind.getMacdSlowPeriod(), ind.getMacdSignalPeriod());
      CrossSignal macdCross = macdResult == null ? null
          : CrossSignal.valueOf(macdResult.getCross().name());

      DmiCalculator.DmiResult dmiResult = DmiCalculator.calculate(
          highs, lows, closes, ind.getDmiPeriod(), ind.getAdxPeriod());
      Signal dmiSignal = toDmiSignal(dmiResult, ind);

      BigDecimal rciVal = RciCalculator.calculate(closes, ind.getRciPeriod());
      Signal rciSignal  = toRciSignal(rciVal, ind);

      TemaCalculator.TemaResult temaResult = TemaCalculator.calculatePair(
          closes, ind.getTemaFastPeriod(), ind.getTemaSlowPeriod());
      CrossSignal temaCross = temaResult == null ? null
          : CrossSignal.valueOf(temaResult.getCross().name());

      // 前回シグナル（直前の HOLD 以外）
      Signal prevRciSig = null;
      CrossSignal prevMacdCross = null;
      if (!recentSignals.isEmpty()) {
        // recentSignals には前回の値が入っている（後で追加）
      }
      // 直前足の RCI / MACD / TEMA を履歴から再計算
      BigDecimal prevRci = null;
      CrossSignal prevMacd = null;
      CrossSignal prevTema = null;
      if (i > 0) {
        List<KlineRecord> prevHistory = getHistory(klines, i - 1, historySize);
        if (!prevHistory.isEmpty()) {
          List<BigDecimal> prevCloses = prevHistory.stream().map(KlineRecord::getClose).toList();
          prevRci = RciCalculator.calculate(prevCloses, ind.getRciPeriod());
          MacdCalculator.MacdResult pm = MacdCalculator.calculate(prevCloses,
              ind.getMacdFastPeriod(), ind.getMacdSlowPeriod(), ind.getMacdSignalPeriod());
          prevMacd = pm == null ? null : CrossSignal.valueOf(pm.getCross().name());
          TemaCalculator.TemaResult pt = TemaCalculator.calculatePair(
              prevCloses, ind.getTemaFastPeriod(), ind.getTemaSlowPeriod());
          prevTema = pt == null ? null : CrossSignal.valueOf(pt.getCross().name());
        }
      }

      // シグナル判定
      Signal signal = calcSignal(rsiVal, rsiSignal, rciVal, rciSignal, dmiSignal,
          macdCross, temaCross, prevRci, prevMacd, prevTema);

      // 連続シグナルによるドテン判定
      if (signal != Signal.HOLD) {
        signal = applyConsecutiveReverse(recentSignals, signal);
        recentSignals.add(signal);
        if (recentSignals.size() > CONSECUTIVE_REVERSE_COUNT) {
          recentSignals.remove(0);
        }
      }

      // trade_signal に保存（バックテスト確認用）
      AppProperties.Indicator ind2 = ind; // effectively final
      TradeSignal ts = TradeSignal.builder()
          .symbol(symbol)
          .signalTime(time)
          .price(price)
          .rsi(rsiVal)
          .rsiPeriod(ind2.getRsiPeriod())
          .rsiSignal(rsiSignal)
          .macd(macdResult == null ? null : macdResult.getMacd())
          .macdSignal(macdResult == null ? null : macdResult.getSignal())
          .macdHistogram(macdResult == null ? null : macdResult.getHistogram())
          .macdFast(ind2.getMacdFastPeriod())
          .macdSlow(ind2.getMacdSlowPeriod())
          .macdSignalPeriod(ind2.getMacdSignalPeriod())
          .macdCross(macdCross)
          .dmiPlus(dmiResult == null ? null : dmiResult.getPlusDi())
          .dmiMinus(dmiResult == null ? null : dmiResult.getMinusDi())
          .adx(dmiResult == null ? null : dmiResult.getAdx())
          .dmiPeriod(ind2.getDmiPeriod())
          .dmiSignal(dmiSignal)
          .rci(rciVal)
          .rciPeriod(ind2.getRciPeriod())
          .rciSignal(rciSignal)
          .temaFast(temaResult == null ? null : temaResult.getTemaFast())
          .temaFastPeriod(ind2.getTemaFastPeriod())
          .temaSlow(temaResult == null ? null : temaResult.getTemaSlow())
          .temaSlowPeriod(ind2.getTemaSlowPeriod())
          .temaCross(temaCross)
          .finalSignal(signal)
          .build();
      signalRepository.save(ts);

      // 損切り・利確チェック（最優先）
      if (position != null) {
        double changePercent = calcChangePercent(position.openPrice(), price, position.side());
        boolean stopped = false;

        if (changePercent <= -Math.abs(trade.getStopLossPercent())) {
          BigDecimal profit = calcProfit(position, price);
          balance = balance.add(profit);
          trades.add(buildTrade(symbol, time, closeSide(position.side()),
              price, trade.getSize(), profit, balance, CloseReason.STOP_LOSS, "損切り"));
          log.debug("[BT] 損切り: time={} side={} openPrice={} closePrice={} profit={}",
              time, position.side(), position.openPrice(), price, profit);
          position = null;
          stopped = true;
        } else if (changePercent >= trade.getTakeProfitPercent()) {
          BigDecimal profit = calcProfit(position, price);
          balance = balance.add(profit);
          trades.add(buildTrade(symbol, time, closeSide(position.side()),
              price, trade.getSize(), profit, balance, CloseReason.TAKE_PROFIT, "利確"));
          log.debug("[BT] 利確: time={} side={} openPrice={} closePrice={} profit={}",
              time, position.side(), position.openPrice(), price, profit);
          position = null;
          stopped = true;
        }

        // ドローダウン更新
        if (balance.compareTo(peakBalance) > 0) peakBalance = balance;
        BigDecimal drawdown = peakBalance.subtract(balance);
        if (drawdown.compareTo(maxDrawdown) > 0) maxDrawdown = drawdown;

        if (stopped) continue;
      }

      // シグナルによる売買実行
      if (signal == Signal.HOLD) continue;

      if (position == null) {
        // 新規建て（BUY or SELL）
        position = new VirtualPosition(signal == Signal.BUY ? Side.BUY : Side.SELL,
            price, time, trade.getSize());
        trades.add(buildTrade(symbol, time,
            signal == Signal.BUY ? Side.BUY : Side.SELL,
            price, trade.getSize(), null, balance, null,
            "新規" + (signal == Signal.BUY ? "買" : "売")));
        log.debug("[BT] 新規{}: time={} price={}", signal, time, price);

      } else {
        // ドテン: 既存ポジションを決済 → 逆方向で新規建て
        boolean isReverse =
            (position.side() == Side.BUY  && signal == Signal.SELL) ||
            (position.side() == Side.SELL && signal == Signal.BUY);

        if (isReverse) {
          BigDecimal profit = calcProfit(position, price);
          balance = balance.add(profit);
          trades.add(buildTrade(symbol, time, closeSide(position.side()),
              price, trade.getSize(), profit, balance, CloseReason.SIGNAL, "ドテン決済"));
          log.debug("[BT] ドテン決済: time={} side={} profit={}", time, position.side(), profit);

          // 新規建て
          position = new VirtualPosition(signal == Signal.BUY ? Side.BUY : Side.SELL,
              price, time, trade.getSize());
          trades.add(buildTrade(symbol, time,
              signal == Signal.BUY ? Side.BUY : Side.SELL,
              price, trade.getSize(), null, balance, null,
              "ドテン新規" + (signal == Signal.BUY ? "買" : "売")));
        }
        // 同方向はスキップ
      }

      // ドローダウン更新
      if (balance.compareTo(peakBalance) > 0) peakBalance = balance;
      BigDecimal drawdown = peakBalance.subtract(balance);
      if (drawdown.compareTo(maxDrawdown) > 0) maxDrawdown = drawdown;
    }

    // 最終ポジションを強制決済
    if (position != null) {
      KlineRecord last = klines.get(klines.size() - 1);
      BigDecimal profit = calcProfit(position, last.getClose());
      balance = balance.add(profit);
      trades.add(buildTrade(symbol, last.getOpenTime(), closeSide(position.side()),
          last.getClose(), position.size(), profit, balance, null, "期間終了強制決済"));
    }

    return new SimulationResult(trades, balance, maxDrawdown);
  }

  // -----------------------------------------------------------------------
  // 売買シグナル判定（TradingStrategy と同一ロジック）
  // -----------------------------------------------------------------------

  private Signal calcSignal(BigDecimal rsi, Signal rsiSignal,
      BigDecimal rci, Signal rciSignal, Signal dmiSignal,
      CrossSignal macdCross, CrossSignal temaCross,
      BigDecimal prevRci, CrossSignal prevMacd, CrossSignal prevTema) {

    double rciD = rci == null ? 0.0 : rci.doubleValue();
    double rsiD = rsi == null ? 0.0 : rsi.doubleValue();
    double pRci = prevRci == null ? 0.0 : prevRci.doubleValue();

    boolean macdGolden     = macdCross == CrossSignal.GOLDEN;
    boolean macdDead       = macdCross == CrossSignal.DEAD;
    boolean temaGolden     = temaCross == CrossSignal.GOLDEN;
    boolean temaDead       = temaCross == CrossSignal.DEAD;
    boolean prevMacdGolden = prevMacd == CrossSignal.GOLDEN;
    boolean prevMacdDead   = prevMacd == CrossSignal.DEAD;
    boolean prevTemaGolden = prevTema == CrossSignal.GOLDEN;
    boolean prevTemaDead   = prevTema == CrossSignal.DEAD;

    AppProperties.Indicator ind = props.getIndicator();

    // 売りシグナル（OR）
    if ((rciD >= 90   && pRci <= 60  && !prevMacdGolden) ||           // ①
        (rciD >= 100  && pRci <= 70  && !prevMacdGolden) ||           // ②
        (rsiD > ind.getRsiOverbought() && macdGolden) ||              // ③
        (macdGolden   && temaGolden  && rsiSignal == Signal.SELL) ||  // ④
        (macdGolden   && prevTemaGolden) ||                           // ⑤
        (temaGolden   && prevMacdGolden) ||                           // ⑥
        (rciSignal == Signal.SELL && temaGolden) ||                   // ⑦
        (dmiSignal == Signal.SELL && rciSignal == Signal.SELL && rsiD >= 65.0) || // ⑨
        (macdGolden   && temaGolden  && dmiSignal == Signal.SELL)) {  // ⑩
      return Signal.SELL;
    }

    // 買いシグナル（OR）
    if ((rciD <= -90  && pRci >= -60 && !prevMacdDead) ||            // ①
        (rciD <= -100 && pRci >= -70 && !prevMacdDead) ||            // ②
        (rsiD < ind.getRsiOversold() && macdDead) ||                  // ③
        (macdDead     && temaDead    && rsiSignal == Signal.BUY) ||   // ④
        (macdDead     && prevTemaDead) ||                             // ⑤
        (temaDead     && prevMacdDead) ||                             // ⑥
        (rciSignal == Signal.BUY && temaDead) ||                      // ⑦
        (dmiSignal == Signal.BUY && rciSignal == Signal.BUY && rsiD <= 35.0) || // ⑨
        (macdDead     && temaDead    && dmiSignal == Signal.BUY)) {   // ⑩
      return Signal.BUY;
    }

    return Signal.HOLD;
  }

  private Signal applyConsecutiveReverse(List<Signal> recent, Signal current) {
    if (recent.size() < CONSECUTIVE_REVERSE_COUNT - 1) return current;
    List<Signal> lastN = recent.subList(
        recent.size() - (CONSECUTIVE_REVERSE_COUNT - 1), recent.size());
    boolean allSame = lastN.stream().allMatch(s -> s == current);
    if (allSame) {
      Signal reversed = current == Signal.BUY ? Signal.SELL : Signal.BUY;
      log.debug("[BT] 連続{}回ドテン: {} → {}", CONSECUTIVE_REVERSE_COUNT, current, reversed);
      return reversed;
    }
    return current;
  }

  // -----------------------------------------------------------------------
  // 指標シグナル判定ヘルパー
  // -----------------------------------------------------------------------

  private Signal toRsiSignal(BigDecimal rsi, AppProperties.Indicator ind) {
    if (rsi == null) return null;
    if (rsi.doubleValue() <= ind.getRsiOversold())  return Signal.BUY;
    if (rsi.doubleValue() >= ind.getRsiOverbought()) return Signal.SELL;
    return Signal.HOLD;
  }

  private Signal toDmiSignal(DmiCalculator.DmiResult r, AppProperties.Indicator ind) {
    if (r == null) return null;
    if (r.getAdx().doubleValue() < ind.getDmiAdxThreshold()) return Signal.HOLD;
    if (r.getMinusDi().compareTo(r.getPlusDi()) > 0) return Signal.BUY;
    if (r.getPlusDi().compareTo(r.getMinusDi()) > 0) return Signal.SELL;
    return Signal.HOLD;
  }

  private Signal toRciSignal(BigDecimal rci, AppProperties.Indicator ind) {
    if (rci == null) return null;
    if (rci.doubleValue() <= ind.getRciOversold())  return Signal.BUY;
    if (rci.doubleValue() >= ind.getRciOverbought()) return Signal.SELL;
    return Signal.HOLD;
  }

  // -----------------------------------------------------------------------
  // ヘルパー
  // -----------------------------------------------------------------------

  /** klines[0..i-1] の末尾 limit 件を返す（現在足は含まない） */
  private List<KlineRecord> getHistory(List<KlineRecord> klines, int i, int limit) {
    int from = Math.max(0, i - limit);
    return klines.subList(from, i);
  }

  private double calcChangePercent(BigDecimal openPrice, BigDecimal currentPrice, Side side) {
    double change = currentPrice.subtract(openPrice)
        .divide(openPrice, MC)
        .multiply(BigDecimal.valueOf(100))
        .doubleValue();
    return side == Side.SELL ? -change : change;
  }

  private BigDecimal calcProfit(VirtualPosition pos, BigDecimal closePrice) {
    if (pos.side() == Side.BUY) {
      return closePrice.subtract(pos.openPrice()).multiply(pos.size(), MC)
          .setScale(0, RoundingMode.HALF_UP);
    } else {
      return pos.openPrice().subtract(closePrice).multiply(pos.size(), MC)
          .setScale(0, RoundingMode.HALF_UP);
    }
  }

  private Side closeSide(Side buildSide) {
    return buildSide == Side.BUY ? Side.SELL : Side.BUY;
  }

  private BacktestResult.Trade buildTrade(String symbol, OffsetDateTime time,
      Side side, BigDecimal price, BigDecimal size,
      BigDecimal profitJpy, BigDecimal balance,
      CloseReason reason, String conditions) {
    return BacktestResult.Trade.builder()
        .symbol(symbol)
        .tradeTime(time)
        .side(side)
        .price(price)
        .size(size)
        .profitJpy(profitJpy)
        .balanceJpy(balance)
        .closeReason(reason)
        .signalConditions(conditions)
        .build();
  }

  // -----------------------------------------------------------------------
  // Step4: 結果集計
  // -----------------------------------------------------------------------

  private BacktestResult buildResult(String symbol, String interval,
      LocalDate startDate, LocalDate endDate,
      BigDecimal initialBalance, SimulationResult sim) {

    // 決済のみの取引（profitJpy != null）を集計
    List<BacktestResult.Trade> closedTrades = sim.trades().stream()
        .filter(t -> t.getProfitJpy() != null)
        .toList();

    int tradeCount = closedTrades.size();
    int winCount   = (int) closedTrades.stream()
        .filter(t -> t.getProfitJpy().compareTo(BigDecimal.ZERO) > 0).count();
    int lossCount  = tradeCount - winCount;

    BigDecimal totalProfit = closedTrades.stream()
        .map(BacktestResult.Trade::getProfitJpy)
        .reduce(BigDecimal.ZERO, BigDecimal::add);

    BigDecimal winRate = tradeCount == 0 ? BigDecimal.ZERO
        : BigDecimal.valueOf(winCount * 100.0 / tradeCount)
            .setScale(2, RoundingMode.HALF_UP);

    return BacktestResult.builder()
        .symbol(symbol)
        .intervalType(interval)
        .startDate(startDate)
        .endDate(endDate)
        .initialBalanceJpy(initialBalance)
        .finalBalanceJpy(sim.finalBalance())
        .totalProfitJpy(totalProfit)
        .tradeCount(tradeCount)
        .winCount(winCount)
        .lossCount(lossCount)
        .winRate(winRate)
        .maxDrawdownJpy(sim.maxDrawdown())
        .executedAt(OffsetDateTime.now(ZoneOffset.UTC))
        .build();
  }

  private void printSummary(BacktestResult r, long runId) {
    log.info("================================================================");
    log.info("  バックテスト結果 (runId={})", runId);
    log.info("  期間        : {} 〜 {}", r.getStartDate(), r.getEndDate());
    log.info("  銘柄/足     : {} / {}", r.getSymbol(), r.getIntervalType());
    log.info("  初期残高    : {} 円", r.getInitialBalanceJpy());
    log.info("  最終残高    : {} 円", r.getFinalBalanceJpy());
    log.info("  総損益      : {} 円", r.getTotalProfitJpy());
    log.info("  取引回数    : {} 回（勝:{} 負:{}）", r.getTradeCount(), r.getWinCount(), r.getLossCount());
    log.info("  勝率        : {} %", r.getWinRate());
    log.info("  最大DD      : {} 円", r.getMaxDrawdownJpy());
    log.info("================================================================");
  }

  // -----------------------------------------------------------------------
  // 内部モデル
  // -----------------------------------------------------------------------

  record VirtualPosition(Side side, BigDecimal openPrice, OffsetDateTime openTime, BigDecimal size) {}
  record SimulationResult(List<BacktestResult.Trade> trades, BigDecimal finalBalance, BigDecimal maxDrawdown) {}
}
