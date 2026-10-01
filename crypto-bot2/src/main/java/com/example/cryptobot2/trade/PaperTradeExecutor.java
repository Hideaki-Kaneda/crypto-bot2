package com.example.cryptobot2.trade;

import com.example.cryptobot2.config.AppProperties;
import com.example.cryptobot2.model.Position;
import com.example.cryptobot2.model.Position.CloseReason;
import com.example.cryptobot2.model.TradeHistory;
import com.example.cryptobot2.model.TradeHistory.Side;
import com.example.cryptobot2.model.TradeHistory.TradeStatus;
import com.example.cryptobot2.model.TradeSignal;
import com.example.cryptobot2.model.TradeSignal.CrossSignal;
import com.example.cryptobot2.model.TradeSignal.Signal;
import com.example.cryptobot2.repository.TradeRepository;
import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * ペーパートレード（模擬売買）実行クラス。
 *
 * <h2>新規建て</h2>
 * <ul>
 *   <li>ポジションなし + finalSignal=BUY（GC）→ 買建て新規</li>
 *   <li>ポジションなし + finalSignal=SELL（DC）→ 売建て新規</li>
 * </ul>
 *
 * <h2>利確</h2>
 * <ul>
 *   <li>買建て中 + finalSignal=SELL（DC or 価格上昇反転）→ 利確決済</li>
 *   <li>売建て中 + finalSignal=BUY（GC or 価格下落反転）→ 利確決済</li>
 * </ul>
 *
 * <h2>途転なし・損切りなし</h2>
 * <p>利確後は次のシグナルで新規建てする。
 * GC→DC→GC と連続した場合は自然と途転に近い動作になる。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaperTradeExecutor {

  private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

  private final AppProperties props;
  private final TradeRepository tradeRepository;

  // 日次損益管理（JST 06:00 基準でリセット）
  private java.time.LocalDate dailyResetDate = null;
  private java.math.BigDecimal dailyPnl      = java.math.BigDecimal.ZERO;
  private boolean dailyLimitReached          = false;

  public void execute(TradeSignal signal) {
    String symbol         = signal.getSymbol();
    BigDecimal price      = signal.getPrice();
    Signal finalSignal    = signal.getFinalSignal();
    CrossSignal temaCross = signal.getTemaCross();
    AppProperties.Trade trade = props.getTrade();

    // 日次損益リセットチェック（JST 06:00 基準）
    java.time.ZonedDateTime jst = signal.getSignalTime()
        .atZoneSameInstant(java.time.ZoneId.of("Asia/Tokyo"));
    java.time.LocalDate jstDate = jst.getHour() < 6
        ? jst.toLocalDate().minusDays(1) : jst.toLocalDate();
    if (!jstDate.equals(dailyResetDate)) {
      dailyResetDate    = jstDate;
      dailyPnl          = java.math.BigDecimal.ZERO;
      dailyLimitReached = false;
      log.debug("[PAPER] 日次リセット: date={}", jstDate);
    }

    if (finalSignal == Signal.HOLD) {
      log.debug("[PAPER] HOLD: symbol={} price={}", symbol, price);
      return;
    }

    boolean isGolden = temaCross == CrossSignal.GOLDEN;
    boolean isDead   = temaCross == CrossSignal.DEAD;
    boolean isCross  = isGolden || isDead;

    // クロスが来た時の期待される方向
    // GC → BUY 方向 / DC → SELL 方向
    Signal crossSignal = isGolden ? Signal.BUY : isDead ? Signal.SELL : null;

    Optional<Position> openPos = tradeRepository.findOpenPosition(symbol, true);

    if (openPos.isEmpty()) {
      // ポジションなし: クロスがあれば新規建て（符号反転のみでは新規なし）
      if (isCross) {
        // 日次損益制限チェック
        if (dailyLimitReached) {
          log.info("[PAPER] 日次制限により新規建てスキップ: symbol={} dailyPnl={}", symbol, dailyPnl);
        } else {
        // RCI フィルター: RCI が [-rciEntryMin, rciEntryMax] 範囲内は新規建てしない
        double rciEntryMin = props.getIndicator().getRciEntryMin();
        double rciEntryMax = props.getIndicator().getRciEntryMax();
        java.math.BigDecimal rciVal = signal.getRci();
        boolean rciBlocked = rciVal != null
            && rciVal.doubleValue() >= rciEntryMin
            && rciVal.doubleValue() <= rciEntryMax;
        if (rciBlocked) {
          log.info("[PAPER] 新規建てスキップ（RCI={} は範囲[{},{}]内）: symbol={} cross={}",
              rciVal, rciEntryMin, rciEntryMax, symbol, temaCross);
        } else {
          // クロスの方向を基本とし、符号反転がある場合は逆転する
          boolean flipToMinus = finalSignal == Signal.SELL && isGolden;
          boolean flipToPlus  = finalSignal == Signal.BUY  && isDead;
          boolean flipped     = flipToMinus || flipToPlus;

          Side side;
          if (flipped) {
            side = finalSignal == Signal.BUY ? Side.BUY : Side.SELL;
            log.info("[PAPER] 新規建て（クロス+符号反転逆転）: symbol={} cross={} side={}",
                symbol, temaCross, side);
          } else {
            side = isGolden ? Side.BUY : Side.SELL;
          }
          openNewPosition(symbol, price, trade, side);
        }
        } // end dailyLimitReached check
      } else {
        log.debug("[PAPER] 新規建てスキップ（クロスなし）: symbol={} cross={} signal={}",
            symbol, temaCross, finalSignal);
      }
    } else {
      Position pos = openPos.get();

      // 損切りチェック（最優先）
      if (checkStopLoss(pos, price, trade)) {
        return;
      }

      // 利確判定: 符号反転のみ（クロスは利確に使わない）
      boolean shouldClose =
          (pos.getSide() == Side.BUY  && finalSignal == Signal.SELL) ||
          (pos.getSide() == Side.SELL && finalSignal == Signal.BUY);

      if (shouldClose) {
        String reason;
        if (pos.getSide() == Side.BUY) {
          reason = isDead ? "TEMA_DEAD" : "価格反転(+)";
        } else {
          reason = isGolden ? "TEMA_GOLDEN" : "価格反転(-)";
        }

        // 符号反転による利確の場合、最低保有本数チェック
        if (!isCross && isFlipCloseBlocked(pos, signal.getSignalTime(), reason, trade)) {
          log.debug("[PAPER] 符号反転利確スキップ（保有本数不足）: symbol={} reason={}", symbol, reason);
        } else {
          // 利確決済
          closePosition(pos, price, CloseReason.SIGNAL, reason);
          // 日次損益更新（profitJpy は closePosition 内で計算されるが、ここで再計算）
          BigDecimal profit = pos.getSide() == Side.BUY
              ? price.subtract(pos.getOpenPrice()).multiply(pos.getAmount(), MC)
              : pos.getOpenPrice().subtract(price).multiply(pos.getAmount(), MC);
          dailyPnl = dailyPnl.add(profit.setScale(0, java.math.RoundingMode.HALF_UP));
          checkDailyLimit(trade);

          // クロスがある場合は利確直後に逆方向で新規建て
          if (isCross && !dailyLimitReached) {
            double rciEntryMin2 = props.getIndicator().getRciEntryMin();
            double rciEntryMax2 = props.getIndicator().getRciEntryMax();
            java.math.BigDecimal rciVal2 = signal.getRci();
            boolean rciBlocked2 = rciVal2 != null
                && rciVal2.doubleValue() >= rciEntryMin2
                && rciVal2.doubleValue() <= rciEntryMax2;

            Side newSide = isGolden ? Side.BUY : Side.SELL;
            boolean emaBlockedNew = (newSide == Side.BUY  && !signal.isEmaBullish())
                                 || (newSide == Side.SELL && !signal.isEmaBearish());

            if (rciBlocked2) {
              log.info("[PAPER] 利確後新規建てスキップ（RCI={} は範囲[{},{}]内）: symbol={} cross={}",
                  rciVal2, rciEntryMin2, rciEntryMax2, symbol, temaCross);
            } else if (emaBlockedNew) {
              log.info("[PAPER] 利確後新規建てスキップ（EMAトレンド逆行）: symbol={} side={}", symbol, newSide);
            } else {
              log.info("[PAPER] 利確後即新規建て: symbol={} side={} reason={}",
                  symbol, newSide, reason);
              openNewPosition(symbol, price, trade, newSide);
            }
          }
        }

      } else {
        log.debug("[PAPER] スキップ: symbol={} side={} finalSignal={} temaCross={}",
            symbol, pos.getSide(), finalSignal, temaCross);
      }
    }
  }

  // -----------------------------------------------------------------------
  // Private
  // -----------------------------------------------------------------------

  private void openNewPosition(String symbol, BigDecimal price,
      AppProperties.Trade trade, Side side) {

    BigDecimal size      = trade.getSize();
    BigDecimal amountJpy = price.multiply(size, MC).setScale(0, RoundingMode.HALF_UP);
    OffsetDateTime now   = now();

    TradeHistory th = TradeHistory.builder()
        .symbol(symbol)
        .tradeTime(now)
        .side(side)
        .price(price)
        .amount(size)
        .amountJpy(amountJpy)
        .fee(BigDecimal.ZERO)
        .status(TradeStatus.PAPER)
        .orderId("PAPER-" + System.currentTimeMillis())
        .note("ペーパートレード 新規" + side.name())
        .build();

    long historyId = tradeRepository.saveTradeHistory(th);

    Position pos = Position.builder()
        .symbol(symbol)
        .side(side)
        .openTime(now)
        .openPrice(price)
        .amount(size)
        .amountJpy(amountJpy)
        .isPaper(true)
        .tradeHistoryId(historyId)
        .build();

    tradeRepository.openPosition(pos);

    log.info("[PAPER] 新規{}: symbol={} price={} size={} amountJpy={}",
        side, symbol, price, size, amountJpy);
  }

  private void closePosition(Position pos, BigDecimal closePrice,
      CloseReason reason, String detail) {

    OffsetDateTime now = now();
    Side closeSide;
    BigDecimal profitJpy;

    if (pos.getSide() == Side.BUY) {
      profitJpy = closePrice.subtract(pos.getOpenPrice())
          .multiply(pos.getAmount(), MC).setScale(0, RoundingMode.HALF_UP);
      closeSide = Side.SELL;
    } else {
      profitJpy = pos.getOpenPrice().subtract(closePrice)
          .multiply(pos.getAmount(), MC).setScale(0, RoundingMode.HALF_UP);
      closeSide = Side.BUY;
    }

    TradeHistory th = TradeHistory.builder()
        .symbol(pos.getSymbol())
        .tradeTime(now)
        .side(closeSide)
        .price(closePrice)
        .amount(pos.getAmount())
        .amountJpy(pos.getAmountJpy())
        .fee(BigDecimal.ZERO)
        .status(TradeStatus.PAPER)
        .orderId("PAPER-CLOSE-" + System.currentTimeMillis())
        .note("ペーパートレード 利確" + closeSide.name() + " reason=" + detail)
        .profitJpy(profitJpy)
        .build();

    tradeRepository.saveTradeHistory(th);
    tradeRepository.closePosition(pos.getId(), now, closePrice, profitJpy, reason);

    log.info("[PAPER] 利確{}: symbol={} openPrice={} closePrice={} profitJpy={} reason={}",
        closeSide, pos.getSymbol(), pos.getOpenPrice(), closePrice, profitJpy, detail);
  }

  /**
   * 符号反転による利確が最低保有本数に達していない場合 true を返す。
   *
   * @param pos        現在のポジション
   * @param signalTime 現在のシグナル時刻
   * @param reason     利確理由
   * @param trade      売買設定
   * @return 利確をスキップすべき場合 true
   */
  /**
   * 損切りチェック。含み損が stopLossJpy 以下なら損切り決済する。
   *
   * @return 損切りした場合 true
   */
  private boolean checkStopLoss(Position pos, BigDecimal price, AppProperties.Trade trade) {
    java.math.BigDecimal stopLossJpy = trade.getStopLossJpy();
    if (stopLossJpy == null || stopLossJpy.compareTo(java.math.BigDecimal.ZERO) == 0) {
      return false; // 損切りなし
    }

    // 含み損益を計算
    BigDecimal unrealizedPnl;
    if (pos.getSide() == Side.BUY) {
      unrealizedPnl = price.subtract(pos.getOpenPrice()).multiply(pos.getAmount(), MC)
          .setScale(0, RoundingMode.HALF_UP);
    } else {
      unrealizedPnl = pos.getOpenPrice().subtract(price).multiply(pos.getAmount(), MC)
          .setScale(0, RoundingMode.HALF_UP);
    }

    if (unrealizedPnl.compareTo(stopLossJpy) <= 0) {
      log.info("[PAPER] 損切り発動: symbol={} side={} openPrice={} currentPrice={} unrealizedPnl={}",
          pos.getSymbol(), pos.getSide(), pos.getOpenPrice(), price, unrealizedPnl);
      closePosition(pos, price, CloseReason.STOP_LOSS, "損切り");
      return true;
    }
    return false;
  }

  /** 日次損益が上下限に達したら dailyLimitReached を true にする */
  private void checkDailyLimit(AppProperties.Trade trade) {
    java.math.BigDecimal profitLimit = trade.getDailyProfitLimit();
    java.math.BigDecimal lossLimit   = trade.getDailyLossLimit();

    if (profitLimit != null && profitLimit.compareTo(java.math.BigDecimal.ZERO) > 0
        && dailyPnl.compareTo(profitLimit) >= 0) {
      dailyLimitReached = true;
      log.info("[PAPER] 日次利益上限到達: date={} dailyPnl={} limit={}", dailyResetDate, dailyPnl, profitLimit);
    } else if (lossLimit != null && lossLimit.compareTo(java.math.BigDecimal.ZERO) < 0
        && dailyPnl.compareTo(lossLimit) <= 0) {
      dailyLimitReached = true;
      log.info("[PAPER] 日次損失下限到達: date={} dailyPnl={} limit={}", dailyResetDate, dailyPnl, lossLimit);
    }
  }

  private boolean isFlipCloseBlocked(Position pos, java.time.OffsetDateTime signalTime,
      String reason, AppProperties.Trade trade) {

    int minBars;
    if ("価格反転(-)".equals(reason)) {
      minBars = trade.getFlipCloseSellMinBars(); // 売建て利確
    } else if ("価格反転(+)".equals(reason)) {
      minBars = trade.getFlipCloseBuyMinBars();  // 買建て利確
    } else {
      return false; // TEMA_DEAD/GOLDEN はスキップしない
    }

    if (minBars <= 0) return false; // 0 = 制限なし

    // 保有本数 = (現在時刻 - 建て時刻) / インターバル分数
    long intervalMinutes = toIntervalMinutes(props.getKline().getInterval());
    long elapsedMinutes = java.time.Duration.between(pos.getOpenTime(), signalTime).toMinutes();
    long holdBars = elapsedMinutes / intervalMinutes;

    if (holdBars < minBars) {
      log.debug("[PAPER] 符号反転利確スキップ: reason={} holdBars={} minBars={}",
          reason, holdBars, minBars);
      return true;
    }
    return false;
  }

  private long toIntervalMinutes(String interval) {
    return switch (interval) {
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
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneId.of(props.getScheduler().getTimezone()));
  }
}
