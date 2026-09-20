package com.example.cryptobot2.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * trade_signal テーブルのエンティティ。
 * RSI・MACD・DMI・RCI・TEMA の各指標値とシグナル、総合判定を保持する。
 */
@Data
@Builder
public class TradeSignal {

  private String symbol;
  private OffsetDateTime signalTime;
  private BigDecimal price;

  // --- RSI ---
  private BigDecimal rsi;
  private Integer rsiPeriod;
  private Signal rsiSignal;

  // --- MACD ---
  private BigDecimal macd;
  private BigDecimal macdSignal;
  private BigDecimal macdHistogram;
  private Integer macdFast;
  private Integer macdSlow;
  private Integer macdSignalPeriod;
  private CrossSignal macdCross;

  // --- DMI ---
  private BigDecimal dmiPlus;
  private BigDecimal dmiMinus;
  private BigDecimal adx;
  private Integer dmiPeriod;
  private Signal dmiSignal;

  // --- RCI ---
  private BigDecimal rci;
  private Integer rciPeriod;
  private Signal rciSignal;

  // --- TEMA 短期 ---
  private BigDecimal temaFast;
  private Integer temaFastPeriod;

  // --- TEMA 長期 ---
  private BigDecimal temaSlow;
  private Integer temaSlowPeriod;

  // --- TEMA クロス ---
  private CrossSignal temaCross;

  // --- 総合シグナル ---
  @Builder.Default
  private Signal finalSignal = Signal.HOLD;

  /** 買い/売り/様子見シグナル */
  public enum Signal {
    BUY, SELL, HOLD
  }

  /** クロスシグナル */
  public enum CrossSignal {
    GOLDEN, DEAD, HOLD
  }
}
