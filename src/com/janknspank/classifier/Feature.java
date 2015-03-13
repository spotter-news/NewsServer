package com.janknspank.classifier;

import java.net.MalformedURLException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutionException;

import com.google.common.base.Strings;
import com.google.common.base.Throwables;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
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
  private static final LoadingCache<FeatureId, Feature> FEATURE_CACHE =
      CacheBuilder.newBuilder().maximumSize(1000).build(new FeatureLoader());
  private static List<FeatureId> VALID_FEATURE_IDS = null;
  private static final PatternCache PATTERN_CACHE = new PatternCache();

  /**
   * Loader for the Guava cache that returns a Feature for each FeatureId.  This
   * is where new Feature type instantiations should go.
   */
  private static class FeatureLoader extends CacheLoader<FeatureId, Feature> {
    @Override
    public Feature load(FeatureId featureId) throws ClassifierException {
      // NOTE(jonemerson): This section is going to get a lot more complicated
      // as we support more features!!  That's as designed, this is where that
      // logic is supposed to go!  But for now, we can assume the only things
      // around are vector features.
      if (featureId.getFeatureType() == FeatureType.MANUAL_HEURISTIC) {
        return new ManualHeuristicFeature(featureId);
      } else {
        return new VectorFeature(featureId);
      }
    }
  }

  /**
   * Returns an implementation of the requested feature, constructing one from
   * vectors on disk (or wherever) as necessary.
   */
  public static Feature getFeature(FeatureId featureId) throws ClassifierException {
    try {
      return FEATURE_CACHE.get(featureId);
    } catch (ExecutionException e) {
      Throwables.propagateIfInstanceOf(e.getCause(), ClassifierException.class);
      throw new RuntimeException(e);
    }
  }

  /**
   * Returns all the features that have been defined on disk.
   */
  public static synchronized Iterable<Feature> getAllFeatures() {
    if (VALID_FEATURE_IDS == null) {
      ImmutableList.Builder<FeatureId> validFeatureIdsBuilder = ImmutableList.<FeatureId>builder();
      for (FeatureId featureId : FeatureId.values()) {
        try {
          FEATURE_CACHE.get(featureId);
        } catch (ExecutionException e) {
          continue;
        }
        validFeatureIdsBuilder.add(featureId);
      }
      VALID_FEATURE_IDS = validFeatureIdsBuilder.build();
      if (VALID_FEATURE_IDS.size() == 0) {
        throw new Error("Could not find any valid features");
      }
    }
    return FEATURE_CACHE.getAllPresent(VALID_FEATURE_IDS).values();
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
