package com.example.cryptobot2.trade;

import com.example.cryptobot2.config.AppProperties;
import com.example.cryptobot2.model.Position;
import com.example.cryptobot2.model.Position.CloseReason;
import com.example.cryptobot2.model.TradeHistory;
import com.example.cryptobot2.model.TradeHistory.Side;
import com.example.cryptobot2.model.TradeHistory.TradeStatus;
import com.example.cryptobot2.model.TradeSignal;
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
 * <p>空売り・ドテン対応。実際の注文は行わず DB への記録のみを行う。
 *
 * <p>実行ルール:
 * <ul>
 *   <li>損切り・利確チェック（最優先）→ 条件達成で決済のみ（ドテンなし）
 *   <li>ポジションなし + BUY  → 買建て新規
 *   <li>ポジションなし + SELL → 売建て新規（空売り）
 *   <li>買建て中     + SELL  → 買建て決済 → 売建て新規（ドテン）
 *   <li>売建て中     + BUY   → 売建て決済 → 買建て新規（ドテン）
 *   <li>同方向シグナル        → スキップ（重複防止）
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PaperTradeExecutor {

  private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

  private final AppProperties props;
  private final TradeRepository tradeRepository;

  public void execute(TradeSignal signal) {
    String symbol = signal.getSymbol();
    BigDecimal price = signal.getPrice();
    AppProperties.Trade trade = props.getTrade();
    TradeSignal.Signal finalSignal = signal.getFinalSignal();

    if (finalSignal == TradeSignal.Signal.HOLD) {
      log.debug("[PAPER] HOLD: symbol={} price={}", symbol, price);
    }

    // --- 損切り・利確チェック（最優先）---
    Optional<Position> openPos = tradeRepository.findOpenPosition(symbol, true);
    if (openPos.isPresent()) {
      boolean closed = checkStopOrTakeProfit(openPos.get(), price, trade);
      if (closed) {
        // 損切り・利確はドテンなしで終了
        return;
      }
    }

    // --- シグナル処理 ---
    if (finalSignal == TradeSignal.Signal.HOLD) {
      return;
    }

    // ポジション再取得（損切り・利確後に変わる可能性があるため）
    openPos = tradeRepository.findOpenPosition(symbol, true);

    if (openPos.isEmpty()) {
      // ポジションなし → 新規建て（BUY or SELL）
      openNewPosition(symbol, price, trade, finalSignal);
    } else {
      Position pos = openPos.get();
      Side currentSide = pos.getSide();
      boolean isReverse =
          (currentSide == Side.BUY  && finalSignal == TradeSignal.Signal.SELL) ||
          (currentSide == Side.SELL && finalSignal == TradeSignal.Signal.BUY);

      if (isReverse) {
        // 逆方向シグナル → 決済してドテン
        log.info("[PAPER] ドテン: symbol={} {} → {}",
            symbol, currentSide, finalSignal);
        closePosition(pos, price, CloseReason.SIGNAL);
        openNewPosition(symbol, price, trade, finalSignal);
      } else {
        // 同方向シグナル → スキップ
        log.info("[PAPER] スキップ（同方向ポジションあり）: symbol={} side={} signal={}",
            symbol, currentSide, finalSignal);
      }
    }
  }

  // -----------------------------------------------------------------------
  // Private
  // -----------------------------------------------------------------------

  /**
   * 新規建て（BUY / SELL 共通）。
   */
  private void openNewPosition(String symbol, BigDecimal price,
      AppProperties.Trade trade, TradeSignal.Signal signal) {

    Side side = signal == TradeSignal.Signal.BUY ? Side.BUY : Side.SELL;
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

  /**
   * 損切り・利確チェック。
   * SELL建て（空売り）は価格上昇が損失になるため符号を反転して計算する。
   *
   * @return クローズした場合 true
   */
  private boolean checkStopOrTakeProfit(
      Position pos, BigDecimal currentPrice, AppProperties.Trade trade) {

    BigDecimal openPrice = pos.getOpenPrice();

    // 買建て: 価格上昇=利益 / 売建て: 価格上昇=損失（符号反転）
    double changePercent = currentPrice.subtract(openPrice)
        .divide(openPrice, MC)
        .multiply(BigDecimal.valueOf(100))
        .doubleValue();

    if (pos.getSide() == Side.SELL) {
      changePercent = -changePercent;
    }

    if (changePercent <= -Math.abs(trade.getStopLossPercent())) {
      log.info("[PAPER] 損切り発動: symbol={} side={} openPrice={} currentPrice={} change={}%",
          pos.getSymbol(), pos.getSide(), openPrice, currentPrice,
          String.format("%.2f", changePercent));
      closePosition(pos, currentPrice, CloseReason.STOP_LOSS);
      return true;
    }

    if (changePercent >= trade.getTakeProfitPercent()) {
      log.info("[PAPER] 利確発動: symbol={} side={} openPrice={} currentPrice={} change={}%",
          pos.getSymbol(), pos.getSide(), openPrice, currentPrice,
          String.format("%.2f", changePercent));
      closePosition(pos, currentPrice, CloseReason.TAKE_PROFIT);
      return true;
    }

    return false;
  }

  /**
   * ポジションをクローズして損益を記録する。
   * 売建て（空売り）の損益は (openPrice - closePrice) × amount。
   */
  private void closePosition(Position pos, BigDecimal closePrice, CloseReason reason) {
    OffsetDateTime now = now();

    // 損益計算: 買建て=(close-open)*amount / 売建て=(open-close)*amount
    BigDecimal profitJpy;
    Side closeSide;
    if (pos.getSide() == Side.BUY) {
      profitJpy = closePrice.subtract(pos.getOpenPrice())
          .multiply(pos.getAmount(), MC)
          .setScale(0, RoundingMode.HALF_UP);
      closeSide = Side.SELL;
    } else {
      profitJpy = pos.getOpenPrice().subtract(closePrice)
          .multiply(pos.getAmount(), MC)
          .setScale(0, RoundingMode.HALF_UP);
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
        .note("ペーパートレード 決済" + closeSide.name() + " reason=" + reason)
        .build();

    tradeRepository.saveTradeHistory(th);
    tradeRepository.closePosition(pos.getId(), now, closePrice, profitJpy, reason);

    log.info("[PAPER] 決済{}: symbol={} openPrice={} closePrice={} profitJpy={} reason={}",
        closeSide, pos.getSymbol(), pos.getOpenPrice(), closePrice, profitJpy, reason);
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneId.of(props.getScheduler().getTimezone()));
  }
}
