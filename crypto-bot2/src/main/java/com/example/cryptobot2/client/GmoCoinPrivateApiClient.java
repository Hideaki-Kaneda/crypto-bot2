package com.example.cryptobot2.client;

import com.example.cryptobot2.config.AppProperties;
import com.example.cryptobot2.exception.CryptoBotException;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * GMOコイン Private API クライアント（レバレッジ取引対応）。
 *
 * <p>認証: API-KEY / HMAC-SHA256 署名
 * <p>ベースURL: {@code https://api.coin.z.com/private}
 *
 * <p>レバレッジ取引の仕様:
 * <ul>
 *   <li>銘柄コード: BTC_JPY 形式（現物の BTC とは異なる）
 *   <li>注文サイズ: BTC 数量（size フィールド）で指定
 *   <li>新規建て: POST /v1/order（settleType=OPEN）
 *   <li>決済:     POST /v1/closeBulkOrder（保有ポジションを一括決済）
 *   <li>ショート: 新規建て side=SELL で空売りが可能
 * </ul>
 *
 * <p>対応エンドポイント:
 * <ul>
 *   <li>POST /v1/order           — 新規建て注文（OPEN）
 *   <li>POST /v1/closeBulkOrder  — 一括決済注文（CLOSE）
 *   <li>POST /v1/cancelOrder     — 注文キャンセル
 *   <li>GET  /v1/openPositions   — 建玉一覧取得
 *   <li>GET  /v1/account/assets  — 資産残高取得
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GmoCoinPrivateApiClient {

  private static final String PRIVATE_BASE_URL = "https://api.coin.z.com/private";
  private static final String HMAC_ALGORITHM   = "HmacSHA256";

  private final RestTemplate restTemplate;
  private final AppProperties props;
  private final ObjectMapper objectMapper;

  // -----------------------------------------------------------------------
  // レバレッジ 新規建て注文
  // -----------------------------------------------------------------------

  /**
   * レバレッジ新規建て成行注文を発注する（settleType=OPEN）。
   *
   * @param symbol レバレッジ銘柄コード（例: BTC_JPY）
   * @param side   売買区分（BUY=買建て / SELL=売建て）
   * @param size   発注数量（BTC単位）
   * @return 注文ID
   */
  public String openLeverageOrder(String symbol, String side, BigDecimal size) {
    String path = "/v1/order";
    String body;
    try {
      body = objectMapper.writeValueAsString(
          new OpenOrderRequest(symbol, side, "MARKET", "OPEN", null, size.toPlainString())
      );
    } catch (Exception e) {
      throw new CryptoBotException("新規建て注文リクエストのJSON変換に失敗しました。", e);
    }

    log.info("[レバレッジ] 新規建て注文: symbol={} side={} size={}", symbol, side, size);
    ApiResponse response = postPrivate(path, body, ApiResponse.class);

    if (response.getStatus() != 0) {
      throw new CryptoBotException(
          "GMO 新規建て注文エラー status=" + response.getStatus()
              + " messages=" + response.getMessages());
    }

    String orderId = extractStringData(response);
    log.info("[レバレッジ] 新規建て注文成功: orderId={}", orderId);
    return orderId;
  }

  // -----------------------------------------------------------------------
  // レバレッジ 一括決済注文
  // -----------------------------------------------------------------------

  /**
   * 指定シンボルの建玉を一括決済する（closeBulkOrder）。
   *
   * <p>GMOコインの一括決済は保有する全建玉を成行で決済する。
   * 部分決済が必要な場合は {@code /v1/closeOrder} を使用すること。
   *
   * @param symbol レバレッジ銘柄コード（例: BTC_JPY）
   * @param side   決済対象の建玉の side（BUY建玉を決済する場合は "BUY"）
   * @return 注文ID
   */
  public String closeBulkOrder(String symbol, String side) {
    String path = "/v1/closeBulkOrder";
    String body;
    try {
      body = objectMapper.writeValueAsString(
          new CloseBulkOrderRequest(symbol, side, "MARKET", null)
      );
    } catch (Exception e) {
      throw new CryptoBotException("一括決済注文リクエストのJSON変換に失敗しました。", e);
    }

    log.info("[レバレッジ] 一括決済注文: symbol={} side={}", symbol, side);
    ApiResponse response = postPrivate(path, body, ApiResponse.class);

    if (response.getStatus() != 0) {
      throw new CryptoBotException(
          "GMO 一括決済注文エラー status=" + response.getStatus()
              + " messages=" + response.getMessages());
    }

    String orderId = extractStringData(response);
    log.info("[レバレッジ] 一括決済注文成功: orderId={}", orderId);
    return orderId;
  }

  // -----------------------------------------------------------------------
  // 注文キャンセル
  // -----------------------------------------------------------------------

  /**
   * 注文をキャンセルする。
   *
   * @param orderId キャンセルする注文ID
   */
  public void cancelOrder(String orderId) {
    String path = "/v1/cancelOrder";
    String body;
    try {
      body = objectMapper.writeValueAsString(new CancelRequest(orderId));
    } catch (Exception e) {
      throw new CryptoBotException("キャンセルリクエストのJSON変換に失敗しました。", e);
    }

    log.info("注文キャンセル: orderId={}", orderId);
    ApiResponse response = postPrivate(path, body, ApiResponse.class);

    if (response.getStatus() != 0) {
      log.warn("注文キャンセル失敗: orderId={} messages={}", orderId, response.getMessages());
    } else {
      log.info("注文キャンセル成功: orderId={}", orderId);
    }
  }

  // -----------------------------------------------------------------------
  // 建玉一覧取得
  // -----------------------------------------------------------------------

  /**
   * 指定シンボルの建玉一覧を取得する（/v1/openPositions）。
   *
   * @param symbol レバレッジ銘柄コード（例: BTC_JPY）
   * @return 建玉リスト（なければ空リスト）
   */
  public List<OpenPosition> getOpenPositions(String symbol) {
    String path = "/v1/openPositions";
    String url = UriComponentsBuilder
        .fromHttpUrl(PRIVATE_BASE_URL + path)
        .queryParam("symbol", symbol)
        .toUriString();

    OpenPositionsResponse response = getPrivate(path + "?symbol=" + symbol,
        OpenPositionsResponse.class);

    if (response == null || response.getStatus() != 0) {
      log.warn("建玉一覧取得失敗: symbol={}", symbol);
      return List.of();
    }

    if (response.getData() == null || response.getData().getList() == null) {
      return List.of();
    }

    return response.getData().getList();
  }

  // -----------------------------------------------------------------------
  // 資産残高取得
  // -----------------------------------------------------------------------

  /**
   * 資産残高を取得する。
   *
   * @param symbol 取得対象銘柄（例: JPY）
   * @return 利用可能残高。取得できない場合は BigDecimal.ZERO
   */
  public BigDecimal getAvailableBalance(String symbol) {
    String path = "/v1/account/assets";
    AssetsResponse response = getPrivate(path, AssetsResponse.class);

    if (response == null || response.getStatus() != 0) {
      log.warn("資産残高取得失敗");
      return BigDecimal.ZERO;
    }

    return response.getData().stream()
        .filter(a -> symbol.equals(a.getSymbol()))
        .map(AssetsResponse.Asset::getAvailable)
        .findFirst()
        .orElse(BigDecimal.ZERO);
  }

  // -----------------------------------------------------------------------
  // HTTP ヘルパー
  // -----------------------------------------------------------------------

  private <T> T postPrivate(String path, String body, Class<T> responseType) {
    String timestamp = String.valueOf(Instant.now().toEpochMilli());
    String sign = buildSignature(timestamp, "POST", path, body);

    HttpHeaders headers = buildHeaders(timestamp, sign);
    headers.setContentType(MediaType.APPLICATION_JSON);

    HttpEntity<String> entity = new HttpEntity<>(body, headers);

    AppProperties.Api.Retry retry = props.getApi().getRetry();
    for (int attempt = 1; attempt <= retry.getMaxAttempts(); attempt++) {
      try {
        ResponseEntity<T> resp = restTemplate.exchange(
            PRIVATE_BASE_URL + path, HttpMethod.POST, entity, responseType);
        return resp.getBody();
      } catch (Exception e) {
        log.warn("[{}/{}] Private API POST失敗 path={}: {}",
            attempt, retry.getMaxAttempts(), path, e.getMessage());
        if (attempt == retry.getMaxAttempts()) {
          throw new CryptoBotException("Private API 呼び出し失敗: " + path, e);
        }
        sleepSilently(retry.getDelayMs());
      }
    }
    throw new CryptoBotException("Private API 呼び出し失敗: " + path);
  }

  private <T> T getPrivate(String path, Class<T> responseType) {
    String timestamp = String.valueOf(Instant.now().toEpochMilli());
    String sign = buildSignature(timestamp, "GET", path, "");

    HttpHeaders headers = buildHeaders(timestamp, sign);
    HttpEntity<Void> entity = new HttpEntity<>(headers);

    AppProperties.Api.Retry retry = props.getApi().getRetry();
    for (int attempt = 1; attempt <= retry.getMaxAttempts(); attempt++) {
      try {
        ResponseEntity<T> resp = restTemplate.exchange(
            PRIVATE_BASE_URL + path, HttpMethod.GET, entity, responseType);
        return resp.getBody();
      } catch (Exception e) {
        log.warn("[{}/{}] Private API GET失敗 path={}: {}",
            attempt, retry.getMaxAttempts(), path, e.getMessage());
        if (attempt == retry.getMaxAttempts()) {
          throw new CryptoBotException("Private API 呼び出し失敗: " + path, e);
        }
        sleepSilently(retry.getDelayMs());
      }
    }
    throw new CryptoBotException("Private API 呼び出し失敗: " + path);
  }

  private HttpHeaders buildHeaders(String timestamp, String sign) {
    HttpHeaders headers = new HttpHeaders();
    headers.set("API-KEY", props.getApi().getKey());
    headers.set("API-TIMESTAMP", timestamp);
    headers.set("API-SIGN", sign);
    return headers;
  }

  private String buildSignature(String timestamp, String method, String path, String body) {
    try {
      String message = timestamp + method + path + (body == null ? "" : body);
      Mac mac = Mac.getInstance(HMAC_ALGORITHM);
      mac.init(new SecretKeySpec(
          props.getApi().getSecret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
      byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : hash) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new CryptoBotException("HMAC-SHA256 署名の生成に失敗しました。", e);
    }
  }

  private String extractStringData(ApiResponse response) {
    if (response.getData() instanceof String s) return s;
    return response.getData() == null ? "unknown" : response.getData().toString();
  }

  private void sleepSilently(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
  }

  // -----------------------------------------------------------------------
  // リクエスト / レスポンス モデル
  // -----------------------------------------------------------------------

  /**
   * レバレッジ新規建て注文リクエスト。
   * settleType=OPEN が現物との違い。
   */
  record OpenOrderRequest(
      String symbol,
      String side,
      String executionType,
      String settleType,   // OPEN=新規建て
      String price,        // 成行の場合は null
      String size          // BTC数量
  ) {}

  /**
   * 一括決済注文リクエスト。
   */
  record CloseBulkOrderRequest(
      String symbol,
      String side,          // 決済対象建玉の side（BUY建玉→"BUY"）
      String executionType,
      String price          // 成行の場合は null
  ) {}

  record CancelRequest(String orderId) {}

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class ApiResponse {
    private int status;
    private Object data;
    private List<Message> messages;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Message {
      private String message_code;
      private String message_string;
    }
  }

  /** 建玉情報 */
  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  public static class OpenPosition {
    private String positionId;
    private String symbol;
    private String side;
    private BigDecimal size;
    private BigDecimal orderedSize;
    private BigDecimal price;         // 建値
    private BigDecimal lossGain;      // 評価損益
    private BigDecimal leverage;
    private String losscutPrice;
    private String timestamp;
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class OpenPositionsResponse {
    private int status;
    private PositionData data;
    private List<ApiResponse.Message> messages;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class PositionData {
      private List<OpenPosition> list;
      private Pagination pagination;

      @Data
      @JsonIgnoreProperties(ignoreUnknown = true)
      static class Pagination {
        private String currentPage;
        private String count;
      }
    }
  }

  @Data
  @JsonIgnoreProperties(ignoreUnknown = true)
  static class AssetsResponse {
    private int status;
    private List<Asset> data;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    static class Asset {
      private String symbol;
      private BigDecimal amount;
      private BigDecimal available;
    }
  }
}
