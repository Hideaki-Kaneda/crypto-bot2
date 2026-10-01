package com.example.cryptobot2.backtest;

import com.example.cryptobot2.client.GmoCoinApiClient;
import com.example.cryptobot2.config.AppProperties;
import com.example.cryptobot2.indicator.DmiCalculator;
import com.example.cryptobot2.indicator.EmaCalculator;
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
import com.example.cryptobot2.service.TradingConfigService;
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
  private static final int CONSECUTIVE_REVERSE_COUNT = 3; // 互換性のため残す（未使用）
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
  private final TradingConfigService tradingConfigService;

  /**
   * バックテストを実行する。
   *
   * @param startDateStr 開始日（yyyy-MM-dd）
   * @param endDateStr   終了日（yyyy-MM-dd）
   */
  public void run(String startDateStr, String endDateStr) {
    // DB から最新設定を読み込む（バックテストも動的設定を反映する）
    tradingConfigService.reload(props);

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
    // startDate の JST 00:00〜05:55 は GMO の date=startDate-1 に含まれるため
    // from は startDate の JST 00:00（= UTC 前日 15:00）から取得する
    OffsetDateTime from = startDate.atTime(0, 0)
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

    // GMO の date=YYYYMMDD は JST 06:00〜翌 05:55 のデータを返す。
    // startDate の 00:00〜05:55（JST）は前日の date= に含まれるため、
    // 前日から取得を開始する。
    LocalDate cursor = startDate.minusDays(1);
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

    AppProperties.Indicator ind   = props.getIndicator();
    AppProperties.Trade trade     = props.getTrade();
    int historySize = ind.getPriceHistorySize();

    // 日次損益制限設定
    BigDecimal dailyProfitLimit = trade.getDailyProfitLimit();
    BigDecimal dailyLossLimit   = trade.getDailyLossLimit();
    boolean hasDailyLimit = (dailyProfitLimit != null && dailyProfitLimit.compareTo(BigDecimal.ZERO) != 0)
                         || (dailyLossLimit   != null && dailyLossLimit.compareTo(BigDecimal.ZERO) != 0);

    List<BacktestResult.Trade> trades = new ArrayList<>();

    BigDecimal balance     = initialBalance;
    BigDecimal peakBalance = initialBalance;
    BigDecimal maxDrawdown = BigDecimal.ZERO;

    VirtualPosition position = null;

    // 日次損益管理
    java.time.LocalDate currentDay = null;
    BigDecimal dailyPnl = BigDecimal.ZERO;  // 当日累積損益
    boolean dailyLimitReached = false;       // 当日制限到達フラグ

    for (int i = 0; i < klines.size(); i++) {
      KlineRecord current = klines.get(i);
      BigDecimal price    = current.getClose();
      OffsetDateTime time = current.getOpenTime();

      // 日付切り替えチェック（JST 06:00 基準）
      java.time.ZonedDateTime jst = time.atZoneSameInstant(
          java.time.ZoneId.of("Asia/Tokyo"));
      java.time.LocalDate jstDate = jst.getHour() < 6
          ? jst.toLocalDate().minusDays(1)
          : jst.toLocalDate();

      if (!jstDate.equals(currentDay)) {
        // 日付が変わったら日次集計をリセット
        currentDay = jstDate;
        dailyPnl = BigDecimal.ZERO;
        dailyLimitReached = false;
        log.debug("[BT] 日次リセット: date={}", jstDate);
      }

      List<KlineRecord> history = getHistory(klines, i, historySize);
      if (history.size() < 2) continue;

      List<BigDecimal> closes = history.stream().map(KlineRecord::getClose).toList();
      List<BigDecimal> highs  = history.stream().map(KlineRecord::getHigh).toList();
      List<BigDecimal> lows   = history.stream().map(KlineRecord::getLow).toList();

      // 各指標計算（DB記録用）
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

      // 前回・前々回 TEMA_FAST（利確の TEMA_FAST 変化反転判定用）
      BigDecimal nowTemaFast     = temaResult == null ? null : temaResult.getTemaFast();
      BigDecimal prevTemaFast    = null;
      BigDecimal prevPrevTemaFast = null;
      if (i > 0) {
        List<KlineRecord> h1 = getHistory(klines, i - 1, historySize);
        if (!h1.isEmpty()) {
          List<BigDecimal> c1 = h1.stream().map(KlineRecord::getClose).toList();
          TemaCalculator.TemaResult t1 = TemaCalculator.calculatePair(
              c1, ind.getTemaFastPeriod(), ind.getTemaSlowPeriod());
          prevTemaFast = t1 == null ? null : t1.getTemaFast();
        }
      }
      if (i > 1) {
        List<KlineRecord> h2 = getHistory(klines, i - 2, historySize);
        if (!h2.isEmpty()) {
          List<BigDecimal> c2 = h2.stream().map(KlineRecord::getClose).toList();
          TemaCalculator.TemaResult t2 = TemaCalculator.calculatePair(
              c2, ind.getTemaFastPeriod(), ind.getTemaSlowPeriod());
          prevPrevTemaFast = t2 == null ? null : t2.getTemaFast();
        }
      }

      // EMA トレンドフィルター計算
      int emaPeriod = ind.getEmaTrendPeriod();
      BigDecimal emaVal = emaPeriod > 0
          ? EmaCalculator.calculate(closes, emaPeriod) : null;
      boolean emaAbove = emaVal != null && nowTemaFast != null
          && nowTemaFast.compareTo(emaVal) > 0;
      boolean emaBelow = emaVal != null && nowTemaFast != null
          && nowTemaFast.compareTo(emaVal) < 0;

      boolean reverse = ind.isEmaTrendReverse();
      boolean emaBullish, emaBearish;
      if (emaVal == null || nowTemaFast == null) {
        emaBullish = true;
        emaBearish = true;
      } else if (reverse) {
        emaBullish = emaBelow;  // 逆張り: TEMA_FAST < EMA → BUY 許可
        emaBearish = emaAbove;  // 逆張り: TEMA_FAST > EMA → SELL 許可
      } else {
        emaBullish = emaAbove;  // 順張り: TEMA_FAST > EMA → BUY 許可
        emaBearish = emaBelow;  // 順張り: TEMA_FAST < EMA → SELL 許可
      }

      // シグナル判定（TradingStrategy と同一ロジック）
      Signal signal = calcSignal(temaCross, nowTemaFast, prevTemaFast, prevPrevTemaFast,
          emaBullish, emaBearish);

      // シグナル反転（逆張りモード）
      if (ind.isSignalReverse() && signal != Signal.HOLD) {
        signal = signal == Signal.BUY ? Signal.SELL : Signal.BUY;
      }

      // trade_signal 保存（バックテスト確認用）
      TradeSignal ts = TradeSignal.builder()
          .symbol(symbol).signalTime(time).price(price)
          .rsi(rsiVal).rsiPeriod(ind.getRsiPeriod()).rsiSignal(rsiSignal)
          .macd(macdResult == null ? null : macdResult.getMacd())
          .macdSignal(macdResult == null ? null : macdResult.getSignal())
          .macdHistogram(macdResult == null ? null : macdResult.getHistogram())
          .macdFast(ind.getMacdFastPeriod()).macdSlow(ind.getMacdSlowPeriod())
          .macdSignalPeriod(ind.getMacdSignalPeriod()).macdCross(macdCross)
          .dmiPlus(dmiResult == null ? null : dmiResult.getPlusDi())
          .dmiMinus(dmiResult == null ? null : dmiResult.getMinusDi())
          .adx(dmiResult == null ? null : dmiResult.getAdx())
          .dmiPeriod(ind.getDmiPeriod()).dmiSignal(dmiSignal)
          .rci(rciVal).rciPeriod(ind.getRciPeriod()).rciSignal(rciSignal)
          .temaFast(temaResult == null ? null : temaResult.getTemaFast())
          .temaFastPeriod(ind.getTemaFastPeriod())
          .temaSlow(temaResult == null ? null : temaResult.getTemaSlow())
          .temaSlowPeriod(ind.getTemaSlowPeriod()).temaCross(temaCross)
          .finalSignal(signal)
          .build();
      signalRepository.save(ts);

      if (signal == Signal.HOLD) {
        // ドローダウン更新
        if (balance.compareTo(peakBalance) > 0) peakBalance = balance;
        BigDecimal drawdown = peakBalance.subtract(balance);
        if (drawdown.compareTo(maxDrawdown) > 0) maxDrawdown = drawdown;
        continue;
      }

      if (position == null) {
        // ポジションなし: クロスがあれば新規建て（符号反転のみでは新規なし）
        boolean isGolden = temaCross == CrossSignal.GOLDEN;
        boolean isDead   = temaCross == CrossSignal.DEAD;
        if (isGolden || isDead) {
          // 日次損益制限チェック（新規建てのみ制限、利確は制限なし）
          if (dailyLimitReached) {
            log.debug("[BT] 日次制限により新規建てスキップ: date={} dailyPnl={}", currentDay, dailyPnl);
          } else {
          // RCI フィルター: RCI が [rciEntryMin, rciEntryMax] 範囲内は新規建てしない
          double rciEntryMin = ind.getRciEntryMin();
          double rciEntryMax = ind.getRciEntryMax();
          boolean rciBlocked = rciVal != null
              && rciVal.doubleValue() >= rciEntryMin
              && rciVal.doubleValue() <= rciEntryMax;

          if (rciBlocked) {
            log.debug("[BT] 新規建てスキップ（RCI={} は範囲[{},{}]内）: time={} cross={}",
                rciVal, rciEntryMin, rciEntryMax, time, temaCross);
          } else {
            boolean flipped = (isGolden && signal == Signal.SELL)
                           || (isDead   && signal == Signal.BUY);
            Side newSide;
            String label;
            if (flipped) {
              newSide = signal == Signal.BUY ? Side.BUY : Side.SELL;
              label   = newSide == Side.BUY ? "新規買(GC+反転)" : "新規売(DC+反転)";
            } else {
              newSide = isGolden ? Side.BUY : Side.SELL;
              label   = newSide == Side.BUY ? "新規買(GC)" : "新規売(DC)";
            }
            position = new VirtualPosition(newSide, price, time, trade.getSize());
            trades.add(buildTrade(symbol, time, newSide,
                price, trade.getSize(), null, balance, null, label));
            log.debug("[BT] {}: time={} price={}", label, time, price);
          }
        }
          // 符号反転のみのシグナルはポジションなしでは無視
          } // end dailyLimitReached check

      } else {
        // ポジションあり
        boolean isGolden = temaCross == CrossSignal.GOLDEN;
        boolean isDead   = temaCross == CrossSignal.DEAD;
        boolean isCross  = isGolden || isDead;

        // 損切りチェック（最優先）
        java.math.BigDecimal stopLossJpy = trade.getStopLossJpy();
        if (stopLossJpy != null && stopLossJpy.compareTo(BigDecimal.ZERO) != 0) {
          BigDecimal unrealizedPnl;
          if (position.side() == Side.BUY) {
            unrealizedPnl = price.subtract(position.openPrice())
                .multiply(position.size(), MC).setScale(0, RoundingMode.HALF_UP);
          } else {
            unrealizedPnl = position.openPrice().subtract(price)
                .multiply(position.size(), MC).setScale(0, RoundingMode.HALF_UP);
          }
          if (unrealizedPnl.compareTo(stopLossJpy) <= 0) {
            BigDecimal profit = calcProfit(position, price);
            balance = balance.add(profit);
            dailyPnl = dailyPnl.add(profit);
            trades.add(buildTrade(symbol, time, closeSide(position.side()),
                price, trade.getSize(), profit, balance, CloseReason.STOP_LOSS, "損切り"));
            log.debug("[BT] 損切り: time={} side={} unrealizedPnl={}", time, position.side(), unrealizedPnl);
            position = null;

            // ドローダウン更新
            if (balance.compareTo(peakBalance) > 0) peakBalance = balance;
            BigDecimal drawdown = peakBalance.subtract(balance);
            if (drawdown.compareTo(maxDrawdown) > 0) maxDrawdown = drawdown;
            continue;
          }
        }

        // 利確判定: クロス方向を優先、クロスなしは finalSignal（符号反転）で判定
        Signal crossSignal = isGolden ? Signal.BUY : isDead ? Signal.SELL : null;
        Signal judgeSignal = crossSignal != null ? crossSignal : signal;

        boolean shouldClose =
            (position.side() == Side.BUY  && judgeSignal == Signal.SELL) ||
            (position.side() == Side.SELL && judgeSignal == Signal.BUY);

        if (shouldClose) {
          String reason;
          if (position.side() == Side.BUY) {
            reason = isDead ? "TEMA_DEAD" : "価格反転(+)";
          } else {
            reason = isGolden ? "TEMA_GOLDEN" : "価格反転(-)";
          }

          boolean flipBlocked = !isCross && isFlipCloseBlocked(
              position.openTime(), time, reason, trade, ind);

          if (flipBlocked) {
            log.debug("[BT] 符号反転利確スキップ（保有本数不足）: time={} reason={}", time, reason);
          } else {
            BigDecimal profit = calcProfit(position, price);
            balance = balance.add(profit);
            dailyPnl = dailyPnl.add(profit);
            if (hasDailyLimit) {
              if (dailyProfitLimit != null && dailyProfitLimit.compareTo(BigDecimal.ZERO) > 0
                  && dailyPnl.compareTo(dailyProfitLimit) >= 0) {
                dailyLimitReached = true;
                log.debug("[BT] 日次利益上限到達: date={} dailyPnl={}", currentDay, dailyPnl);
              } else if (dailyLossLimit != null && dailyLossLimit.compareTo(BigDecimal.ZERO) < 0
                  && dailyPnl.compareTo(dailyLossLimit) <= 0) {
                dailyLimitReached = true;
                log.debug("[BT] 日次損失下限到達: date={} dailyPnl={}", currentDay, dailyPnl);
              }
            }
            trades.add(buildTrade(symbol, time, closeSide(position.side()),
                price, trade.getSize(), profit, balance, CloseReason.SIGNAL, reason));
            log.debug("[BT] 利確: time={} side={} profit={} reason={} temaCross={}",
                time, position.side(), profit, reason, temaCross);
            position = null;

            // クロスがある場合は利確直後に逆方向で新規建て
            if (isCross && !dailyLimitReached) {
              boolean rciBlocked2 = rciVal != null
                  && rciVal.doubleValue() >= ind.getRciEntryMin()
                  && rciVal.doubleValue() <= ind.getRciEntryMax();

              Side newSide = isGolden ? Side.BUY : Side.SELL;
              boolean emaBlockedNew = (newSide == Side.BUY && !emaBullish)
                  || (newSide == Side.SELL && !emaBearish);

              if (rciBlocked2) {
                log.debug("[BT] 利確後新規建てスキップ（RCI={} は範囲[{},{}]内）: time={} cross={}",
                    rciVal, ind.getRciEntryMin(), ind.getRciEntryMax(), time, temaCross);
              } else if (emaBlockedNew) {
                log.debug("[BT] 利確後新規建てスキップ（EMAトレンド逆行）: time={} side={} emaBullish={} emaBearish={}",
                    time, newSide, emaBullish, emaBearish);
              } else {
                position = new VirtualPosition(newSide, price, time, trade.getSize());
                trades.add(buildTrade(symbol, time, newSide,
                    price, trade.getSize(), null, balance, null,
                    "利確後即新規" + (newSide == Side.BUY ? "買(GC)" : "売(DC)")));
                log.debug("[BT] 利確後即新規{}: time={} price={}", newSide, time, price);
              }
            }
          }
        }
        // スキップ（同方向）
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

  /**
   * GC=BUY / DC=SELL、また TEMA_FAST の変化方向反転も利確条件として判定する。
   *
   * @param temaCross      今回の TEMA クロス
   * @param nowTemaFast    今回の TEMA_FAST 値
   * @param prevTemaFast   前回の TEMA_FAST 値
   * @param prevPrevTemaFast 前々回の TEMA_FAST 値
   */
  private Signal calcSignal(CrossSignal temaCross,
      BigDecimal nowTemaFast, BigDecimal prevTemaFast, BigDecimal prevPrevTemaFast,
      boolean emaBullish, boolean emaBearish) {

    boolean temaGolden = temaCross == CrossSignal.GOLDEN;
    boolean temaDead   = temaCross == CrossSignal.DEAD;

    // TEMA_FAST の変化方向の反転判定
    boolean temaFlipToPlus  = false;
    boolean temaFlipToMinus = false;
    if (nowTemaFast != null && prevTemaFast != null && prevPrevTemaFast != null) {
      int diff1Sign = prevTemaFast.compareTo(prevPrevTemaFast);
      int diff2Sign = nowTemaFast.compareTo(prevTemaFast);
      temaFlipToPlus  = diff1Sign < 0 && diff2Sign > 0; // マイナス→プラス → BUY
      temaFlipToMinus = diff1Sign > 0 && diff2Sign < 0; // プラス→マイナス → SELL
    }

    // 符号反転を優先（利確なので EMA フィルターなし）
    if (temaFlipToPlus)  return Signal.BUY;
    if (temaFlipToMinus) return Signal.SELL;

    // クロスシグナル: EMA トレンドフィルターを適用（新規エントリー方向を制限）
    if (temaGolden) return emaBullish ? Signal.BUY  : Signal.HOLD;
    if (temaDead)   return emaBearish ? Signal.SELL : Signal.HOLD;
    return Signal.HOLD;
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

  private boolean isFlipCloseBlocked(
      java.time.OffsetDateTime openTime, java.time.OffsetDateTime signalTime,
      String reason, AppProperties.Trade trade, AppProperties.Indicator ind) {

    int minBars;
    if ("価格反転(-)".equals(reason)) {
      minBars = trade.getFlipCloseSellMinBars();
    } else if ("価格反転(+)".equals(reason)) {
      minBars = trade.getFlipCloseBuyMinBars();
    } else {
      return false;
    }

    if (minBars <= 0) return false;

    long intervalMinutes = switch (props.getKline().getInterval()) {
      case "1min"   ->    1L;
      case "5min"   ->    5L;
      case "10min"  ->   10L;
      case "15min"  ->   15L;
      case "30min"  ->   30L;
      case "1hour"  ->   60L;
      case "4hour"  ->  240L;
      case "8hour"  ->  480L;
      case "12hour" ->  720L;
      case "1day"   -> 1440L;
      default       ->   10L;
    };

    long elapsedMinutes = java.time.Duration.between(openTime, signalTime).toMinutes();
    long holdBars = elapsedMinutes / intervalMinutes;
    return holdBars < minBars;
  }

  /** klines[0..i-1] の末尾 limit 件を返す（現在足は含まない） */
  private List<KlineRecord> getHistory(List<KlineRecord> klines, int i, int limit) {
    int from = Math.max(0, i - limit);
    return klines.subList(from, i);
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
