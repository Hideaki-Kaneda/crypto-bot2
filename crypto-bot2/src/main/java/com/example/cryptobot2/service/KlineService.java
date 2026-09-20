package com.example.cryptobot2.service;

import com.example.cryptobot2.client.GmoCoinApiClient;
import com.example.cryptobot2.config.AppProperties;
import com.example.cryptobot2.exception.CryptoBotException;
import com.example.cryptobot2.model.KlineRecord;
import com.example.cryptobot2.repository.KlineRepository;
import com.example.cryptobot2.util.ScheduleGuard;
import java.time.ZoneId;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * KLine データ取得・保存のビジネスロジック。
 *
 * <p>全シンボルをループし、GMO API からデータを取得して DB に保存する。
 * シンボルごとにエラーが発生しても他シンボルの処理は継続する。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KlineService {

  /** 1分〜1時間足は YYYYMMDD、1日足以上は YYYY を date パラメータに渡す */
  private static final List<String> DAILY_OR_LONGER =
      List.of("1day", "1week", "1month");

  private final AppProperties props;
  private final GmoCoinApiClient apiClient;
  private final KlineRepository repository;

  /**
   * 現在時刻を基準に全シンボルの KLine データを取得して DB に保存する。
   * スケジューラから呼び出される。
   */
  public void collectAndSave() {
    String interval = props.getKline().getInterval();
    List<String> symbols = props.getKline().getSymbols();
    ZoneId zone = ZoneId.of(props.getScheduler().getTimezone());
    String date = buildDateParam(interval, zone);

    log.info("=== KLine収集開始 interval={}, date={}, symbols={} ===",
        interval, date, symbols);

    int totalInserted = 0;
    int errorCount = 0;

    for (String symbol : symbols) {
      try {
        List<KlineRecord> records = apiClient.fetchKlines(symbol, interval, date);

        if (!records.isEmpty()) {
          int inserted = repository.saveAll(records);
          totalInserted += inserted;
          log.info("symbol={} 保存完了: 取得={} 件, INSERT={} 件",
              symbol, records.size(), inserted);
        }

        // APIレート制限対策（シンボル間に1秒の待機）
        sleepBetweenSymbols(symbols, symbol, props.getApi().getRetry().getDelayMs());

      } catch (CryptoBotException e) {
        errorCount++;
        log.error("symbol={} の取得/保存でエラーが発生しました。次のシンボルへ継続します。 原因: {}",
            symbol, e.getMessage(), e);
      }
    }

    log.info("=== KLine収集完了 合計INSERT={} 件, エラー={} 件 ===",
        totalInserted, errorCount);
  }

  // -----------------------------------------------------------------------
  // Private helpers
  // -----------------------------------------------------------------------

  /**
   * interval に応じて GMO API の date パラメータ文字列を生成する。
   * 日付切り替えルールは {@link ScheduleGuard} に集約している。
   */
  String buildDateParam(String interval, ZoneId zone) {
    if (DAILY_OR_LONGER.contains(interval)) {
      return ScheduleGuard.buildKlineYearParam(zone);
    }
    return ScheduleGuard.buildKlineDateParam(zone);
  }

  /**
   * シンボルリストの最後でなければ指定ミリ秒スリープする。
   */
  private void sleepBetweenSymbols(List<String> symbols, String current, long delayMs) {
    if (!symbols.getLast().equals(current)) {
      try {
        Thread.sleep(delayMs);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
