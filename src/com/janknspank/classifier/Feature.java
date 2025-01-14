package com.janknspank.classifier;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.Map;

import com.google.common.base.Strings;
import com.google.common.collect.ImmutableMap;
import com.janknspank.classifier.manual.ManualFeatureAcquisitions;
import com.janknspank.classifier.manual.ManualFeatureBigMoney;
import com.janknspank.classifier.manual.ManualFeatureFundraising;
import com.janknspank.classifier.manual.ManualFeatureIsList;
import com.janknspank.classifier.manual.ManualFeatureLaunches;
import com.janknspank.classifier.manual.ManualFeatureQuarterlyEarnings;
import com.janknspank.common.PatternCache;
import com.janknspank.crawler.SiteManifests;
import com.janknspank.proto.ArticleProto.ArticleOrBuilder;
import com.janknspank.proto.CrawlerProto.SiteManifest;
import com.janknspank.proto.CrawlerProto.SiteManifest.FeatureBoostPattern;

/**
 * A business class that scores news articles against a particular attribute,
 * such as relevance to an industry or relevance to a user's intent.  Every
 * Feature must have a FeatureId and an implementation of the
 * {@code #score(Article)} method.  The implementation of the score method can
 * be decided on a case-by-case subclass basis.
 */
public abstract class Feature {
  private static Map<FeatureId, Feature> VALID_FEATURE_MAP = null;
  private static final PatternCache PATTERN_CACHE = new PatternCache();

  private static Feature createFeature(FeatureId featureId) throws ClassifierException {
    // NOTE(jonemerson): This section is going to get a lot more complicated
    // as we support more features!!  That's as designed, this is where that
    // logic is supposed to go!  But for now, we can assume the only things
    // around are vector features.
    if (featureId.getFeatureType() == FeatureType.MANUAL_HEURISTIC) {
      switch (featureId) {
        case MANUAL_HEURISTIC_ACQUISITIONS:
          return new ManualFeatureAcquisitions();
        case MANUAL_HEURISTIC_BIG_MONEY:
          return new ManualFeatureBigMoney();
        case MANUAL_HEURISTIC_FUNDRAISING:
          return new ManualFeatureFundraising();
        case MANUAL_HEURISTIC_LAUNCHES:
          return new ManualFeatureLaunches();
        case MANUAL_HEURISTIC_QUARTERLY_EARNINGS:
          return new ManualFeatureQuarterlyEarnings();
        case MANUAL_HEURISTIC_IS_LIST:
          return new ManualFeatureIsList();
        default:
          throw new ClassifierException("No ManualHeuristicFeature for featureId: " + featureId);
      }
    } else {
      return new VectorFeature(featureId);
    }
  }

  /**
   * Returns an implementation of the requested feature, constructing one from
   * vectors on disk (or wherever) as necessary.
   */
  public static Feature getFeature(FeatureId featureId) {
    return getFeatureMap().get(featureId);
  }

  public static Iterable<Feature> getAllFeatures() {
    return getFeatureMap().values();
  }

  /**
   * Returns all the features that have been defined on disk.
   */
  private static synchronized Map<FeatureId, Feature> getFeatureMap() {
    if (VALID_FEATURE_MAP == null) {
      ImmutableMap.Builder<FeatureId, Feature> validFeatureMapBuilder =
          ImmutableMap.<FeatureId, Feature>builder();
      for (FeatureId featureId : FeatureId.values()) {
        try {
          validFeatureMapBuilder.put(featureId, createFeature(featureId));
        } catch (Exception e) {
          e.printStackTrace();
          continue;
        }
      }
      VALID_FEATURE_MAP = validFeatureMapBuilder.build();
      if (VALID_FEATURE_MAP.size() == 0) {
        throw new Error("Could not find any valid features");
      }
    }
    return VALID_FEATURE_MAP;
  }

  protected final FeatureId featureId;

  public Feature(FeatureId featureId) {
    this.featureId = featureId;
  }

  public FeatureId getId() {
    return featureId;
  }

  public final FeatureId getFeatureId() {
    return featureId;
  }

  public final String getDescription() {
    return featureId.getTitle();
  }

  /**
   * Computes a normalized relevance of this feature within the passed article.
   * @return [0-1]
   */
  public abstract double score(ArticleOrBuilder article) throws ClassifierException;

  /**
   * Returns the boost this article should receive for ranking against this
   * feature given its domain, subdomain, path, query, etc.
   */
  protected static int getBoost(FeatureId featureId, ArticleOrBuilder article) {
    // We only do boosts for industry features and the Startups feature.
    // This enables us to score politics, murder/war, etc, correctly across
    // sites without worrying about it explicitly in the manifests.
    if (featureId.getFeatureType() != FeatureType.INDUSTRY && featureId != FeatureId.STARTUPS) {
      return 0;
    }

    URL url;
    try {
      url = new URL(article.getUrl());
    } catch (MalformedURLException e) {
      return 0;
    }

    SiteManifest site = SiteManifests.getForUrl(url);
    if (site == null) {
      return 0;
    }

    FeatureBoostPattern bestFeatureBoostPattern = null;
    String domain = url.getHost();
    String path = url.getPath();
    for (FeatureBoostPattern featureBoostPattern : site.getFeatureBoostPatternList()) {
      if (featureBoostPattern.getBoost() < -20 || featureBoostPattern.getBoost() > 10) {
        System.out.println("INVALID FEATURE BOOST VALUE! Domain=" + domain);
      }
      if (featureBoostPattern.hasFeatureId()
          && featureId.getId() != featureBoostPattern.getFeatureId()) {
        continue;
      }
      if (featureBoostPattern.hasSubdomain()
          && !(domain.equals(featureBoostPattern.getSubdomain())
              || domain.endsWith("." + featureBoostPattern.getSubdomain()))) {
        continue;
      }
      if (featureBoostPattern.hasPathRegex()
            && !PATTERN_CACHE.getPattern(featureBoostPattern.getPathRegex()).matcher(path).find()) {
        continue;
      }
      if (featureBoostPattern.hasQueryRegex()
            && !PATTERN_CACHE.getPattern(featureBoostPattern.getQueryRegex())
                .matcher(Strings.nullToEmpty(url.getQuery())).find()) {
        continue;
      }
      bestFeatureBoostPattern = featureBoostPattern;
    }
    return (bestFeatureBoostPattern == null) ? 0 : bestFeatureBoostPattern.getBoost();
  }
}
