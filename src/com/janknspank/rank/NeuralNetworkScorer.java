package com.janknspank.rank;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import org.neuroph.core.NeuralNetwork;
import org.neuroph.nnet.learning.BackPropagation;

import com.google.common.collect.Maps;
import com.google.common.primitives.Doubles;
import com.janknspank.bizness.Urls;
import com.janknspank.bizness.UserIndustries;
import com.janknspank.bizness.UserInterests;
import com.janknspank.bizness.Users;
import com.janknspank.classifier.FeatureId;
import com.janknspank.common.Asserts;
import com.janknspank.crawler.Interpreter;
import com.janknspank.proto.ArticleProto.Article;
import com.janknspank.proto.ArticleProto.InterpretedData;
import com.janknspank.proto.CoreProto.Url;
import com.janknspank.proto.UserProto.User;

public final class NeuralNetworkScorer extends Scorer {
  static final String DEFAULT_NEURAL_NETWORK_FILE = "neuralnet/backpropagation_out.nnet";
  private static NeuralNetworkScorer instance = null;
  private NeuralNetwork<BackPropagation> neuralNetwork;

  @SuppressWarnings("unchecked")
  private NeuralNetworkScorer() {
    neuralNetwork = NeuralNetwork.createFromFile(DEFAULT_NEURAL_NETWORK_FILE);
  }

  public NeuralNetworkScorer(NeuralNetwork<BackPropagation> neuralNetwork) {
    this.neuralNetwork = neuralNetwork;
  }

  public static synchronized NeuralNetworkScorer getInstance() {
    if (instance == null) {
      instance = new NeuralNetworkScorer();
    }
    return instance;
  }

  public static LinkedHashMap<String, Double> generateInputNodes(User user, Article article) {
    Asserts.assertNotNull(user, "user", NullPointerException.class);
    Asserts.assertNotNull(article, "article", NullPointerException.class);
    LinkedHashMap<String, Double> linkedHashMap = Maps.newLinkedHashMap();

    // 0. Relevance to user's industries.
    linkedHashMap.put("industries", InputValuesGenerator.relevanceToUserIndustries(user, article));

    // 1. Nearby industry count.  Value relative to the number of industries
    // this article is about that the user is not explicitly interested in.
    linkedHashMap.put("industry-specific",
        InputValuesGenerator.relevanceToNonUserIndustries(user, article));

    // 2. Relevance on Facebook.
    linkedHashMap.put("facebook", InputValuesGenerator.relevanceOnFacebook(user, article));

    // 3. Relevance on Twitter.
    linkedHashMap.put("twitter", InputValuesGenerator.relevanceOnTwitter(user, article));

    // 4. Relevance to contacts.
    linkedHashMap.put("contacts", InputValuesGenerator.relevanceToContacts(user, article));

    // 5. Company / organization entities being followed.
    linkedHashMap.put("companies", InputValuesGenerator.relevanceToCompanyEntities(user, article));

    // 6. Relevance to acquisitions.
    Set<FeatureId> userIndustryFeatureIds = UserInterests.getUserIndustryFeatureIds(user);
    linkedHashMap.put("acquisitions",
        InputValuesGenerator.relevanceToAcquisitions(userIndustryFeatureIds, article));

    // 7. Relevance to launches.
    linkedHashMap.put("launches",
        InputValuesGenerator.relevanceToLaunches(userIndustryFeatureIds, article));

    // 8. Relevance to start-up fundraising rounds.
    linkedHashMap.put("fundraising",
        InputValuesGenerator.relevanceToFundraising(userIndustryFeatureIds, article));

    // 9. Topic scores.  If the user's actually interested in any of these
    // things, then we null out the scores (because otherwise the neural
    // network just learns that some folks like Sports + Politics + etc, without
    // knowing why, which is a really bad thing for overall quality.)
    linkedHashMap.put("entertainment",
        InputValuesGenerator.getOptimizedFeatureValue(article, FeatureId.TOPIC_ENTERTAINMENT));
    linkedHashMap.put("sports", UserIndustries.hasFeatureId(user, FeatureId.SPORTS)
        ? 0 : InputValuesGenerator.getOptimizedFeatureValue(article, FeatureId.TOPIC_SPORTS));
    linkedHashMap.put("politics", UserIndustries.hasFeatureId(user, FeatureId.GOVERNMENT)
        ? 0 : InputValuesGenerator.getOptimizedFeatureValue(article, FeatureId.TOPIC_POLITICS));
    linkedHashMap.put("murder_crime_war", UserIndustries.hasFeatureId(user, FeatureId.MILITARY)
        ? 0 : InputValuesGenerator.getOptimizedFeatureValue(article, FeatureId.TOPIC_MURDER_CRIME_WAR));
    linkedHashMap.put("equity", UserIndustries.hasFeatureId(user, FeatureId.EQUITY_INVESTING)
        ? 0 : InputValuesGenerator.getOptimizedFeatureValue(article, FeatureId.EQUITY_INVESTING));

    // 10. Relevance to big money
    linkedHashMap.put("big_money",
        InputValuesGenerator.relevanceToBigMoney(userIndustryFeatureIds, article));

    // 11. Relevance to quarterly earnings
    linkedHashMap.put("quarterly_earnings", 
        InputValuesGenerator.relevanceToQuarterlyEarnings(userIndustryFeatureIds, article));

    // 12. Is it a list of things
    linkedHashMap.put("is_list", 
        InputValuesGenerator.relevanceToList(userIndustryFeatureIds, article));

    return linkedHashMap;
  }

  @Override
  public double getScore(User user, Article article) {
    Asserts.assertNotNull(user, "user", NullPointerException.class);
    Asserts.assertNotNull(article, "article", NullPointerException.class);
    return getScore(generateInputNodes(user, article));
  }

  public double getScore(LinkedHashMap<String, Double> inputNodes) {
    neuralNetwork.setInput(Doubles.toArray(inputNodes.values()));
    neuralNetwork.calculate();
    return neuralNetwork.getOutput()[0];
  }

  /**
   * Usage:
   * bin/score.sh jon@jonemerson.net http://path/to/article
   */
  public static void main(String args[]) throws Exception {
    User user = Users.getByEmail(args[0]);
    if (user == null) {
      throw new RuntimeException("User not found: " + args[0]);
    }

    String urlString = args[1];
    Url url = Urls.getByUrl(urlString);
    if (url == null) {
      url = Urls.put(urlString, "");
    }
    InterpretedData data = Interpreter.interpret(url);
    System.out.println(data.getArticle());

    LinkedHashMap<String, Double> inputNodes = generateInputNodes(user, data.getArticle());
    for (Map.Entry<String, Double> entry : inputNodes.entrySet()) {
      System.out.println("Node " + entry.getKey() + ": " + entry.getValue());
    }
    System.out.println("Score: " + getInstance().getScore(inputNodes));
  }
}
