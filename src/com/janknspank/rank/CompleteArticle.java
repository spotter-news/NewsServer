package com.janknspank.rank;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.janknspank.classifier.IndustryClassifier;
import com.janknspank.data.ArticleFacebookEngagements;
import com.janknspank.data.ArticleKeywords;
import com.janknspank.data.Articles;
import com.janknspank.data.DataInternalException;
import com.janknspank.data.IndustryCodes;
import com.janknspank.data.ValidationException;
import com.janknspank.proto.Core.Article;
import com.janknspank.proto.Core.ArticleFacebookEngagement;
import com.janknspank.proto.Core.ArticleIndustryClassification;
import com.janknspank.proto.Core.ArticleKeyword;
import com.janknspank.proto.Core.UserInterest;

/**
 * Convenience class that combines ArticleKeyworks
 * ArticleTypeCodes
 * ArticleIndustries
 * and TrainedArticleRelevance
 * @author tomch
 *
 */
public class CompleteArticle {
  private Article article;
  private Iterable<ArticleKeyword> keywords;
  private Iterable<ArticleIndustryClassification> industryClassifications;
  private Iterable<ArticleFacebookEngagement> facebookEngagements;
  private static final int MILLIS_PER_DAY = 86400000;

  public CompleteArticle(String urlId) 
      throws DataInternalException, IOException, ValidationException {
    article = Articles.getArticle(urlId);
    initForArticle(article);
  }

  public CompleteArticle(Article article) 
      throws DataInternalException, IOException, ValidationException {
    this.article = article;
    initForArticle(article);
  }

  private void initForArticle(Article article) 
      throws DataInternalException, IOException, ValidationException {
    String url = article.getUrl();
    keywords = ArticleKeywords.get(ImmutableList.of(article));
    industryClassifications = IndustryClassifier.getInstance().classify(article);
    facebookEngagements = ArticleFacebookEngagements.getLatest(url, 2);
  }

  public Iterable<ArticleFacebookEngagement> getFacebookEngagements() {
    return facebookEngagements;
  }
  
  public ArticleFacebookEngagement getLatestFacebookEngagement() {
    if (facebookEngagements != null && Iterables.size(facebookEngagements) > 0) {
      // First index is the latest (biggest create_time)
      return Iterables.getFirst(facebookEngagements, null);      
    }
    else {
      return null;
    }
  }
  
  // returns Likes / day
  public double getLikeVelocity() {
    if (facebookEngagements == null || Iterables.isEmpty(facebookEngagements)) {
      return 0;
    }
    else if (Iterables.size(facebookEngagements) == 1) {
      // Use the published date to get the velocity
      ArticleFacebookEngagement engagement = Iterables.getFirst(facebookEngagements, null);
      double daysSincePublish = (System.currentTimeMillis() - 
          article.getPublishedTime()) / MILLIS_PER_DAY;
      return engagement.getLikeCount() / daysSincePublish;
    } else {
      // Use the time interval between the last two engagement checks
      ArticleFacebookEngagement mostRecentEng = Iterables.getFirst(facebookEngagements, null);
      ArticleFacebookEngagement previousEng = Iterables.getFirst(facebookEngagements, null);
      long changeInLikes = mostRecentEng.getLikeCount() 
          - previousEng.getLikeCount();
      double daysBetweenChecks = (mostRecentEng.getCreateTime()
          - previousEng.getCreateTime()) / MILLIS_PER_DAY;
      return changeInLikes / daysBetweenChecks;
    }
  }
  
  // TODO: getShareVelocity()
  
  public Article getArticle() {
    return article;
  }
  
  public int getAgeInMillis() {
    return (int) (System.currentTimeMillis() - article.getPublishedTime()); 
  }
  
  public boolean containsInterest(UserInterest interest) {
    for (ArticleKeyword keyword : keywords) {
      if (keyword.getKeyword().equals(interest.getKeyword())) {
        return true;
      }
    }
    return false;
  }
  
  public double getSimilarityToIndustry(int industryCode) {
    for (ArticleIndustryClassification classification : industryClassifications) {
      if (classification.getIndustryCodeId() == industryCode) {
        return classification.getSimilarity();
      }
    }
    return 0;
  }
  
  public String getIndustryClassificationsString() {
    String output = "[";
    for (ArticleIndustryClassification classification : industryClassifications) {
      output += IndustryCodes.INDUSTRY_CODE_MAP.get(
          classification.getIndustryCodeId()).getDescription();
      output += ": ";
      output += classification.getSimilarity();
      output += ", ";
    }
    output = output.substring(0, output.length() - 2) + "]";
    return output;
  }
  
  public boolean containsKeyword(String keyword) {
    for (ArticleKeyword articleKeyword : keywords) {
      if (articleKeyword.getKeyword().equals(keyword)) {
        return true;
      }
    }
    return false;
  }
  
  // Utility methods
  // TODO: Compute at crawl and save to Article
  public int wordCount() {
    int paragraphCount = article.getParagraphCount();
    int wordCount = 0;
    for (int i = 0; i < paragraphCount; i++) {
      wordCount += CompleteArticle.countWords(article.getParagraph(i));
    }
    return wordCount;
  }
  
  public static int countWords(String s){
    Pattern pattern = Pattern.compile("[\\s]+");
    Matcher matcher = pattern.matcher(s);
    int words = 0;
    while (matcher.find()) {
      words++;
    }
    return words + 1;
  }
}