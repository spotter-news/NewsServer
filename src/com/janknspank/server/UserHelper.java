package com.janknspank.server;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.json.JSONArray;
import org.json.JSONObject;

import com.google.common.base.Function;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.janknspank.database.Database;
import com.janknspank.database.DatabaseSchemaException;
import com.janknspank.database.Serializer;
import com.janknspank.proto.ArticleProto.Article;
import com.janknspank.proto.UserProto.UrlFavorite;
import com.janknspank.proto.UserProto.UrlRating;
import com.janknspank.proto.UserProto.User;

/**
 * Helper class containing the user's favorite and rated articles.
 */
public class UserHelper {
  private final User user;
  private final Map<String, Integer> ratedArticleIds;
  private final Map<String, Long> favoriteArticleIds;
  private final Map<String, Article> articleMap;

  public UserHelper(User user) throws DatabaseSchemaException {
    this.user = user;

    // Figure out the IDs of the articles the user has rated or favorited.
    ratedArticleIds = getRatedArticleIds();
    favoriteArticleIds = getFavoriteArticleIds();

    // Create a map of the articles.
    articleMap = getArticleMap(Iterables.concat(
        ratedArticleIds.keySet(), favoriteArticleIds.keySet()));
  }

  private Map<String, Integer> getRatedArticleIds() throws DatabaseSchemaException {
    Map<String, Integer> ratedArticleIds = Maps.newHashMap();
    for (UrlRating rating : user.getUrlRatingList()) {
      ratedArticleIds.put(rating.getUrlId(), rating.getRating());
    }
    return ratedArticleIds;
  }

  private Map<String, Long> getFavoriteArticleIds() throws DatabaseSchemaException {
    Map<String, Long> favoriteArticleIds = Maps.newHashMap();
    for (UrlFavorite favorite : user.getUrlFavoriteList()) {
      favoriteArticleIds.put(favorite.getUrlId(), favorite.getCreateTime());
    }
    return favoriteArticleIds;
  }

  private Map<String, Article> getArticleMap(Iterable<String> articleIds)
      throws DatabaseSchemaException {
    return Maps.uniqueIndex(
        Iterables.isEmpty(articleIds)
            ? Collections.<Article>emptyList()
            : Database.with(Article.class).get(articleIds),
        new Function<Article, String>() {
          @Override
          public String apply(Article article) {
            return article.getUrlId();
          }
        });
  }

  public JSONArray getRatingsJsonArray() {
    JSONArray ratingsJsonArray = new JSONArray();
    for (String urlId : ratedArticleIds.keySet()) {
      if (articleMap.containsKey(urlId)) {
        JSONObject ratingsJson = Serializer.toJSON(articleMap.get(urlId));
        ratingsJson.put("rating", ratedArticleIds.get(urlId));
        ratingsJsonArray.put(ratingsJson);
      }
    }
    return ratingsJsonArray;
  }

  public JSONArray getFavoritesJsonArray() {
    List<Article> favoriteArticles = Lists.newArrayList();
    for (String urlId : favoriteArticleIds.keySet()) {
      if (articleMap.containsKey(urlId)) {
        favoriteArticles.add(articleMap.get(urlId));
      }
    }

    Collections.sort(favoriteArticles, new Comparator<Article>() {
      @Override
      public int compare(Article o1, Article o2) {
        // Newest articles first, sorted by when they were favorited.
        return Long.compare(favoriteArticleIds.get(o2.getUrlId()),
            favoriteArticleIds.get(o1.getUrlId()));
      }
    });

    JSONArray favoritesJsonArray = new JSONArray();
    for (Article favoriteArticle : favoriteArticles) {
      JSONObject favoriteJson = Serializer.toJSON(favoriteArticle);
      favoriteJson.put("favorited_time", favoriteArticleIds.get(favoriteArticle.getUrlId()));
      favoritesJsonArray.put(favoriteJson);
    }
    return favoritesJsonArray;
  }

  public JSONArray getInterestsJsonArray() throws DatabaseSchemaException {
    return Serializer.toJSON(user.getInterestList());
  }
}