package com.example.cryptobot2.util;

import java.time.DayOfWeek;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import lombok.extern.slf4j.Slf4j;

/**
 * スケジューラ実行可否の判定ユーティリティ。
 *
 * <p>以下の条件でスキップ判定を行う:
 * <ul>
 *   <li>GMOコイン定期メンテナンス: 毎週土曜日 09:05〜11:00 JST
 *       （9:00〜メンテ開始・11:00終了のため余裕を持って 9:05〜11:00 をスキップ）
 * </ul>
 *
 * <p>KLine 日付切り替えルール（GMOコイン仕様）:
 * <ul>
 *   <li>JST 00:00〜06:04 → 前日の日付（YYYYMMDD）を使用
 *   <li>JST 06:05〜23:59 → 当日の日付（YYYYMMDD）を使用
 * </ul>
 */
@Slf4j
public class ScheduleGuard {

  /** メンテナンス開始時刻（土曜 09:05 JST） */
  private static final LocalTime MAINTENANCE_START = LocalTime.of(9, 5);

  /** メンテナンス終了時刻（土曜 11:00 JST、この時刻以降は再開） */
  private static final LocalTime MAINTENANCE_END = LocalTime.of(11, 0);

  /**
   * GMOコインの定期メンテナンス時間帯かどうかを判定する。
   *
   * <p>毎週土曜日 09:05〜10:59 JST の間は {@code true} を返す。
   *
   * @param zone タイムゾーン（通常 Asia/Tokyo）
   * @return メンテナンス中なら true
   */
  public static boolean isMaintenanceTime(ZoneId zone) {
    ZonedDateTime now = ZonedDateTime.now(zone);
    if (now.getDayOfWeek() != DayOfWeek.SATURDAY) {
      return false;
    }
    LocalTime time = now.toLocalTime();
    boolean inMaintenance = !time.isBefore(MAINTENANCE_START)
        && time.isBefore(MAINTENANCE_END);

    if (inMaintenance) {
      log.info("GMOコイン定期メンテナンス時間帯のためスキップします。"
          + " [{} 〜 {}]", MAINTENANCE_START, MAINTENANCE_END);
    }
    return inMaintenance;
  }

  /**
   * GMO KLine API に渡す date パラメータ（YYYYMMDD）を生成する。
   *
   * <p>GMOコインの日付切り替えは JST 06:00 基準だが、
   * 切り替え直後（06:00〜06:04）は 5:55〜6:00 のデータが翌日付に
   * 含まれない場合があるため、06:05 以降を当日扱いとする。
   *
   * <ul>
   *   <li>JST 00:00〜06:04 → 前日の YYYYMMDD
   *   <li>JST 06:05〜23:59 → 当日の YYYYMMDD
   * </ul>
   *
   * @param zone タイムゾーン（通常 Asia/Tokyo）
   * @return YYYYMMDD 形式の日付文字列
   */
  public static String buildKlineDateParam(ZoneId zone) {
    ZonedDateTime now = ZonedDateTime.now(zone);
    // 06:05 未満（00:00〜06:04）は前日扱い
    boolean isBeforeCutover = now.toLocalTime().isBefore(LocalTime.of(6, 5));
    ZonedDateTime base = isBeforeCutover ? now.minusDays(1) : now;
    return base.toLocalDate().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd"));
  }

  /**
   * GMO KLine API に渡す year パラメータ（YYYY）を生成する。
   * 1day 以上の足で使用する。
   *
   * @param zone タイムゾーン（通常 Asia/Tokyo）
   * @return YYYY 形式の年文字列
   */
  public static String buildKlineYearParam(ZoneId zone) {
    ZonedDateTime now = ZonedDateTime.now(zone);
    boolean isBeforeCutover = now.toLocalTime().isBefore(LocalTime.of(6, 5));
    ZonedDateTime base = isBeforeCutover ? now.minusDays(1) : now;
    return String.valueOf(base.toLocalDate().getYear());
  }

  private ScheduleGuard() {}
}
