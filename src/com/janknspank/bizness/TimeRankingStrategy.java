package com.janknspank.bizness;

import java.util.concurrent.TimeUnit;

import com.janknspank.proto.ArticleProto.Article;
import com.janknspank.proto.UserProto.User;
import com.janknspank.push.UserTimezone;

/**
 * Strategies for ranking articles differently depending on how old they are
 * and what stream (main stream, topic, stream, etc) the user's looking at.
 */
public abstract class TimeRankingStrategy {
  /**
   * Returns a time-value coefficient for this article given the current user.
   * This value will typically be multiplied by the article's neural network
   * score to create the article's final, effective score for ranking purposes.
   * This method should strive to return 1 for articles that are still relevant
   * given their age, and slowly decay towards 0 for articles that are older.
   */
  public abstract double getTimeRank(Article article, User user);

  /**
   * Returns the number of minutes ago that the user last used the app,
   * excluding any usages in the last hour.
   */
  private static long getLastAppUsageInMinutes(User user) {
    long lastAppUsageAtLeastOneHourAgo = 0;
    for (long last5AppUseTime : user.getLast5AppUseTimeList()) {
      if (last5AppUseTime < (System.currentTimeMillis() - TimeUnit.HOURS.toMillis(1))
          && last5AppUseTime > lastAppUsageAtLeastOneHourAgo) {
        lastAppUsageAtLeastOneHourAgo = last5AppUseTime;
      }
    }
    return (System.currentTimeMillis() - lastAppUsageAtLeastOneHourAgo)
        / TimeUnit.MINUTES.toMillis(1);
  }

  private static boolean isWeekend(User user) {
    try {
      UserTimezone userTimezone = UserTimezone.getForUser(user);
      return userTimezone.isWeekend();
    } catch (Exception e) {}
    return false;
  }

  /**
   * This is the time ranking strategy (aka how we punish articles for being
   * older) for the main stream.
   */
  public static class MainStreamStrategy extends TimeRankingStrategy {
    @Override
    public double getTimeRank(Article article, User user) {
      // How many hours ago did the user last use the app?  Don't let this
      // get bigger than 18 (or 42 on weekends), otherwise the stream gets
      // really dated.
      double lastAppUsageInHoursAgo = Math.min(
          isWeekend(user) ? 42 : 18,
          getLastAppUsageInMinutes(user) / 60.0);

      // How many hours ago was the article published?
      double articleAgeInHours =
          ((double) System.currentTimeMillis() - Articles.getPublishedTime(article))
              / (double) TimeUnit.HOURS.toMillis(1);

      // Hours since last app usage.
      double hoursSinceLastAppUsage = Math.max(0, articleAgeInHours - lastAppUsageInHoursAgo);

      // OK, here's what we're going to do.  If
      // hoursSinceLastAppUsage == 0: Return 1.
      // hoursSinceLastAppUsage == 12: Return 0.5.
      // hoursSinceLastAppUsage == 24: Return 0.25.
      double denominator = (hoursSinceLastAppUsage / 12) + 1;
      return 1 / denominator;
    }
  }

  /**
   * This is a time ranking strategy for non-main-stream streams.  E.g. the user
   * clicked on a specific industry, or a specific entity.
   */
  public static class AncillaryStreamStrategy extends TimeRankingStrategy {
    @Override
    public double getTimeRank(Article article, User user) {
      // How many hours ago was the article published?
      double articleAgeInHours =
          ((double) System.currentTimeMillis() - Articles.getPublishedTime(article))
              / (double) TimeUnit.HOURS.toMillis(1);

      // This is when the time punishment starts.  It is bigger on weekends
      // because there's less news on weekends.
      int cliff = isWeekend(user) ? 42 : 18;

      // OK, here's what we're going to do.  If
      // articleAgeInHours <= cliff: Return 1.
      // articleAgeInHours == cliff + 24: Return 0.5.
      // articleAgeInHours == cliff + 48: Return 0.25.
      double denominator = (Math.max(0, articleAgeInHours - cliff) / 24) + 1;
      return 1 / denominator;
    }
  }
}
