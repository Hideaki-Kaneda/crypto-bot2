package com.example.cryptobot2.config;

import java.math.BigDecimal;
import java.util.List;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * application.properties の gmo.* 設定をバインドするプロパティクラス。
 */
@Data
@Component
@ConfigurationProperties(prefix = "gmo")
public class AppProperties {

  private Api api = new Api();
  private Kline kline = new Kline();
  private Scheduler scheduler = new Scheduler();

  @Data
  public static class Api {
    /** GMOコイン Public API ベースURL */
    private String baseUrl = "https://api.coin.z.com/public";

    /** 暗号化済み API KEY（secret.properties から読み込み） */
    private String key;

    /** 暗号化済み API SECRET（secret.properties から読み込み） */
    private String secret;

    private Retry retry = new Retry();
    private Timeout timeout = new Timeout();

    @Data
    public static class Retry {
      private int maxAttempts = 3;
      private long delayMs = 1000;
    }

    @Data
    public static class Timeout {
      private int connectMs = 5000;
      private int readMs = 10000;
    }
  }

  @Data
  public static class Kline {
    /** 取得対象の銘柄リスト（カンマ区切り → List） */
    private List<String> symbols = List.of("BTC");

    /**
     * 取得インターバル。GMO API の interval パラメータ値。
     * 選択肢: 1min / 5min / 10min / 15min / 30min / 1hour / 4hour / 8hour / 12hour / 1day / 1week / 1month
     */
    private String interval = "1hour";
  }

  @Data
  public static class Scheduler {
    /** cron 式（Spring Scheduler 形式: 秒 分 時 日 月 曜） */
    private String cron = "0 0 * * * *";

    /** タイムゾーン */
    private String timezone = "Asia/Tokyo";

    /** 自動売買スケジューラ cron 式（デフォルト: 10分ごと） */
    private String tradeCron = "0 */10 * * * *";
  }

  private Trade trade = new Trade();

  @Data
  public static class Trade {
    /** true=ペーパートレード / false=本番売買 */
    private boolean paperMode = true;

    /**
     * 1回の発注数量（BTC）。
     * レバレッジ取引は数量（BTC単位）で指定する。
     * 例: 0.01 → 0.01 BTC
     */
    private BigDecimal size = new BigDecimal("0.01");

    /** ポジション重複防止 */
    private boolean positionManagement = true;

    /**
     * 損切りライン（円）。
     * 含み損がこの金額を超えたら損切りする。負の値で指定。
     * 例: -5000 → 含み損が -5,000円 以下になったら損切り
     * 0 = 損切りなし。
     */
    private java.math.BigDecimal stopLossJpy = new java.math.BigDecimal("-5000");

    /**
     * 符号反転（売建て利確）の最低保有本数。
     * 価格反転(-): TEMA_FAST がマイナス→プラスに反転した時（売建て利確）。
     * 新規建てからこの本数未満の場合は符号反転による利確をスキップする。
     * 0 = 制限なし。
     */
    private int flipCloseSellMinBars = 5;

    /**
     * 符号反転（買建て利確）の最低保有本数。
     * 価格反転(+): TEMA_FAST がプラス→マイナスに反転した時（買建て利確）。
     * 新規建てからこの本数未満の場合は符号反転による利確をスキップする。
     * 0 = 制限なし。
     */
    private int flipCloseBuyMinBars = 4;

    /**
     * 1日の損益上限（JPY）。
     * 当日の累積損益がこの値以上になったらその日の新規エントリーを停止する。
     * 0 = 制限なし。
     */
    private java.math.BigDecimal dailyProfitLimit = new java.math.BigDecimal("0");

    /**
     * 1日の損益下限（JPY）。
     * 当日の累積損益がこの値以下になったらその日の新規エントリーを停止する。
     * 0 = 制限なし。
     */
    private java.math.BigDecimal dailyLossLimit = new java.math.BigDecimal("0");

    /**
     * レバレッジ取引の銘柄コードサフィックス。
     * GMOコインのレバレッジ銘柄は "BTC_JPY" 形式。
     */
    private String leverageSymbolSuffix = "_JPY";
  }

  private Backtest backtest = new Backtest();

  private Indicator indicator = new Indicator();

  @Data
  public static class Backtest {
    /** バックテスト初期残高（JPY）。開始日・終了日は起動引数 --start-date / --end-date で指定する。 */
    private BigDecimal initialBalanceJpy = new BigDecimal("1000000");
  }

  @Data
  public static class Indicator {
    // RSI
    private int rsiPeriod = 14;
    private double rsiOversold  = 20.0;  // RSI < 20 で BUY 判定
    private double rsiOverbought = 80.0; // RSI > 80 で SELL 判定

    // MACD
    private int macdFastPeriod = 12;
    private int macdSlowPeriod = 26;
    private int macdSignalPeriod = 9;

    // DMI
    private int dmiPeriod = 14;
    private double dmiAdxThreshold = 25.0;
    /** ADX の平滑化期間（DI 期間とは独立して設定可能） */
    private int adxPeriod = 9;
    /**
     * 新規建て時の RCI フィルター範囲。
     * RCI がこの範囲内（rciEntryMin ≤ RCI ≤ rciEntryMax）の場合は
     * 横ばい相場とみなし新規建てを行わない。利確（決済）には影響しない。
     */
    private double rciEntryMin = -60.0;
    private double rciEntryMax =  60.0;

    /**
     * トレンドフィルター用 EMA 期間。
     * TEMA_FAST が EMA より上 → BUY のみ許可（SELL エントリー禁止）
     * TEMA_FAST が EMA より下 → SELL のみ許可（BUY エントリー禁止）
     * 0 = フィルターなし。
     */
    private int emaTrendPeriod = 20;

    /**
     * EMA トレンドフィルターの方向を反転する。
     * true の場合:
     *   TEMA_FAST > EMA → SELL のみ許可（逆張り）
     *   TEMA_FAST < EMA → BUY のみ許可（逆張り）
     */
    private boolean emaTrendReverse = false;

    /**
     * 売買シグナルを反転する。
     * true の場合: GC → SELL（空売り）/ DC → BUY（買い）
     * 符号反転も逆転する: マイナス→プラス → SELL / プラス→マイナス → BUY
     */
    private boolean signalReverse = false;
    // RCI
    private int rciPeriod = 5;
    private double rciOversold = -80.0;
    private double rciOverbought = 80.0;

    // TEMA
    private int temaFastPeriod = 12;
    private int temaSlowPeriod = 26;

    /**
     * TEMA 乖離率しきい値（%）。
     * (FAST - SLOW) / FAST * 100 がこの値以上 → BUY
     * 負のしきい値以下 → SELL
     * 例: 0.1 → +0.1% 以上で BUY、-0.1% 以下で SELL
     */
    private double temaDivergenceThresholdPercent = 0.1;

    /** 指標データ取得件数（DB から取得する最大ローソク足数） */
    private int priceHistorySize = 100;
  }
}
