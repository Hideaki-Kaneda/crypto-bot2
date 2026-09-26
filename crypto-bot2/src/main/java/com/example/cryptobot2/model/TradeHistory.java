package com.example.cryptobot2.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * trade_history テーブルのエンティティ。
 * 本番・ペーパートレード共通（status で区別）。
 */
@Data
@Builder
public class TradeHistory {

  private Long id;
  private String symbol;
  private OffsetDateTime tradeTime;
  private Side side;
  private BigDecimal price;
  private BigDecimal amount;
  private BigDecimal amountJpy;
  private BigDecimal fee;
  private TradeStatus status;
  private String orderId;
  private Long signalId;
  private String note;

  private BigDecimal profitJpy;
  
  public enum Side {
    BUY, SELL
  }

  public enum TradeStatus {
    SUCCESS, PAPER, FAILED
  }
}
