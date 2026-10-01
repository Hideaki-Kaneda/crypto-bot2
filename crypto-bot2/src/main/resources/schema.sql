-- ============================================================
-- crypto-bot2 DB スキーマ
-- 初回起動時に自動実行される（spring.sql.init.mode=always）
-- ============================================================

-- ------------------------------------------------------------
-- KLine データ（ローソク足）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS kline_data (
    id            BIGSERIAL      PRIMARY KEY,
    symbol        VARCHAR(20)    NOT NULL,
    interval_type VARCHAR(10)    NOT NULL,
    open_time     TIMESTAMPTZ    NOT NULL,
    open          NUMERIC(20,8)  NOT NULL,
    high          NUMERIC(20,8)  NOT NULL,
    low           NUMERIC(20,8)  NOT NULL,
    close         NUMERIC(20,8)  NOT NULL,
    volume        NUMERIC(30,8)  NOT NULL,
    created_at    TIMESTAMPTZ    NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_kline UNIQUE (symbol, interval_type, open_time)
);

CREATE INDEX IF NOT EXISTS idx_kline_symbol_time
    ON kline_data (symbol, interval_type, open_time DESC);

-- バックテスト実行サマリ
CREATE TABLE IF NOT EXISTS backtest_run (
    id            BIGSERIAL     PRIMARY KEY,
    symbol        VARCHAR(20)   NOT NULL,
    interval_type VARCHAR(10)   NOT NULL,
    start_date    DATE          NOT NULL,
    end_date      DATE          NOT NULL,
    initial_balance_jpy NUMERIC(20,2) NOT NULL,
    final_balance_jpy   NUMERIC(20,2),
    total_profit_jpy    NUMERIC(20,2),
    trade_count   INT,
    win_count     INT,
    loss_count    INT,
    win_rate      NUMERIC(5,2),
    max_drawdown_jpy    NUMERIC(20,2),
    executed_at   TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- バックテスト個別取引履歴
CREATE TABLE IF NOT EXISTS backtest_trade (
    id             BIGSERIAL     PRIMARY KEY,
    run_id         BIGINT        NOT NULL REFERENCES backtest_run(id),
    symbol         VARCHAR(20)   NOT NULL,
    trade_time     TIMESTAMPTZ   NOT NULL,
    side           VARCHAR(4)    NOT NULL,
    price          NUMERIC(20,2) NOT NULL,
    size           NUMERIC(20,8) NOT NULL,
    profit_jpy     NUMERIC(20,2),
    balance_jpy    NUMERIC(20,2),
    close_reason   VARCHAR(20),
    signal_conditions TEXT
);

-- ------------------------------------------------------------
-- トレードシグナル
-- 指標値・BUY/SELL/HOLD シグナルを記録する
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trade_signal (
    id              BIGSERIAL       PRIMARY KEY,
    symbol          VARCHAR(20)     NOT NULL,
    signal_time     TIMESTAMPTZ     NOT NULL,

    -- 価格
    price           NUMERIC(20,8)   NOT NULL,

    -- RSI
    rsi             NUMERIC(10,4),
    rsi_period      INT,
    rsi_signal      VARCHAR(10),     -- BUY / SELL / HOLD / NULL(データ不足)

    -- MACD
    macd            NUMERIC(20,8),
    macd_signal     NUMERIC(20,8),
    macd_histogram  NUMERIC(20,8),
    macd_fast       INT,
    macd_slow       INT,
    macd_signal_period INT,
    macd_cross      VARCHAR(10),     -- GOLDEN / DEAD / HOLD / NULL

    -- DMI
    dmi_plus        NUMERIC(10,4),   -- +DI
    dmi_minus       NUMERIC(10,4),   -- -DI
    adx             NUMERIC(10,4),
    dmi_period      INT,
    dmi_signal      VARCHAR(10),     -- BUY / SELL / HOLD / NULL

    -- RCI (Rank Correlation Index)
    rci             NUMERIC(10,4),
    rci_period      INT,
    rci_signal      VARCHAR(10),     -- BUY / SELL / HOLD / NULL

    -- TEMA (Triple Exponential Moving Average) 短期
    tema_fast       NUMERIC(20,8),
    tema_fast_period INT,

    -- TEMA (Triple Exponential Moving Average) 長期
    tema_slow       NUMERIC(20,8),
    tema_slow_period INT,

    -- TEMA クロスシグナル
    tema_cross      VARCHAR(10),     -- GOLDEN / DEAD / HOLD / NULL

    -- 総合シグナル（全指標の合意）
    final_signal    VARCHAR(10)      NOT NULL DEFAULT 'HOLD',  -- BUY / SELL / HOLD

    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_trade_signal UNIQUE (symbol, signal_time)
);

CREATE INDEX IF NOT EXISTS idx_trade_signal_symbol_time
    ON trade_signal (symbol, signal_time DESC);

-- ------------------------------------------------------------
-- 売買履歴
-- 本番・ペーパートレード共通（status で区別）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trade_history (
    id              BIGSERIAL       PRIMARY KEY,
    symbol          VARCHAR(20)     NOT NULL,
    trade_time      TIMESTAMPTZ     NOT NULL,
    side            VARCHAR(10)     NOT NULL,   -- BUY / SELL
    price           NUMERIC(20,8)   NOT NULL,
    amount          NUMERIC(20,8)   NOT NULL,   -- 数量（BTC等）
    amount_jpy      NUMERIC(20,2)   NOT NULL,   -- 金額（JPY）
    fee             NUMERIC(20,8),
    status          VARCHAR(10)     NOT NULL,   -- SUCCESS / PAPER / FAILED
    order_id        VARCHAR(100),               -- 取引所の注文ID
    signal_id       BIGINT          REFERENCES trade_signal(id),
    note            TEXT,
    profit_jpy      NUMERIC(20,2),              -- 損益（JPY）決済時のみ
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_trade_history_symbol_time
    ON trade_history (symbol, trade_time DESC);

-- ------------------------------------------------------------
-- ポジション管理
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS position (
    id              BIGSERIAL       PRIMARY KEY,
    symbol          VARCHAR(20)     NOT NULL,
    side            VARCHAR(10)     NOT NULL,   -- BUY / SELL
    open_time       TIMESTAMPTZ     NOT NULL,
    open_price      NUMERIC(20,8)   NOT NULL,
    amount          NUMERIC(20,8)   NOT NULL,   -- 数量（BTC等）
    amount_jpy      NUMERIC(20,2)   NOT NULL,   -- 金額（JPY）
    close_time      TIMESTAMPTZ,
    close_price     NUMERIC(20,8),
    profit_jpy      NUMERIC(20,2),              -- 損益（JPY）
    status          VARCHAR(10)     NOT NULL DEFAULT 'OPEN',  -- OPEN / CLOSED
    close_reason    VARCHAR(20),                -- SIGNAL / STOP_LOSS / TAKE_PROFIT
    is_paper        BOOLEAN         NOT NULL DEFAULT FALSE,
    trade_history_id BIGINT         REFERENCES trade_history(id),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_position_symbol_status
    ON position (symbol, status, open_time DESC);

-- ------------------------------------------------------------
-- 動的設定テーブル（スケジューラ実行ごとに再読み込み）
-- ------------------------------------------------------------
CREATE TABLE IF NOT EXISTS trading_config (
    key         VARCHAR(100)  PRIMARY KEY,
    value       VARCHAR(500)  NOT NULL,
    description TEXT,
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT NOW()
);

-- デフォルト値を挿入（存在しない場合のみ）
INSERT INTO trading_config (key, value, description) VALUES
  ('trade.paper-mode',               'true',   'ペーパートレードモード（true/false）')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO trading_config (key, value, description) VALUES
  ('trade.size',                     '0.1',    '発注数量（BTC単位）')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO trading_config (key, value, description) VALUES
  ('trade.stop-loss-jpy',            '0',      '損切り金額（円）。0=なし')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO trading_config (key, value, description) VALUES
  ('trade.flip-close-sell-min-bars', '0',      '符号反転利確の最低保有本数（売建て）')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO trading_config (key, value, description) VALUES
  ('trade.flip-close-buy-min-bars',  '0',      '符号反転利確の最低保有本数（買建て）')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO trading_config (key, value, description) VALUES
  ('indicator.signal-reverse',       'true',   'シグナル反転（true=GC→売/DC→買）')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO trading_config (key, value, description) VALUES
  ('indicator.ema-trend-period',     '20',     'EMAトレンドフィルター期間（0=なし）')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO trading_config (key, value, description) VALUES
  ('indicator.ema-trend-reverse',    'false',  'EMAフィルター反転（true=逆張り）')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO trading_config (key, value, description) VALUES
  ('indicator.rci-entry-min',        '-60.0',  'RCI新規建てフィルター下限')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO trading_config (key, value, description) VALUES
  ('indicator.rci-entry-max',        '60.0',   'RCI新規建てフィルター上限')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO trading_config (key, value, description) VALUES
  ('trade.daily-profit-limit',       '0',      '1日の損益上限（円）。0=制限なし')
  ON CONFLICT (key) DO NOTHING;
INSERT INTO trading_config (key, value, description) VALUES
  ('trade.daily-loss-limit',         '0',      '1日の損益下限（円）。0=制限なし')
  ON CONFLICT (key) DO NOTHING;
