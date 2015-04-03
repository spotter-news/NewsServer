package com.janknspank.crawler.facebook;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Properties;

import org.apache.commons.io.IOUtils;

import com.janknspank.bizness.Articles;
import com.janknspank.classifier.ClassifierException;
import com.janknspank.common.DateParser;
import com.janknspank.common.Logger;
import com.janknspank.proto.ArticleProto.ArticleOrBuilder;
import com.janknspank.proto.ArticleProto.SocialEngagement;
import com.janknspank.proto.ArticleProto.SocialEngagement.Site;
import com.janknspank.proto.CoreProto.Url;
import com.restfb.DefaultFacebookClient;
import com.restfb.FacebookClient;
import com.restfb.Parameter;
import com.restfb.Version;
import com.restfb.exception.FacebookOAuthException;
import com.restfb.json.JsonException;
import com.restfb.json.JsonObject;

public class FacebookData {
  private static final Logger LOG = new Logger(FacebookData.class);
  private static String __facebookAppSecret = null;
  private static FacebookClient __facebookClient = null;

  private static String encodeUrl(String url) {
    // Example urlObject: http://goo.gl/JVf3tt
    try {
      return URLEncoder.encode(url, "UTF-8");
    } catch (UnsupportedEncodingException e) {
      LOG.warning("Can't encode url: " + url);
      return url;
    }
  }

  public static Long getPublishTime(Url url) throws FacebookException {
    String encodedURL = encodeUrl(url.getUrl());
    JsonObject urlObject = getFacebookClient().fetchObject(encodedURL, JsonObject.class);
    if (urlObject != null
        && urlObject.has("og_object")
        && urlObject.getJsonObject("og_object").has("id")) {
      String objectId = urlObject.getJsonObject("og_object").getString("id");
      JsonObject object = getFacebookClient().fetchObject(objectId, JsonObject.class);
      if (object != null
          && object.has("created_time")) {
        return DateParser.parseDateTime(object.getString("created_time"));
      }
    }
    return null;
  }

  public static SocialEngagement getEngagementForURL(ArticleOrBuilder article) throws FacebookException {
    String url = article.getUrl();
    try {
      String encodedURL = encodeUrl(url);
      JsonObject urlObject = getFacebookClient().fetchObject(encodedURL, JsonObject.class);

      // Get shares and comments
      if (!urlObject.has("share")) {
        return null;
      }
      else {
        JsonObject shareObject = urlObject.getJsonObject("share");
        int shareCount = shareObject.getInt("share_count");
        int commentCount = shareObject.getInt("comment_count");

        // Get likes
        String objectId = urlObject.getJsonObject("og_object").getString("id");
        JsonObject likesObject = getFacebookClient().fetchObject(objectId, JsonObject.class,
            Parameter.with("fields", "likes.summary(true)"));
        int likeCount = likesObject.getJsonObject("likes")
            .getJsonObject("summary")
            .getInt("total_count");

        return SocialEngagement.newBuilder()
            .setSite(Site.FACEBOOK)
            .setLikeCount(likeCount)
            .setShareCount(shareCount)
            .setShareScore(FacebookShareNormalizer.getInstance().getShareScore(
                url,
                shareCount,
                System.currentTimeMillis() - Articles.getPublishedTime(article) /* ageInMillis */))
            .setCommentCount(commentCount)
            .setCreateTime(System.currentTimeMillis())
            .build();
      }
    } catch (ClassifierException e) {
      // FacebookShareNormalizer failed to instantiate from disk - this is an invalid state.
      throw new IllegalStateException(e);
    } catch (FacebookOAuthException e) {
      e.printStackTrace();
      throw new FacebookException("Can't get FB engagement for url "
          + url + ": " + e.getMessage(), e);
    } catch (JsonException e) {
      e.printStackTrace();
      throw new FacebookException("Can't parse Facebook JSON: " + e.getMessage(), e);
    }
  }

  private static synchronized FacebookClient getFacebookClient() throws FacebookException {
    if (__facebookClient == null) {
      Properties properties = getFacebookProperties();
      String appSecret = properties.getProperty("appSecret");
      String appId = properties.getProperty("appId");
      __facebookClient =
          new DefaultFacebookClient(appId + "|" + appSecret, appSecret, Version.VERSION_2_2);
    }
    return __facebookClient;
  }

  public static synchronized String getFacebookAppSecret() throws FacebookException {
    if (__facebookAppSecret == null) {
      Properties properties = getFacebookProperties();
      __facebookAppSecret = properties.getProperty("appSecret");
    }
    return __facebookAppSecret;
  }

  private static Properties getFacebookProperties() throws FacebookException {
    Properties properties = new Properties();
    InputStream inputStream = null;
    try {
      inputStream = new FileInputStream("facebook.properties");
      properties.load(inputStream);
      return properties;
    } catch (IOException e) {
      throw new FacebookException("Could not read facebook.properties: " + e.getMessage(), e);
    } finally {
      IOUtils.closeQuietly(inputStream);
    }
  }

  public static void main(String args[]) throws Exception {
    System.out.println(getPublishTime(Url.newBuilder()
        .setUrl("http://firstround.com/review/Top-Hacks-from-a-PM-Behind-Two-of"
            + "-Techs-Hottest-Products/")
        .build()));
  }
}
