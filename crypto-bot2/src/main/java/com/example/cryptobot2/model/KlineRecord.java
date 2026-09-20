package com.example.cryptobot2.model;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import lombok.Builder;
import lombok.Data;

/**
 * kline_data テーブルへのインサート用エンティティ。
 * openTime は UTC の OffsetDateTime で保持する。
 */
@Data
@Builder
public class KlineRecord {

  /** 銘柄コード（例: BTC, ETH） */
  private String symbol;

  /** 時間軸（例: 1hour, 1day） */
  private String intervalType;

  /** ローソク足の開始時刻（UTC） */
  private OffsetDateTime openTime;

  private BigDecimal open;
  private BigDecimal high;
  private BigDecimal low;
  private BigDecimal close;
  private BigDecimal volume;
}
