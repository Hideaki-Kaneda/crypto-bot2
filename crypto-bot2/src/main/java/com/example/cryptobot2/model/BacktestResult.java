package com.example.cryptobot2.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import lombok.Builder;
import lombok.Data;

/** バックテスト実行サマリ */
@Data
@Builder
public class BacktestResult {

  private String symbol;
  private String intervalType;
  private LocalDate startDate;
  private LocalDate endDate;
  private BigDecimal initialBalanceJpy;
  private BigDecimal finalBalanceJpy;
  private BigDecimal totalProfitJpy;
  private int tradeCount;
  private int winCount;
  private int lossCount;
  private BigDecimal winRate;
  private BigDecimal maxDrawdownJpy;
  private OffsetDateTime executedAt;

  /** バックテスト個別取引 */
  @Data
  @Builder
  public static class Trade {
    private Long runId;
    private String symbol;
    private OffsetDateTime tradeTime;
    private TradeHistory.Side side;
    private BigDecimal price;
    private BigDecimal size;
    private BigDecimal profitJpy;
    private BigDecimal balanceJpy;
    private Position.CloseReason closeReason;
    private String signalConditions;
  }
}
