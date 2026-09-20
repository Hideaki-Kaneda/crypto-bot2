package com.example.cryptobot2.scheduler;

import com.example.cryptobot2.config.AppProperties;
import com.example.cryptobot2.exception.CryptoBotException;
import com.example.cryptobot2.service.KlineService;
import com.example.cryptobot2.util.ScheduleGuard;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * KLine データ収集スケジューラ。
 *
 * <p>cron 式は {@code application.properties} の {@code gmo.scheduler.cron} で設定する。
 * 実行タイムゾーンは {@code gmo.scheduler.timezone} で設定する（デフォルト: Asia/Tokyo）。
 *
 * <p>処理中に未捕捉例外が発生してもスケジューラスレッドを死なせないよう、
 * {@link Throwable} レベルでキャッチしてログに記録する。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class KlineScheduler {

  private final KlineService klineService;
  private final AppProperties props;

  /**
   * 定期実行メソッド。cron 式とタイムゾーンはプロパティから読み込む。
   *
   * <p>Spring の {@code @Scheduled} は cron / zone をリテラルまたは
   * プロパティプレースホルダで指定する。
   */
  @Scheduled(
      cron = "${gmo.scheduler.cron}",
      zone = "${gmo.scheduler.timezone}"
  )
  public void run() {
    ZoneId zone = ZoneId.of(props.getScheduler().getTimezone());

    if (ScheduleGuard.isMaintenanceTime(zone)) {
      return;
    }

    log.info("--- KLine スケジューラ起動 cron={} timezone={} ---",
        props.getScheduler().getCron(),
        props.getScheduler().getTimezone());
    try {
      klineService.collectAndSave();
    } catch (CryptoBotException e) {
      log.error("KLine収集でアプリケーション例外が発生しました: {}", e.getMessage(), e);
    } catch (Throwable t) {
      // スケジューラスレッドを保護するため Throwable を捕捉
      log.error("KLine収集で予期しない例外が発生しました: {}", t.getMessage(), t);
    }
  }
}
