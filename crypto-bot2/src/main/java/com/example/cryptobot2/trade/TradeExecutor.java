package com.example.cryptobot2.trade;

import com.example.cryptobot2.client.GmoCoinPrivateApiClient;
import com.example.cryptobot2.config.AppProperties;
import com.example.cryptobot2.exception.CryptoBotException;
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
 * 本番レバレッジ売買実行クラス。空売り・ドテン対応。
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
public class TradeExecutor {

  private static final MathContext MC = new MathContext(10, RoundingMode.HALF_UP);

  private final AppProperties props;
  private final GmoCoinPrivateApiClient privateApiClient;
  private final TradeRepository tradeRepository;

  public void execute(TradeSignal signal) {
    String symbol    = signal.getSymbol();
    String levSymbol = toLeverageSymbol(symbol);
    BigDecimal price = signal.getPrice();
    AppProperties.Trade trade = props.getTrade();
    TradeSignal.Signal finalSignal = signal.getFinalSignal();

    if (finalSignal == TradeSignal.Signal.HOLD) {
      log.debug("[本番] HOLD: symbol={} price={}", symbol, price);
    }

    // --- 損切り・利確チェック（最優先）---
    Optional<Position> openPos = tradeRepository.findOpenPosition(symbol, false);
    if (openPos.isPresent()) {
      boolean closed = checkStopOrTakeProfit(openPos.get(), price, trade, levSymbol);
      if (closed) {
        // 損切り・利確はドテンなしで終了
        return;
      }
    }

    if (finalSignal == TradeSignal.Signal.HOLD) {
      return;
    }

    // ポジション再取得
    openPos = tradeRepository.findOpenPosition(symbol, false);

    if (openPos.isEmpty()) {
      // ポジションなし → 新規建て
      openNewPosition(symbol, levSymbol, price, trade, finalSignal);
    } else {
      Position pos = openPos.get();
      Side currentSide = pos.getSide();
      boolean isReverse =
          (currentSide == Side.BUY  && finalSignal == TradeSignal.Signal.SELL) ||
          (currentSide == Side.SELL && finalSignal == TradeSignal.Signal.BUY);

      if (isReverse) {
        // 逆方向 → 決済してドテン
        log.info("[本番] ドテン: symbol={} {} → {}",
            symbol, currentSide, finalSignal);
        closeLeveragePosition(pos, price, levSymbol, CloseReason.SIGNAL);
        openNewPosition(symbol, levSymbol, price, trade, finalSignal);
      } else {
        log.info("[本番] スキップ（同方向ポジションあり）: symbol={} side={} signal={}",
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
  private void openNewPosition(String symbol, String levSymbol,
      BigDecimal price, AppProperties.Trade trade, TradeSignal.Signal signal) {

    Side side = signal == TradeSignal.Signal.BUY ? Side.BUY : Side.SELL;
    String apiSide   = side.name(); // "BUY" or "SELL"
    BigDecimal size  = trade.getSize();
    BigDecimal amountJpy = price.multiply(size, MC).setScale(0, RoundingMode.HALF_UP);

    String orderId = null;
    TradeStatus status = TradeStatus.SUCCESS;
    String note = null;

    try {
      orderId = privateApiClient.openLeverageOrder(levSymbol, apiSide, size);
    } catch (CryptoBotException e) {
      status = TradeStatus.FAILED;
      note = "新規建て失敗: " + e.getMessage();
      log.error("[本番] {}新規建て失敗: levSymbol={} error={}", side, levSymbol, e.getMessage(), e);
    }

    OffsetDateTime now = now();

    TradeHistory th = TradeHistory.builder()
        .symbol(symbol)
        .tradeTime(now)
        .side(side)
        .price(price)
        .amount(size)
        .amountJpy(amountJpy)
        .fee(BigDecimal.ZERO)
        .status(status)
        .orderId(orderId)
        .note(note != null ? note : "レバレッジ新規" + side.name() + " levSymbol=" + levSymbol)
        .build();

    long historyId = tradeRepository.saveTradeHistory(th);

    if (status == TradeStatus.SUCCESS) {
      Position pos = Position.builder()
          .symbol(symbol)
          .side(side)
          .openTime(now)
          .openPrice(price)
          .amount(size)
          .amountJpy(amountJpy)
          .isPaper(false)
          .tradeHistoryId(historyId)
          .build();
      tradeRepository.openPosition(pos);
      log.info("[本番] {}新規建て完了: levSymbol={} price={} size={} orderId={}",
          side, levSymbol, price, size, orderId);
    }
  }

  /**
   * 損切り・利確チェック。
   * SELL建て（空売り）は価格上昇が損失になるため符号を反転して計算する。
   */
  private boolean checkStopOrTakeProfit(
      Position pos, BigDecimal currentPrice, AppProperties.Trade trade, String levSymbol) {

    BigDecimal openPrice = pos.getOpenPrice();
    double changePercent = currentPrice.subtract(openPrice)
        .divide(openPrice, MC)
        .multiply(BigDecimal.valueOf(100))
        .doubleValue();

    if (pos.getSide() == Side.SELL) {
      changePercent = -changePercent;
    }

    if (changePercent <= -Math.abs(trade.getStopLossPercent())) {
      log.info("[本番] 損切り発動: symbol={} side={} change={}%",
          pos.getSymbol(), pos.getSide(), String.format("%.2f", changePercent));
      closeLeveragePosition(pos, currentPrice, levSymbol, CloseReason.STOP_LOSS);
      return true;
    }

    if (changePercent >= trade.getTakeProfitPercent()) {
      log.info("[本番] 利確発動: symbol={} side={} change={}%",
          pos.getSymbol(), pos.getSide(), String.format("%.2f", changePercent));
      closeLeveragePosition(pos, currentPrice, levSymbol, CloseReason.TAKE_PROFIT);
      return true;
    }

    return false;
  }

  /**
   * 一括決済。
   * 売建て（空売り）の損益は (openPrice - closePrice) × amount。
   */
  private void closeLeveragePosition(
      Position pos, BigDecimal closePrice, String levSymbol, CloseReason reason) {

    String symbol    = pos.getSymbol();
    String buildSide = pos.getSide().name(); // closeBulkOrder は建玉 side を指定

    // 決済側の side（買建て→SELL で決済 / 売建て→BUY で決済）
    Side closeSide = pos.getSide() == Side.BUY ? Side.SELL : Side.BUY;

    String orderId = null;
    TradeStatus status = TradeStatus.SUCCESS;
    String note = "reason=" + reason;

    try {
      orderId = privateApiClient.closeBulkOrder(levSymbol, buildSide);
    } catch (CryptoBotException e) {
      status = TradeStatus.FAILED;
      note = "一括決済失敗: " + e.getMessage();
      log.error("[本番] 一括決済失敗: levSymbol={} error={}", levSymbol, e.getMessage(), e);
    }

    BigDecimal profitJpy;
    if (pos.getSide() == Side.BUY) {
      profitJpy = closePrice.subtract(pos.getOpenPrice())
          .multiply(pos.getAmount(), MC)
          .setScale(0, RoundingMode.HALF_UP);
    } else {
      // 空売り: 建値 - 決済値
      profitJpy = pos.getOpenPrice().subtract(closePrice)
          .multiply(pos.getAmount(), MC)
          .setScale(0, RoundingMode.HALF_UP);
    }

    OffsetDateTime now = now();

    TradeHistory th = TradeHistory.builder()
        .symbol(symbol)
        .tradeTime(now)
        .side(closeSide)
        .price(closePrice)
        .amount(pos.getAmount())
        .amountJpy(pos.getAmountJpy())
        .fee(BigDecimal.ZERO)
        .status(status)
        .orderId(orderId)
        .note(note + " levSymbol=" + levSymbol)
        .build();

    tradeRepository.saveTradeHistory(th);

    if (status == TradeStatus.SUCCESS) {
      tradeRepository.closePosition(pos.getId(), now, closePrice, profitJpy, reason);
      log.info("[本番] 決済{}: levSymbol={} openPrice={} closePrice={} profitJpy={} reason={}",
          closeSide, levSymbol, pos.getOpenPrice(), closePrice, profitJpy, reason);
    }
  }

  private String toLeverageSymbol(String symbol) {
    String suffix = props.getTrade().getLeverageSymbolSuffix();
    if (suffix == null || suffix.isBlank()) return symbol;
    if (symbol.endsWith(suffix)) return symbol;
    return symbol + suffix;
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneId.of(props.getScheduler().getTimezone()));
  }
}
