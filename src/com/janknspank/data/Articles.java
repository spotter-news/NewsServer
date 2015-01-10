package com.janknspank.data;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Set;

import com.google.common.base.Function;
import com.google.common.base.Joiner;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor.JavaType;
import com.janknspank.proto.Core.Article;
import com.janknspank.proto.Core.ArticleKeyword;
import com.janknspank.proto.Core.UserInterest;
import com.janknspank.proto.Extensions;

/**
 * Helper class that manages storing and retrieving Article objects from the
 * database.
 */
public class Articles {
  private static final String SELECT_ALL_COMMAND =
      "SELECT * FROM " + Database.getTableName(Article.class)
      + " ORDER BY published_time DESC LIMIT 50";

  public static final int MAX_TITLE_LENGTH;
  public static final int MAX_PARAGRAPH_LENGTH;
  public static final int MAX_DESCRIPTION_LENGTH;
  static {
    int titleLength = 0;
    int paragraphLength = 0;
    int descriptionLength = 0;
    for (FieldDescriptor field : Article.getDefaultInstance().getDescriptorForType().getFields()) {
      if (JavaType.STRING == field.getJavaType()) {
        if ("title".equals(field.getName())) {
          titleLength = field.getOptions().getExtension(Extensions.stringLength);
        } else if ("paragraph".equals(field.getName())) {
          paragraphLength = field.getOptions().getExtension(Extensions.stringLength);
        } else if ("description".equals(field.getName())) {
          descriptionLength = field.getOptions().getExtension(Extensions.stringLength);
        }
      }
    }
    if (titleLength == 0 || paragraphLength == 0 || descriptionLength == 0) {
      throw new IllegalStateException("Could not find length of title, paragraph or description");
    }
    MAX_TITLE_LENGTH = titleLength;
    MAX_PARAGRAPH_LENGTH = paragraphLength;
    MAX_DESCRIPTION_LENGTH = descriptionLength;
  }

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
    StringBuilder sql = new StringBuilder();
    sql.append("SELECT * FROM ")
        .append(Database.getTableName(ArticleKeyword.class))
        .append(" WHERE keyword IN (")
        .append(Joiner.on(",").join(
            Iterables.limit(Iterables.cycle("?"), Iterables.size(topics))))
        .append(") LIMIT 500");

    try {
      PreparedStatement stmt = Database.getConnection().prepareStatement(sql.toString());
      int i = 0;
      for (String topic : topics) {
        stmt.setString(++i, topic);
      }
      return Database.createListFromResultSet(stmt.executeQuery(), ArticleKeyword.class);
    } catch (SQLException e) {
      throw new DataInternalException("Error fetching article keywords: " + e.getMessage(), e);
    }
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

  /**
   * Returns articles with the given IDs, ordered by publish time, if they
   * exist. If they don't exist, no error is thrown.
   */
  public static List<Article> getArticles(Iterable<String> articleIds)
      throws DataInternalException {
    StringBuilder sql = new StringBuilder();
    sql.append("SELECT * FROM ")
        .append(Database.getTableName(Article.class))
        .append(" WHERE url_id IN (")
        .append(Joiner.on(",").join(Iterables.limit(Iterables.cycle("?"),
            Iterables.size(articleIds))))
        .append(") ORDER BY published_time DESC LIMIT 50");

    try {
      PreparedStatement stmt = Database.getConnection().prepareStatement(sql.toString());
      int i = 0;
      for (String articleId : articleIds) {
        stmt.setString(++i, articleId);
      }
      return Database.createListFromResultSet(stmt.executeQuery(), Article.class);
    } catch (SQLException e) {
      throw new DataInternalException("Error fetching articles: " + e.getMessage(), e);
    }
  }

  /** Helper method for creating the Article table. */
  public static void main(String args[]) throws Exception {
    Connection connection = Database.getConnection();
    connection.prepareStatement(Database.getCreateTableStatement(Article.class)).execute();
    for (String statement : Database.getCreateIndexesStatement(Article.class)) {
      connection.prepareStatement(statement).execute();
    }

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
