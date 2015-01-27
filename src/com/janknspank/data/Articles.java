package com.janknspank.data;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.PriorityQueue;
import java.util.Set;

import com.google.common.base.Function;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.janknspank.data.QueryOption.LimitWithOffset;
import com.janknspank.dom.parser.ParserException;
import com.janknspank.proto.Core.Article;
import com.janknspank.proto.Core.ArticleKeyword;
import com.janknspank.proto.Core.TrainedArticleIndustry;
import com.janknspank.proto.Core.UserInterest;
import com.janknspank.rank.CompleteUser;
import com.janknspank.rank.Scorer;

/**
 * Helper class that manages storing and retrieving Article objects from the
 * database.
 */
public class Articles {
  public static final int MAX_TITLE_LENGTH =
      Database.with(Article.class).getStringLength("title");
  public static final int MAX_PARAGRAPH_LENGTH =
      Database.with(Article.class).getStringLength("paragraph");
  public static final int MAX_DESCRIPTION_LENGTH =
      Database.with(Article.class).getStringLength("description");

  public static Iterable<Article> getArticlesOnTopic(String topic) throws DataInternalException {
    List<ArticleKeyword> articleKeywords = getArticleKeywordsForTopics(ImmutableList.of(topic));
    Set<String> articleIds = Sets.newHashSet();
    for (ArticleKeyword articleKeyword : articleKeywords) {
      articleIds.add(articleKeyword.getUrlId());
    }
    return getArticles(articleIds);
  }

  private static List<ArticleKeyword> getArticleKeywords(Iterable<UserInterest> interests)
      throws DataInternalException {
    // Filter out location interests for now, until we can better prioritize them.
    interests = Iterables.filter(interests, new Predicate<UserInterest>() {
      @Override
      public boolean apply(UserInterest userInterest) {
        return !UserInterests.TYPE_LOCATION.equals(userInterest.getType());
      }
    });
    return getArticleKeywordsForTopics(Iterables.transform(interests,
        new Function<UserInterest, String>() {
          @Override
          public String apply(UserInterest interest) {
            return interest.getKeyword();
          }
    }));
  }

  private static List<ArticleKeyword> getArticleKeywordsForTopics(Iterable<String> topics)
      throws DataInternalException {
    return Database.with(ArticleKeyword.class).get(
        new QueryOption.WhereEquals("keyword", topics),
        new QueryOption.Limit(500));
  }
  
  /**
   * Gets articles that contain a set of keywords
   * @throws DataInternalException 
   */
  public static List<Article> getArticlesForKeywords(Iterable<String> keywords) 
      throws DataInternalException {
    List<ArticleKeyword> articleKeywords = getArticleKeywordsForTopics(keywords);
    Set<String> articleIds = Sets.newHashSet();
    for (ArticleKeyword articleKeyword : articleKeywords) {
      articleIds.add(articleKeyword.getUrlId());
    }
    return getArticles(articleIds);
  }

  /**
   * Gets a list of articles tailored specifically to the current user's
   * interests.
   */
  public static List<Article> getArticles(List<UserInterest> interests)
      throws DataInternalException {
    List<ArticleKeyword> articleKeywords = getArticleKeywords(interests);
    Set<String> articleIds = Sets.newHashSet();
    for (ArticleKeyword articleKeyword : articleKeywords) {
      articleIds.add(articleKeyword.getUrlId());
    }
    return getArticles(articleIds);
  }
  
  
  
  public static List<Article> getRankedArticles(String userId, Scorer scorer)
      throws DataInternalException, ParserException {
    Map<Article, Double> ranks = getArticlesAndScores(userId, scorer);
    
    // Sort the articles by rank
    PriorityQueue<Entry<Article, Double>> pq = new PriorityQueue<Map.Entry<Article,Double>>(
        ranks.size(), new Comparator<Entry<Article, Double>>() {

      @Override
      public int compare(Entry<Article, Double> arg0, Entry<Article, Double> arg1) {
        return arg0.getValue().compareTo(arg1.getValue()) * -1;
      }
    });
    pq.addAll(ranks.entrySet());
    
    List<Article> sortedArticles = new ArrayList<Article>();
    while (!pq.isEmpty()) {
      sortedArticles.add(pq.poll().getKey());
    }
    return sortedArticles;
  }
  
  public static Map<Article, Double> getArticlesAndScores(String userId, Scorer scorer)
      throws DataInternalException, ParserException {
    //NeuralNetworkScorer neuralNetworkRank = NeuralNetworkScorer.getInstance();
    CompleteUser completeUser = new CompleteUser(userId);
    // TODO: replace this with getArticles(UserIndustries.getIndustries(userId))
    List<Article> articles = getArticles(UserInterests.getInterests(userId));
    Map<Article, Double> ranks = new HashMap<>();
    
    for (Article article : articles) {
      ranks.put(article, scorer.getScore(article, completeUser));
    }
    
    return ranks;
  }

  /**
   * Returns articles with the given IDs, ordered by publish time, if they
   * exist. If they don't exist, no error is thrown.
   */
  public static List<Article> getArticles(Iterable<String> urlIds)
      throws DataInternalException {
    return Database.with(Article.class).get(
        new QueryOption.WhereEquals("url_id", urlIds),
        new QueryOption.DescendingSort("published_time"));
  }
  
  public static Article getArticle(String urlId) 
      throws DataInternalException {
    return Database.with(Article.class).get(urlId);
  }

  public static List<Article> getPageOfArticles(int limit, int offset) 
      throws DataInternalException {
    return Database.with(Article.class).get(
        new LimitWithOffset(limit, offset));
  }
  
  /**
   * Returns a random article
   */
  public static Article getRandomArticle() throws DataInternalException {
    return Database.with(Article.class).getFirst(
        new QueryOption.Sort("rand()"));
  }

  /**
   * Returns a random untrained article
   */
  public static Article getRandomUntrainedArticle() throws DataInternalException {
    Article article;
    List<TrainedArticleIndustry> taggedIndustries;
    do {
      article = Articles.getRandomArticle();
      taggedIndustries = TrainedArticleIndustries.getFromArticle(article.getUrlId());
    } while (!taggedIndustries.isEmpty());
    return article;
  }
  
  /** Helper method for creating the Article table. */
  public static void main(String args[]) throws Exception {
    Database.with(Article.class).createTable();

//    Article.Builder builder = Article.newBuilder();
//    String id = "id" + System.currentTimeMillis();
//    builder.setAuthor("author");
//    builder.setArticleBody("body");
//    builder.setCopyright("copyright");
//    builder.setDescription("desc");
//    builder.setId(id);
//    builder.setImageUrl("image urllllz");
//    builder.setModifiedTime(500L);
//    builder.setPublishedTime(7300L);
//    builder.setTitle("title");
//    builder.setType("article");
//    builder.setUrl("http://www.nytimes.com/super/article.html");
//    Article article = builder.build();
//    Database.insert(article);
//
//    Article articleRefetched = Database.get(id, Article.class);
//    Printer.print(articleRefetched);
//
//    Article.Builder articleBuilder = articleRefetched.toBuilder();
//    articleBuilder.setArticleBody("new body");
//    articleBuilder.setDescription("new description");
//    articleBuilder.setTitle("new title");
//    Database.update(articleBuilder.build());
//
//    articleRefetched = Database.get(id, Article.class);
//    Printer.print(articleRefetched);
  }
}
