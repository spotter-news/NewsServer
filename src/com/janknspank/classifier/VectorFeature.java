package com.janknspank.classifier;

import java.io.File;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.janknspank.proto.ArticleProto.ArticleOrBuilder;

/**
 * Abstract Feature that judges articles' relevance to topics by using TF-IDF
 * word frequency vectors.  The feature's vectors and historical distributions
 * must have already been created by VectorFeatureCreator, and live in the
 * specified {@code vectorDirectory} as feature.vector and feature.distribution,
 * respectively.
 * @see {@link #score(ArticleOrBuilder)}
 */
public final class VectorFeature extends Feature {
  private static final Map<FeatureType, File> VECTOR_DIRECTORY_MAP =
      ImmutableMap.<FeatureType, File>builder()
          .put(FeatureType.INDUSTRY, new File("classifier/industry"))
          .put(FeatureType.TOPIC, new File("classifier/topic"))
          .build();
  private static final Vector UNIVERSE_VECTOR;
  static {
    try {
      UNIVERSE_VECTOR = UniverseVector.getInstance();
    } catch (ClassifierException e) {
      throw new Error("Could not load universe vector: " + e.getMessage(), e);
    }
  }

  private final Vector featureVector;
  private final IndustryVectorNormalizer normalizer;

  public VectorFeature(FeatureId featureId) throws ClassifierException {
    super(featureId);
    featureVector = Vector.fromFile(getVectorFile(featureId));
    normalizer = IndustryVectorNormalizer.fromFile(getNormalizerFile(featureId));
  }

  /**
   * Returns a distribution-adjusted score for this article's relevance to
   * this vector's feature.
   */
  @Override
  public double score(ArticleOrBuilder article) {
    int boost = Feature.getBoost(featureId, article);
    return score(Vector.fromArticle(article), boost);
  }

  public double score(Vector articleVector, int boost) {
    return normalizer.getNormalizedScore(
        rawScore(this.featureId, featureVector, articleVector), boost);
  }

  public double rawScore(ArticleOrBuilder article) {
    return rawScore(this.featureId, featureVector, Vector.fromArticle(article));
  }

  /**
   * Returns the raw, un-distribution-adjusted score for this article versus
   * this vector.  Used by both {@link #score(ArticleOrBuilder)} and
   * {@link VectorFeatureCreator#generateDistributionForVector(Vector)}.  The
   * latter is responsible for building the distribution histogram.
   * @param featureId identifier for the feature we're scoring against
   * @param fectureVector the pre-generated vector for the specified feature ID
   * @param articleVector the pre-generated vector for the specified article
   */
  static double rawScore(FeatureId featureId, Vector featureVector, Vector articleVector) {
    return featureVector.getCosineSimilarity(UNIVERSE_VECTOR, articleVector);
  }

  static File getVectorFile(FeatureId featureId) throws ClassifierException {
    return new File(getVectorDirectory(featureId), "/feature.vector");
  }

  static File getNormalizerFile(FeatureId featureId) throws ClassifierException {
    return new File(getVectorDirectory(featureId), "/feature.normalizationdata");
  }

  /**
   * Returns a collection of feature IDs that have folders representing their
   * seed words, blacklisted words, etc, in /classifier/.
   */
  static Iterable<FeatureId> getDefinedFeatureIds() {
    List<FeatureId> featureIds = Lists.newArrayList();
    for (File parentDirectory : VECTOR_DIRECTORY_MAP.values()) {
      for (File vectorDirectory : parentDirectory.listFiles()) {
        String name = vectorDirectory.getName();
        if (name.contains("-")) {
          try {
            FeatureId featureId =
                FeatureId.fromId(Integer.parseInt(name.substring(0, name.indexOf("-"))));
            if (featureId != null) {
              featureIds.add(featureId);
            }
          } catch (NumberFormatException e) {
            // Don't care.  Directory must not be a feature, that's OK.
          }
        }
      }
    }
    return featureIds;
  }

  static File getVectorDirectory(FeatureId featureId) throws ClassifierException {
    int id = featureId.getId();
    File vectorDirectory = VECTOR_DIRECTORY_MAP.get(featureId.getFeatureType());
    for (String idFolderName : vectorDirectory.list()) {
      if (idFolderName.startsWith(id + "-")) {
        return new File(vectorDirectory, idFolderName);
      }
    }
    throw new ClassifierException("Could not find vector directory, vector ID=" + id);
  }

  public double getSimilarityThreshold10Percent() {
    return normalizer.getSimilarityThreshold10Percent();
  }

  public double getSimilarityThreshold50Percent() {
    return normalizer.getSimilarityThreshold50Percent();
  }
}
