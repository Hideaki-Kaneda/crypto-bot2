package com.example.cryptobot2;

import com.example.cryptobot2.backtest.BacktestEngine;
import java.util.Arrays;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.ApplicationContext;
import org.springframework.scheduling.annotation.EnableScheduling;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * crypto-bot2 エントリポイント。
 *
 * <p>通常起動: {@code java -jar crypto-bot2.jar}
 * <p>バックテスト: {@code java -jar crypto-bot2.jar --backtest}
 */
@Slf4j
@SpringBootApplication
@EnableScheduling
@RequiredArgsConstructor
public class CryptoBot2Application implements ApplicationRunner {

  private final BacktestEngine backtestEngine;

  public static void main(String[] args) {
    SpringApplication.run(CryptoBot2Application.class, args);
  }

  @Override
  public void run(ApplicationArguments args) {
    if (args.containsOption("backtest")) {
      log.info("バックテストモードで起動します。");

      String startDate = getRequiredArg(args, "start-date");
      String endDate   = getRequiredArg(args, "end-date");

      backtestEngine.run(startDate, endDate);
      log.info("バックテスト完了。アプリケーションを終了します。");
      System.exit(0);
    }
    // 通常起動: スケジューラが動き続ける
  }

  private String getRequiredArg(ApplicationArguments args, String name) {
    if (!args.containsOption(name) || args.getOptionValues(name).isEmpty()) {
      log.error("必須引数が指定されていません: --{}", name);
      log.error("使用方法: java -jar crypto-bot2.jar --backtest --start-date=2024-01-01 --end-date=2024-12-31");
      System.exit(1);
    }
    return args.getOptionValues(name).get(0);
  }
}
