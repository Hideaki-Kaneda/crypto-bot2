# CLAUDE.md — crypto-bot2

> このファイルは Claude Code 向けのプロジェクト仕様書です。
> 作業開始前に必ず読み込んでください。

---

## プロジェクト概要

GMOコインの BTC_JPY レバレッジ取引を自動化する Java バッチアプリケーション。

- KLine（ローソク足）データを定期取得して PostgreSQL に保存
- TEMA(5/9) クロスを主軸とした売買シグナル判定
- **signal-reverse=true**: GC→売建て（空売り）/ DC→買建て の逆張り戦略
- EMA20 トレンドフィルターでエントリー方向を制限
- ペーパートレード（模擬売買）と本番売買を切り替え可能
- バックテスト機能（kline_data を使った過去データシミュレーション）

---

## 技術スタック

| 項目 | 内容 |
|------|------|
| 言語 | Java 21 |
| ビルド | Maven（`<layout>ZIP</layout>` → PropertiesLauncher 使用）|
| フレームワーク | Spring Boot 3.3 |
| DB | PostgreSQL 15+（JdbcTemplate / HikariCP）|
| スケジューラ | Spring `@Scheduled`（cron 式）|
| 暗号化 | AES-256-GCM + PBKDF2WithHmacSHA256 |
| ログ | Logback + SLF4J（`@Slf4j`）|

---

## ディレクトリ構成

```
crypto-bot2/
├── pom.xml
├── CLAUDE.md
├── scripts/
│   └── encrypt-secrets.bat
└── src/main/
    ├── java/com/example/cryptobot2/
    │   ├── CryptoBot2Application.java      # エントリポイント（--backtest オプション対応）
    │   ├── backtest/
    │   │   └── BacktestEngine.java         # バックテストエンジン
    │   ├── client/
    │   │   ├── GmoCoinApiClient.java        # Public API（KLine取得）
    │   │   └── GmoCoinPrivateApiClient.java # Private API（注文・残高）
    │   ├── config/
    │   │   ├── AppConfig.java               # Bean定義・起動時復号
    │   │   └── AppProperties.java           # @ConfigurationProperties（gmo.*）
    │   ├── indicator/
    │   │   ├── EmaCalculator.java           # EMA 計算
    │   │   ├── RsiCalculator.java
    │   │   ├── MacdCalculator.java
    │   │   ├── DmiCalculator.java           # calculate(highs, lows, closes, period, adxPeriod)
    │   │   ├── RciCalculator.java
    │   │   └── TemaCalculator.java          # calculatePair(closes, fastPeriod, slowPeriod)
    │   ├── model/
    │   │   ├── KlineData.java
    │   │   ├── KlineRecord.java
    │   │   ├── TradeSignal.java             # Signal・CrossSignal enum / emaBullish・emaBearish フィールド含む
    │   │   ├── TradeHistory.java            # profitJpy フィールド含む
    │   │   ├── Position.java
    │   │   └── BacktestResult.java
    │   ├── repository/
    │   │   ├── KlineRepository.java
    │   │   ├── TradeSignalRepository.java   # fetchPrevTemaFast・fetchPrevSignal・isConsecutiveSignal
    │   │   ├── TradeRepository.java         # trade_history に profit_jpy 保存対応済み
    │   │   ├── BacktestRepository.java      # existsByGmoDate（JST 06:00 基準）
    │   │   └── BacktestResultRepository.java
    │   ├── scheduler/
    │   │   ├── KlineScheduler.java          # KLine 定期取得
    │   │   └── TradeScheduler.java          # 自動売買定期実行
    │   ├── service/
    │   │   ├── KlineService.java            # JST 06:05 基準の日付切替
    │   │   └── EncryptionService.java
    │   ├── strategy/
    │   │   └── TradingStrategy.java         # TEMA クロス + EMA フィルター + signal-reverse
    │   ├── trade/
    │   │   ├── PaperTradeExecutor.java      # ペーパートレード実行
    │   │   └── TradeExecutor.java           # 本番レバレッジ売買実行
    │   └── util/
    │       ├── EncryptCli.java
    │       └── ScheduleGuard.java           # メンテナンス時間・日付切替判定
    └── resources/
        ├── application.properties
        ├── secret.properties                # 暗号化済み API KEY/SECRET（Git 除外）
        ├── schema.sql
        └── logback-spring.xml
```

---

## 決定済み設定（application.properties）

```properties
# 銘柄・足種
gmo.kline.symbols=BTC_JPY
gmo.kline.interval=10min

# スケジューラ（10分ごとに売買判定）
gmo.scheduler.trade-cron=0 */10 * * * *
gmo.scheduler.timezone=Asia/Tokyo

# 自動売買
gmo.trade.paper-mode=true        # 本番移行時は false に変更
gmo.trade.size=0.1               # 発注数量（BTC）
gmo.trade.stop-loss-jpy=0        # 損切りなし
gmo.trade.flip-close-sell-min-bars=0
gmo.trade.flip-close-buy-min-bars=0
gmo.trade.leverage-symbol-suffix=_JPY

# TEMA（売買の主軸指標）
gmo.indicator.tema-fast-period=5
gmo.indicator.tema-slow-period=9

# EMA トレンドフィルター
gmo.indicator.ema-trend-period=20    # TEMA_FAST と EMA の位置関係でエントリー制限
gmo.indicator.ema-trend-reverse=false # false=順張りフィルター

# シグナル反転（逆張り戦略）
gmo.indicator.signal-reverse=true    # GC→売建て / DC→買建て

# RCI 新規建てフィルター
gmo.indicator.rci-entry-min=-60.0
gmo.indicator.rci-entry-max=60.0

# バックテスト初期残高
gmo.backtest.initial-balance-jpy=1000000
```

---

## 売買ロジック（TradingStrategy）

### シグナル判定フロー

```
1. TEMA(5/9) のクロスを計算
2. EMA20 フィルター適用
   - TEMA_FAST > EMA20 → emaBullish=true（BUY エントリー許可）
   - TEMA_FAST < EMA20 → emaBearish=true（SELL エントリー許可）
3. signal-reverse=true により BUY/SELL を反転
   - GC → SELL（空売り新規）
   - DC → BUY（買建て新規）
4. TEMA_FAST の符号反転（前々回→前回→今回の変化方向）で利確シグナル
   - マイナス→プラス反転 → BUY
   - プラス→マイナス反転 → SELL
   ※ 符号反転は signal-reverse 適用後なので利確方向も逆転済み
```

### Executor 動作（PaperTradeExecutor / TradeExecutor）

```
【ポジションなし + クロスあり】
  EMA フィルター確認
  RCI フィルター確認（-60〜60 範囲内はスキップ）
  符号反転があればクロス方向を逆転して新規建て
  → GC + 符号反転なし → 売建て新規
  → GC + 符号反転(+→-)  → 買建て新規（逆転）

【ポジションあり + 逆方向シグナル】
  クロスが来た場合: 利確 → 即逆方向新規建て
    ただし EMA フィルター・RCI フィルターを確認
  符号反転のみ: 利確のみ（新規なし）

【損切り】
  なし（stop-loss-jpy=0）
```

### TEMA クロス判定

```
乖離率 = (FAST - SLOW) / |FAST| × 100
+divergence-threshold% 以上 → GOLDEN クロス
-divergence-threshold% 以下 → DEAD クロス
```

---

## バックテスト結果（決定設定）

| 設定 | 合計損益 |
|------|---------|
| EMAなし + signal-reverse=true | +466,369円 |
| EMA20 + signal-reverse=true | **+762,295円** ← 採用 |
| EMA23 + signal-reverse=false | +267,800円 |

**月別詳細（EMA20 + signal-reverse=true / BTC_JPY / 0.1BTC / 10分足）**

| 月 | 勝 | 負 | 損益 | 最大損失 |
|----|----|----|------|---------|
| 2026-01 | 243 | 223 | +244,241円 | -17,628円 |
| 2026-02 | 207 | 195 |  +45,544円 | -38,638円 |
| 2026-03 | 220 | 207 |  +17,519円 | -24,024円 |
| 2026-04 | 233 | 191 |  +63,406円 | -20,414円 |
| 2026-05 | 235 | 211 | +151,870円 | -15,918円 |
| 2026-06 | 226 | 202 |  +37,406円 | -29,312円 |
| 2026-07 | 224 | 228 |  -10,964円 | -15,303円 |
| 2026-08 | 221 | 203 |  +70,170円 | -37,201円 |
| 2026-09 | 175 | 161 | +143,103円 | -42,494円 |
| **合計** | **1,984** | **1,821** | **+762,295円** | |

- **7月のみマイナス**（-10,964円）
- 勝率約 52%
- 月平均 +84,700円

---

## GMOコイン API 仕様

### KLine 取得（Public API）

```
GET https://api.coin.z.com/public/v1/klines
  ?symbol=BTC_JPY&interval=10min&date=YYYYMMDD
```

**日付切り替えルール（重要）:**
- GMO の1日分データは **JST 06:00〜翌 05:55**
- `ScheduleGuard.buildKlineDateParam`: JST 06:05 以降を当日、06:04 以前を前日として扱う
- バックテストの `existsByGmoDate`: JST 06:00〜翌 05:59 の UTC 範囲でチェック

### メンテナンス回避（ScheduleGuard）

- **毎週土曜 09:05〜10:59 JST** はスキップ
- `KlineScheduler` と `TradeScheduler` の冒頭で判定

---

## DBスキーマ概要

| テーブル | 用途 |
|---------|------|
| `kline_data` | ローソク足データ（UNIQUE: symbol, interval_type, open_time）|
| `trade_signal` | 指標値・シグナル記録（UNIQUE: symbol, signal_time）|
| `trade_history` | 売買履歴（profit_jpy カラムあり・決済時のみ値が入る）|
| `position` | ポジション管理（status: OPEN/CLOSED）|
| `backtest_run` | バックテスト実行サマリ |
| `backtest_trade` | バックテスト個別取引履歴 |

**既存 DB への追加が必要なカラム:**
```sql
ALTER TABLE trade_history
ADD COLUMN IF NOT EXISTS profit_jpy NUMERIC(20,2);
```

---

## 暗号化の仕組み

```
CRYPTO_MASTER_KEY（環境変数）
    ↓ PBKDF2WithHmacSHA256（310,000回）
  AES-256 秘密鍵
    ↓ AES/GCM/NoPadding（IV=12バイトランダム）
  ENC(Base64(IV + 暗号文))
```

- `AppConfig.@PostConstruct` で起動時に `secret.properties` の `ENC(...)` を復号
- `secret.properties` 更新後は **必ず再ビルド**
- 暗号化: `scripts\encrypt-secrets.bat YOUR_API_KEY YOUR_API_SECRET`

---

## 起動手順

```cmd
rem ビルド
mvn clean package -DskipTests

rem 通常起動（スケジューラ稼働）
set CRYPTO_MASTER_KEY=your_password
java -jar target\crypto-bot2-1.0.0.jar

rem バックテスト
set CRYPTO_MASTER_KEY=your_password
java -jar target\crypto-bot2-1.0.0.jar ^
     --backtest ^
     --start-date=2026-01-01 ^
     --end-date=2026-09-23
```

---

## ペーパートレード監視 SQL

```sql
-- 損益確認
SELECT 
    trade_time AT TIME ZONE 'Asia/Tokyo' AS time,
    side,
    price,
    profit_jpy,
    note,
    SUM(profit_jpy) OVER (ORDER BY trade_time) AS cumulative_profit
FROM trade_history
WHERE trade_time >= '2026-09-24 09:00:00+00'
  AND status = 'PAPER'
  AND profit_jpy IS NOT NULL
ORDER BY trade_time;

-- 現在のオープンポジション
SELECT symbol, side,
    open_time AT TIME ZONE 'Asia/Tokyo' AS open_time_jst,
    open_price, amount
FROM position
WHERE status = 'OPEN' AND is_paper = true;

-- 月別損益
SELECT 
    TO_CHAR(trade_time AT TIME ZONE 'Asia/Tokyo', 'YYYY-MM') AS month,
    COUNT(*) FILTER (WHERE profit_jpy > 0) AS win,
    COUNT(*) FILTER (WHERE profit_jpy < 0) AS loss,
    SUM(profit_jpy) AS total_profit
FROM trade_history
WHERE status = 'PAPER' AND profit_jpy IS NOT NULL
GROUP BY 1 ORDER BY 1;
```

---

## コーディング規約

- パッケージ: `com.example.cryptobot2`
- Lombok `@Slf4j` でロギング（`System.out.println` 禁止）
- 設定値はすべて `AppProperties` 経由（定数の直書き禁止）
- 例外は `CryptoBotException` にラップ
- スケジューラの `run()` は `Throwable` レベルでキャッチ（スレッド死亡防止）
- API KEY・シークレットはログ出力禁止

## .gitignore 必須項目

```
src/main/resources/secret.properties
target/
*.log
logs/
```
