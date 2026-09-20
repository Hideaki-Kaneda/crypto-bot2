package com.example.cryptobot2.config;

import com.example.cryptobot2.service.EncryptionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * アプリケーション共通 Bean 定義。
 *
 * <p>起動時に secret.properties の ENC(...) 値を復号し、
 * AppProperties に平文をセットする。
 * @Bean で AppProperties を返すと @Component と二重登録になるため
 * @PostConstruct で同一インスタンスを直接書き換える方式を採用する。
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class AppConfig {

  private final AppProperties props;
  private final EncryptionService encryptionService;

  /**
   * 起動時に暗号化済みプロパティを復号する。
   * @Bean ではなく @PostConstruct で AppProperties を直接書き換えることで
   * Bean の二重登録を回避する。
   */
  @PostConstruct
  public void decryptSecrets() {
    AppProperties.Api api = props.getApi();

    if (api.getKey() != null && encryptionService.isEncrypted(api.getKey())) {
      api.setKey(encryptionService.decrypt(api.getKey()));
      log.info("gmo.api.key を復号しました。");
    }
    if (api.getSecret() != null && encryptionService.isEncrypted(api.getSecret())) {
      api.setSecret(encryptionService.decrypt(api.getSecret()));
      log.info("gmo.api.secret を復号しました。");
    }
  }

  @Bean
  public ObjectMapper objectMapper() {
    return new ObjectMapper();
  }

  /**
   * タイムアウト設定済みの RestTemplate Bean。
   *
   * <p>Spring Boot 3.2 以降、RestTemplateBuilder の connectTimeout/readTimeout は廃止。
   * SimpleClientHttpRequestFactory でタイムアウトを設定する。
   */
  @Bean
  public RestTemplate restTemplate() {
    AppProperties.Api.Timeout timeout = props.getApi().getTimeout();
    SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
    factory.setConnectTimeout(timeout.getConnectMs());
    factory.setReadTimeout(timeout.getReadMs());
    return new RestTemplate(factory);
  }
}
