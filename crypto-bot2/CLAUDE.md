# CLAUDE.md — crypto-bot2

> このファイルは Claude Code 向けのプロジェクト仕様書です。
> 作業開始前に必ず読み込んでください。

---

## プロジェクト概要

GMOコインのレバレッジ取引（信用取引）を自動化する Java バッチアプリケーション。

- KLine（ローソク足）データを定期取得して PostgreSQL に保存
- RSI・MACD・DMI・RCI・TEMA の5指標を用いた売買シグナル判定
- 新規買建て・新規売建て（空売り）・ドテン売買に対応
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
│   └── encrypt-secrets.bat           # API KEY/SECRET 暗号化ユーティリティ（Windows）
└── src/main/
    ├── java/com/example/cryptobot2/
    │   ├── CryptoBot2Application.java  # エントリポイント（--backtest オプション対応）
    │   ├── backtest/
    │   │   └── BacktestEngine.java     # バックテストエンジン
    │   ├── client/
    │   │   ├── GmoCoinApiClient.java   # Public API（KLine取得）
    │   │   └── GmoCoinPrivateApiClient.java  # Private API（注文・残高）
    │   ├── config/
    │   │   ├── AppConfig.java          # Bean定義・起動時復号（@PostConstruct）
    │   │   └── AppProperties.java      # @ConfigurationProperties（gmo.*）
    │   ├── exception/
    │   │   └── CryptoBotException.java # アプリ共通例外
    │   ├── indicator/
    │   │   ├── RsiCalculator.java
    │   │   ├── MacdCalculator.java
    │   │   ├── DmiCalculator.java      # calculate(highs, lows, closes, period, adxPeriod)
    │   │   ├── RciCalculator.java
    │   │   └── TemaCalculator.java     # calculatePair(closes, fastPeriod, slowPeriod)
    │   ├── model/
    │   │   ├── KlineData.java          # API レスポンス JSON モデル
    │   │   ├── KlineRecord.java        # kline_data エンティティ
    │   │   ├── TradeSignal.java        # trade_signal エンティティ（Signal・CrossSignal enum 含む）
    │   │   ├── TradeHistory.java       # trade_history エンティティ
    │   │   ├── Position.java           # position エンティティ
    │   │   └── BacktestResult.java     # バックテスト結果モデル
    │   ├── repository/
    │   │   ├── KlineRepository.java        # kline_data UPSERT
    │   │   ├── TradeSignalRepository.java  # trade_signal 保存・前回シグナル取得
    │   │   ├── TradeRepository.java        # trade_history / position CRUD
    │   │   ├── BacktestRepository.java     # バックテスト用 kline_data 取得
    │   │   └── BacktestResultRepository.java # バックテスト結果保存
    │   ├── scheduler/
    │   │   ├── KlineScheduler.java     # KLine 定期取得（gmo.scheduler.cron）
    │   │   └── TradeScheduler.java     # 自動売買定期実行（gmo.scheduler.trade-cron）
    │   ├── service/
    │   │   ├── KlineService.java       # KLine 取得・保存ロジック
    │   │   └── EncryptionService.java  # AES-256-GCM 暗号化/復号
    │   ├── strategy/
    │   │   └── TradingStrategy.java    # 売買シグナル判定・trade_signal 保存
    │   ├── trade/
    │   │   ├── PaperTradeExecutor.java # ペーパートレード実行
    │   │   └── TradeExecutor.java      # 本番レバレッジ売買実行
    │   └── util/
    │       ├── EncryptCli.java         # 暗号化 CLI（PropertiesLauncher 経由）
    │       └── ScheduleGuard.java      # メンテナンス時間・日付切替判定
    └── resources/
        ├── application.properties      # メイン設定（Git 管理 OK）
        ├── secret.properties           # 暗号化済み API KEY/SECRET（Git 除外）
        ├── schema.sql                  # DB スキーマ（初回起動時自動実行）
        └── logback-spring.xml          # ログ設定
```

---

## 設定ファイル仕様

### `application.properties`

```properties
# GMOコイン Public API
gmo.api.base-url=https://api.coin.z.com/public

# KLine 取得設定（レバレッジ銘柄は _JPY 形式）
gmo.kline.symbols=BTC_JPY
gmo.kline.interval=1hour        # 1min/5min/10min/15min/30min/1hour/4hour/8hour/12hour/1day/1week/1month

# スケジューラ
gmo.scheduler.cron=0 0 * * * *          # KLine 取得（毎時0分）
gmo.scheduler.trade-cron=0 */10 * * * * # 自動売買（10分ごと）
gmo.scheduler.timezone=Asia/Tokyo

# API リトライ・タイムアウト
gmo.api.retry.max-attempts=3
gmo.api.retry.delay-ms=1000
gmo.api.timeout.connect-ms=5000
gmo.api.timeout.read-ms=10000

# 自動売買（レバレッジ取引）
gmo.trade.paper-mode=true          # true=ペーパー / false=本番
gmo.trade.size=0.01                # 発注数量（BTC 単位）
gmo.trade.stop-loss-percent=3.0    # 損切り（建値から -3% で発動）
gmo.trade.take-profit-percent=5.0  # 利確（建値から +5% で発動）
gmo.trade.position-management=true # ポジション重複防止
gmo.trade.leverage-symbol-suffix=_JPY  # BTC → BTC_JPY に変換

# 指標パラメータ
gmo.indicator.rsi-period=14
gmo.indicator.rsi-oversold=20.0
gmo.indicator.rsi-overbought=80.0
gmo.indicator.macd-fast-period=12
gmo.indicator.macd-slow-period=26
gmo.indicator.macd-signal-period=9
gmo.indicator.dmi-period=14        # +DI/-DI の平滑化期間
gmo.indicator.adx-period=9         # ADX の平滑化期間（DI とは独立）
gmo.indicator.dmi-adx-threshold=25.0
gmo.indicator.rci-period=5
gmo.indicator.rci-oversold=-80.0
gmo.indicator.rci-overbought=80.0
gmo.indicator.tema-fast-period=12
gmo.indicator.tema-slow-period=26
gmo.indicator.tema-divergence-threshold-percent=0.1
gmo.indicator.price-history-size=100

# バックテスト（日付は起動引数で指定）
gmo.backtest.initial-balance-jpy=1000000
```

### `secret.properties`（Git 除外）

```properties
gmo.api.key=ENC(...)      # 暗号化済み API KEY
gmo.api.secret=ENC(...)   # 暗号化済み API SECRET
```

- `ENC(...)` は `EncryptionService` が起動時に自動復号
- マスターパスワードは環境変数 `CRYPTO_MASTER_KEY` で渡す
- 暗号化コマンド:
  ```cmd
  set CRYPTO_MASTER_KEY=your_password
  scripts\encrypt-secrets.bat YOUR_API_KEY YOUR_API_SECRET
  ```

---

## GMOコイン API 仕様

### KLine 取得（Public API）

```
GET https://api.coin.z.com/public/v1/klines
  ?symbol=BTC_JPY
  &interval=1hour
  &date=YYYYMMDD
```

**日付切り替えルール（重要）:**
- GMO の1日分データは **JST 06:00〜翌 05:55**
- `date=20240914` → `2024/09/14 06:00 JST 〜 2024/09/15 05:55 JST`
- JST 06:05 以降を当日、06:04 以前を前日として `date` を生成（`ScheduleGuard.buildKlineDateParam`）

### 注文（Private API・レバレッジ）

| 操作 | エンドポイント | 主なパラメータ |
|------|-------------|--------------|
| 新規建て | POST `/v1/order` | `settleType=OPEN`, `executionType=MARKET`, `size`（BTC数量）|
| 一括決済 | POST `/v1/closeBulkOrder` | `side`=建玉の side（買建て→`BUY`）|
| キャンセル | POST `/v1/cancelOrder` | `orderId` |
| 建玉一覧 | GET `/v1/openPositions` | `symbol` |
| 残高 | GET `/v1/account/assets` | - |

**認証:** `API-KEY` / `API-TIMESTAMP` / `API-SIGN`（HMAC-SHA256）
**署名対象:** `timestamp + method + path + body`

---

## スケジューラ動作

### メンテナンス回避（`ScheduleGuard`）

- **毎週土曜 09:05〜10:59 JST** はスキップ（GMOコイン定期メンテ）
- `KlineScheduler.run()` と `TradeScheduler.run()` の冒頭で判定

### KLine 取得フロー（`KlineScheduler` → `KlineService`）

```
1. ScheduleGuard.isMaintenanceTime() → メンテ中はスキップ
2. buildDateParam(interval, zone)    → JST 06:05 基準で date 生成
3. GMO API fetchKlines()             → 全シンボルをループ（シンボル間 1 秒待機）
4. kline_data に UPSERT              → ON CONFLICT DO NOTHING（冪等）
```

### 自動売買フロー（`TradeScheduler` → `TradingStrategy` → `Executor`）

```
1. ScheduleGuard.isMaintenanceTime() → メンテ中はスキップ
2. kline_data から最新終値を取得
3. TradingStrategy.evaluate()        → 指標計算・シグナル判定・trade_signal 保存
4. paper-mode に応じて Executor を選択
   PaperTradeExecutor  → DB 記録のみ
   TradeExecutor       → GMO Private API で実際に発注
```

---

## 売買ロジック（`TradingStrategy`）

### 指標計算

| 指標 | クラス | 主な判定 |
|------|--------|---------|
| RSI | `RsiCalculator` | ≤20=BUY / ≥80=SELL |
| MACD | `MacdCalculator` | GOLDEN/DEAD/HOLD クロス判定 |
| DMI | `DmiCalculator` | -DI>+DI かつ ADX≥25=BUY / +DI>-DI かつ ADX≥25=SELL |
| RCI | `RciCalculator` | ≤-80=BUY / ≥80=SELL |
| TEMA | `TemaCalculator` | GOLDEN/DEAD クロス判定 |

**TEMA クロス判定:**
- 乖離率 = (FAST - SLOW) / FAST × 100
- +0.1% 以上 → SELL / -0.1% 以下 → BUY

**DMI 判定（逆張りロジック）:**
- BUY: `-DI > +DI` かつ `ADX ≥ 25`（下降トレンドへの反転狙い）
- SELL: `+DI > -DI` かつ `ADX ≥ 25`

### 売買シグナル（OR 条件）

**売りシグナル:**

| 番号 | 条件 |
|------|------|
| ① | RCI≥90 かつ 前回RCI≤60 かつ 前回MACDがGCでない |
| ② | RCI≥100 かつ 前回RCI≤70 かつ 前回MACDがGCでない |
| ③ | RSI > 80 かつ MACDがGC |
| ④ | MACDがGC かつ TEMAがGC かつ RSIシグナル=SELL |
| ⑤ | MACDがGC かつ 前回TEMAがGC |
| ⑥ | TEMAがGC かつ 前回MACDがGC |
| ⑦ | RCIシグナル=SELL かつ TEMAがGC |
| ⑨ | DMIシグナル=SELL かつ RCIシグナル=SELL かつ RSI≥65 |
| ⑩ | MACDがGC かつ TEMAがGC かつ DMIシグナル=SELL |

**買いシグナル（GC→DC に置換）:**

| 番号 | 条件 |
|------|------|
| ① | RCI≤-90 かつ 前回RCI≥-60 かつ 前回MACDがDCでない |
| ② | RCI≤-100 かつ 前回RCI≥-70 かつ 前回MACDがDCでない |
| ③ | RSI < 20 かつ MACDがDC |
| ④ | MACDがDC かつ TEMAがDC かつ RSIシグナル=BUY |
| ⑤ | MACDがDC かつ 前回TEMAがDC |
| ⑥ | TEMAがDC かつ 前回MACDがDC |
| ⑦ | RCIシグナル=BUY かつ TEMAがDC |
| ⑨ | DMIシグナル=BUY かつ RCIシグナル=BUY かつ RSI≤35 |
| ⑩ | MACDがDC かつ TEMAがDC かつ DMIシグナル=BUY |

### 連続シグナルによるドテン

同方向シグナル（HOLD 除く）が **3回連続** したら 3回目を逆方向に反転。
→ 現ポジション決済（利確）＋逆方向新規建て（ドテン）

### ポジション管理（`PaperTradeExecutor` / `TradeExecutor`）

```
【ポジションなし】
  BUY  → 買建て新規
  SELL → 売建て新規（空売り）

【買建て中】
  SELL → 買建て決済 → 売建て新規（ドテン）
  損切り/利確 → 買建て決済のみ

【売建て中】
  BUY  → 売建て決済 → 買建て新規（ドテン）
  損切り/利確 → 売建て決済のみ
```

**損益計算:**
- 買建て: `(closePrice - openPrice) × size`
- 売建て: `(openPrice - closePrice) × size`
- 損切り発動: `変動率 ≤ -stop-loss-percent`
- 利確発動: `変動率 ≥ take-profit-percent`

**本番注文（`TradeExecutor`）:**
- 新規建て: `closeBulkOrder` で一括決済（建玉の side を指定）
- 銘柄コード: `BTC` → `BTC_JPY`（`toLeverageSymbol` が自動変換、二重付与なし）

---

## バックテスト

### 起動方法

```cmd
# ビルド
mvn clean package -DskipTests

# バックテスト実行
java -jar target\crypto-bot2-1.0.0.jar ^
     --backtest ^
     --start-date=2024-01-01 ^
     --end-date=2024-12-31
```

`--start-date` / `--end-date` は必須。未指定の場合はエラー終了。
初期残高は `gmo.backtest.initial-balance-jpy` で設定。

### 処理フロー

```
1. 開始日〜終了日をループ
   → existsByGmoDate() で DB に存在チェック（JST 06:00〜翌 05:59 範囲）
   → 不足分を GMO API から取得して kline_data に保存

2. fetchRange() で全期間データを JST 06:00 境界で取得

3. 1本ずつシミュレーション
   → 指標計算（直前 historySize 件を使用）
   → TradingStrategy と同一ロジックでシグナル判定
   → trade_signal テーブルにも保存（確認用）
   → 連続3回ドテン・損切り・利確も同一ロジック

4. backtest_run / backtest_trade テーブルに結果保存
   → ログにサマリ（損益・勝率・最大DD）を出力
```

### 結果確認

```sql
SELECT * FROM backtest_run ORDER BY executed_at DESC LIMIT 5;
SELECT * FROM backtest_trade WHERE run_id = 1 ORDER BY trade_time;
```

---

## DBスキーマ概要

| テーブル | 用途 |
|---------|------|
| `kline_data` | ローソク足データ（UNIQUE: symbol, interval_type, open_time）|
| `trade_signal` | 指標値・シグナル記録（UNIQUE: symbol, signal_time）|
| `trade_history` | 売買履歴（status: SUCCESS/PAPER/FAILED）|
| `position` | ポジション管理（status: OPEN/CLOSED）|
| `backtest_run` | バックテスト実行サマリ |
| `backtest_trade` | バックテスト個別取引履歴 |

---

## 暗号化の仕組み

```
CRYPTO_MASTER_KEY（環境変数）
    ↓ PBKDF2WithHmacSHA256（310,000回、固定 salt）
  AES-256 秘密鍵
    ↓ AES/GCM/NoPadding（IV=12バイトランダム）
  ENC(Base64(IV + 暗号文))
```

- `AppConfig.@PostConstruct` で起動時に `secret.properties` の `ENC(...)` 値を復号
- `secret.properties` 更新後は **必ず再ビルド**（JAR に取り込まれるため）
- `CRYPTO_MASTER_KEY` を変更した場合は `encrypt-secrets.bat` で再暗号化が必要

---

## 起動手順

```cmd
rem 1. ビルド
mvn clean package -DskipTests

rem 2. DB 起動（Docker）
docker run -d --name cryptodb ^
  -e POSTGRES_DB=cryptodb ^
  -e POSTGRES_USER=cryptouser ^
  -e POSTGRES_PASSWORD=your_db_password ^
  -p 5432:5432 postgres:15

rem 3. APIキー暗号化（初回のみ）
set CRYPTO_MASTER_KEY=your_strong_password
scripts\encrypt-secrets.bat YOUR_API_KEY YOUR_API_SECRET

rem 4. 通常起動（スケジューラ稼働）
set CRYPTO_MASTER_KEY=your_strong_password
java -jar target\crypto-bot2-1.0.0.jar

rem 5. バックテスト
set CRYPTO_MASTER_KEY=your_strong_password
java -jar target\crypto-bot2-1.0.0.jar --backtest --start-date=2024-01-01 --end-date=2024-12-31
```

---

## コーディング規約

- パッケージ: `com.example.cryptobot2`
- Lombok `@Slf4j` でロギング（`System.out.println` 禁止）
- 設定値はすべて `AppProperties` 経由（定数の直書き禁止）
- 例外は `CryptoBotException` にラップ
- スケジューラの `run()` は `Throwable` レベルでキャッチ（スレッド死亡防止）
- APIキー・シークレットはログ出力禁止

## .gitignore 必須項目

```
src/main/resources/secret.properties
target/
*.log
logs/
```
