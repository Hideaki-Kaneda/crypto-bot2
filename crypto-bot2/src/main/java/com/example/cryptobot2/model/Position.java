package com.example.cryptobot2.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * position テーブルのエンティティ。
 * OPEN/CLOSED のポジションを管理する。
 */
@Data
@Builder
public class Position {

  private Long id;
  private String symbol;
  private TradeHistory.Side side;
  private OffsetDateTime openTime;
  private BigDecimal openPrice;
  private BigDecimal amount;
  private BigDecimal amountJpy;
  private OffsetDateTime closeTime;
  private BigDecimal closePrice;
  private BigDecimal profitJpy;

  @Builder.Default
  private PositionStatus status = PositionStatus.OPEN;

  private CloseReason closeReason;
  private boolean isPaper;
  private Long tradeHistoryId;

  public enum PositionStatus {
    OPEN, CLOSED
  }

  public enum CloseReason {
    SIGNAL, STOP_LOSS, TAKE_PROFIT
  }
}
