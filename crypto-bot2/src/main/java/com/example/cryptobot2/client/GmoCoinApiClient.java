package com.example.cryptobot2.client;

import com.example.cryptobot2.config.AppProperties;
import com.example.cryptobot2.exception.CryptoBotException;
import com.example.cryptobot2.model.KlineData;
import com.example.cryptobot2.model.KlineRecord;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * GMOコイン Public API クライアント。
 *
 * <p>エンドポイント: {@code GET https://api.coin.z.com/public/v1/klines}
 *
 * <p>リトライ処理を内包し、API status != 0 の場合は {@link CryptoBotException} をスローする。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GmoCoinApiClient {

  private static final String KLINES_PATH = "/v1/klines";

  private final RestTemplate restTemplate;
  private final AppProperties props;

  /**
   * 指定シンボル・日付の KLine データを取得し、{@link KlineRecord} リストに変換して返す。
   *
   * @param symbol      銘柄コード（例: BTC）
   * @param interval    時間軸（例: 1hour）
   * @param date        日付文字列（例: 20240101 or 2024）
   * @return KlineRecord リスト（データなしの場合は空リスト）
   */
  public List<KlineRecord> fetchKlines(String symbol, String interval, String date) {
    String url = UriComponentsBuilder
        .fromHttpUrl(props.getApi().getBaseUrl() + KLINES_PATH)
        .queryParam("symbol", symbol)
        .queryParam("interval", interval)
        .queryParam("date", date)
        .toUriString();

    log.debug("KLine取得 URL: {}", url);

    AppProperties.Api.Retry retry = props.getApi().getRetry();
    int maxAttempts = retry.getMaxAttempts();
    long delayMs = retry.getDelayMs();

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        KlineData response = restTemplate.getForObject(url, KlineData.class);

        if (response == null) {
          log.warn("[{}/{}] APIレスポンスが null でした。symbol={}, date={}",
              attempt, maxAttempts, symbol, date);
          return Collections.emptyList();
        }

        if (response.getStatus() != 0) {
          throw new CryptoBotException(
              "GMO API エラー status=" + response.getStatus()
                  + " symbol=" + symbol + " date=" + date);
        }

        if (response.getData() == null || response.getData().isEmpty()) {
          log.info("KLineデータが空でした。symbol={}, interval={}, date={}",
              symbol, interval, date);
          return Collections.emptyList();
        }

        List<KlineRecord> records = toRecords(response.getData(), symbol, interval);
        log.info("KLine取得成功 symbol={}, interval={}, date={}, 件数={}",
            symbol, interval, date, records.size());
        return records;

      } catch (CryptoBotException e) {
        throw e; // API status エラーはリトライしない
      } catch (RestClientException e) {
        log.warn("[{}/{}] API呼び出し失敗 symbol={}, date={}: {}",
            attempt, maxAttempts, symbol, date, e.getMessage());

        if (attempt == maxAttempts) {
          throw new CryptoBotException(
              "GMO API の呼び出しが " + maxAttempts + " 回失敗しました。"
                  + " symbol=" + symbol + " date=" + date, e);
        }

        sleepSilently(delayMs);
      }
    }

    return Collections.emptyList();
  }

  // -----------------------------------------------------------------------
  // Private helpers
  // -----------------------------------------------------------------------

  private List<KlineRecord> toRecords(
      List<KlineData.KlineEntry> entries, String symbol, String interval) {
    return entries.stream()
        .map(e -> KlineRecord.builder()
            .symbol(symbol)
            .intervalType(interval)
            .openTime(toUtcDateTime(e.getOpenTime()))
            .open(new BigDecimal(e.getOpen()))
            .high(new BigDecimal(e.getHigh()))
            .low(new BigDecimal(e.getLow()))
            .close(new BigDecimal(e.getClose()))
            .volume(new BigDecimal(e.getVolume()))
            .build())
        .toList();
  }

  /** UNIX ミリ秒 → UTC OffsetDateTime 変換 */
  private OffsetDateTime toUtcDateTime(long epochMilli) {
    return OffsetDateTime.ofInstant(Instant.ofEpochMilli(epochMilli), ZoneOffset.UTC);
  }

  private void sleepSilently(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ie) {
      Thread.currentThread().interrupt();
    }
  }
}
