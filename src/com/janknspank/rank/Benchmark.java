package com.janknspank.rank;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import com.google.api.client.repackaged.com.google.common.base.Joiner;
import com.google.api.client.util.Lists;
import com.google.common.collect.HashMultiset;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Multiset;
import com.janknspank.bizness.BiznessException;
import com.janknspank.common.Asserts;
import com.janknspank.crawler.ArticleCrawler;
import com.janknspank.proto.ArticleProto.Article;
import com.janknspank.proto.RankProto.Persona;
import com.janknspank.proto.UserProto.User;

public class Benchmark {
  // TODO(jonemerson): This should just take a set of Articles that are pre-
  // filtered as holdback articles.  It's pretty slow to grab all the Articles
  // before getting here when only 20% of them are going to be used.
  public static Map<Article, Double> getScores(
      User user, Iterable<String> urlStrings, Scorer scorer) throws BiznessException {
    Map<Article, Double> scoreMap = Maps.newHashMap();
    Collection<Article> articles =
        ArticleCrawler.getArticles(urlStrings, true /* retain */).values();
    for (Article article : articles) {
      // Use the holdback for the benchmark. The other 80% are used
      // to train the neural network.
      if (NeuralNetworkTrainer.isInTrainingHoldback(article)) {
        scoreMap.put(article, scorer.getScore(user, article));
      }
    }
    return scoreMap;
  }

  /**
   * Prints out a performance score (aka a "grade") for how well the Scorer did
   * at creating scores for the passed Article -> Score maps.
   */
  public static void grade(Map<Article, Double> goodScoreMap, Map<Article, Double> badScoreMap) {
    int positives = 0;
    int falseNegatives = 0;
    List<String> falseNegativesTitles = new ArrayList<>();
    for (Map.Entry<Article, Double> entry : goodScoreMap.entrySet()) {
      double score = entry.getValue();
      Article article = entry.getKey();
      if (score > 0.5) {
        positives++;
      } else {
        falseNegatives++;
        falseNegativesTitles.add(article.getTitle());
      }
    }
    int negatives = 0;
    int falsePositives = 0;
    for (Double score : badScoreMap.values()) {
      if (score <= 0.5) {
        negatives++;
      } else {
        falsePositives++;
      }
    }
    System.out.println("Positives: " + positives
        + " (" + (int) ((double) 100 * positives / goodScoreMap.size()) + "% correct)");
    System.out.println("False negatives: " + falseNegatives);
    System.out.println("False positives: " + falsePositives);
    System.out.println("Negatives: " + negatives
        + " (" + (int) ((double) 100 * negatives / badScoreMap.size()) + "% correct)");
    System.out.println("Percent correct: " +
        (int) (100 * (((double) positives + negatives)
            / (goodScoreMap.size() + badScoreMap.size()))) + "%");
    System.out.println("False negative titles:");
    for (int i = 0; i < falseNegativesTitles.size(); i++) {
      System.out.println("  " + falseNegativesTitles.get(i));
    }
  }

  /**
   * Returns a multiset that counts how many scores are within each 1/10th
   * decimal increment.  Field "0" contains a count of scores between 0 and
   * 0.1, field "1" contains a count of scores between 0.1 and 0.2, etc.
   */
  private static Multiset<Integer> getHistogram(Iterable<Double> scores) {
    Multiset<Integer> histogram = HashMultiset.create();
    for (Double score : scores) {
      int bucket = (int) (score * 10);
      if (bucket < 0 || bucket >= 10) {
        bucket = -100;
      }
      histogram.add(bucket);
    }
    return histogram;
  }

  private static String createStars(int good, int bad) {
    return Joiner.on("").join(Iterables.limit(Iterables.cycle("g"), good)) +
        Joiner.on("").join(Iterables.limit(Iterables.cycle("B"), bad));
  }

  private static void printHistogram(
      Map<Article, Double> goodScores, Map<Article, Double> badScores) {
    Multiset<Integer> goodHistogram = getHistogram(goodScores.values());
    Multiset<Integer> badHistogram = getHistogram(badScores.values());
    for (int i = 9; i >= 0; i--) {
      String start = "0." + i;
      String end = (i == 9) ? "1.0" : "0." + (i + 1);
      System.out.println("* " + start + " to " + end + ": "
          + createStars(goodHistogram.count(i), badHistogram.count(i)));
    }
    System.out.println("* SCORE OUT OF RANGE: "
        + createStars(goodHistogram.count(-100), badHistogram.count(-100)));
  }

  public static void main(String args[]) throws Exception {
    List<Persona> personas = Lists.newArrayList();
    if (args.length > 0) {
      for (String arg : args) {
        Persona persona = null;
        if ("jon".equals(arg)) {
          persona = Personas.getByEmail("panaceaa@gmail.com");
        } else if ("tom".equals(arg)) {
          persona = Personas.getByEmail("tom.charytoniuk@gmail.com");
        } else {
          persona = Personas.getByEmail(arg);
        }
        Asserts.assertNotNull(persona, "Could not read persona: " + arg);
        personas.add(persona);
      }
    } else {
      personas.addAll(Personas.getPersonaMap().values());
    }

    Map<Article, Double> goodScores = Maps.newHashMap();
    Map<Article, Double> badScores = Maps.newHashMap();
    for (Persona persona : personas) {
      User user = Personas.convertToUser(persona);
      goodScores.putAll(
          getScores(user, persona.getGoodUrlList(), NeuralNetworkScorer.getInstance()));
      badScores.putAll(
          getScores(user, persona.getBadUrlList(), NeuralNetworkScorer.getInstance()));
    }
    System.out.println("\nNEURAL NETWORK SCORER:");
    printHistogram(goodScores, badScores);
    grade(goodScores, badScores);

    System.out.println("\n");
  }
}