package com.janknspank.classifier;

import java.io.File;
import java.util.List;

import com.google.api.client.util.Lists;
import com.google.common.collect.Iterables;
import com.janknspank.database.Database;
import com.janknspank.database.DatabaseSchemaException;
import com.janknspank.database.QueryOption.LimitWithOffset;
import com.janknspank.proto.ArticleProto.Article;

/**
 * Computes and returns the # of documents in the corpus that contain a given
 * term. This is the DF part of TF-IDF.
 */
public class UniverseVector {
  private static final File UNIVERSE_VECTOR_FILE = new File("classifier/industry/universe.vector");

  private static Vector universeVector = null;

  private UniverseVector() {}

  public static Vector getInstance() throws ClassifierException {
    if (universeVector == null) {
      universeVector = Vector.fromFile(UNIVERSE_VECTOR_FILE);
    }
    return universeVector;
  }

  // Expensive - only do infrequently as the corpus grows.
  private static Vector generateUniverseVectorFromCorpus() throws DatabaseSchemaException {
    System.out.println("Warning: calling expensive function: generateUniverseVectorFromCorpus");

    int offset = 0;
    List<Article> allArticles = Lists.newArrayList();
    Iterable<Article> articles = Database.with(Article.class).get(new LimitWithOffset(1000, offset));
    while (!Iterables.isEmpty(articles)) {
      System.out.println("Getting articles - offset: " + offset);
      Iterables.addAll(allArticles, articles);
      offset += 1000;
      articles = Database.with(Article.class).get(new LimitWithOffset(1000, offset));
    }
    System.out.println("Let the computing begin!");
    return new Vector(allArticles);
  }

  /**
   * Helper method for saving the universe vector to the local codebase.  We'll
   * likely want to run this periodically, as we collect more and more articles,
   * so that we have a very good vector for English news article word
   * distribution.
   */
  public static void main(String args[]) throws Exception {
    generateUniverseVectorFromCorpus().writeToFile(UNIVERSE_VECTOR_FILE);
  }
}