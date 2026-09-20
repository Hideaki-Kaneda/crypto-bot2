package com.example.cryptobot2.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import lombok.Data;

/**
 * GMOコイン API /v1/klines レスポンス全体。
 *
 * <pre>
 * {
 *   "status": 0,
 *   "data": [ { "openTime": 1618905600000, "open": "5000000", ... } ],
 *   "responsetime": "2021-04-20T10:00:00.000Z"
 * }
 * </pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class KlineData {

  /** 0 = 正常、それ以外はエラー */
  private int status;

  private List<KlineEntry> data;

  private String responsetime;

  /** 個々のローソク足エントリ */
  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class KlineEntry {

    /** 開始時刻（UNIX時間ミリ秒） */
    private long openTime;

    /** 始値（文字列） */
    private String open;

    /** 高値（文字列） */
    private String high;

    /** 安値（文字列） */
    private String low;

    /** 終値（文字列） */
    private String close;

    /** 取引量（文字列） */
    private String volume;
  }
}
