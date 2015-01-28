package com.janknspank.facebook;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.util.Properties;

import com.janknspank.data.DataInternalException;
import com.janknspank.data.Database;
import com.janknspank.data.ValidationException;
import com.janknspank.proto.Core.ArticleFacebookEngagement;
import com.restfb.DefaultFacebookClient;
import com.restfb.FacebookClient;
import com.restfb.Parameter;
import com.restfb.Version;
import com.restfb.exception.FacebookOAuthException;
import com.restfb.json.JsonException;
import com.restfb.json.JsonObject;

public class FacebookData {
  private static FacebookClient facebookClient;
  
  static {
    generateFacebookClient();
  }
  
  public static ArticleFacebookEngagement getEngagementForURL(String url) 
      throws DataInternalException {
    ArticleFacebookEngagement engagement = null;
    try {
      // Example urlObject: http://goo.gl/JVf3tt
      String encodedURL;
      try {
        encodedURL = URLEncoder.encode(url, "UTF-8");
      } catch (UnsupportedEncodingException e) {
        System.out.println("Can't encode url: " + url);
        encodedURL = url;
      }
      JsonObject urlObject = facebookClient.fetchObject(encodedURL, JsonObject.class);
      
      // Get shares and comments
      if (!urlObject.has("share")) {
        // There is no engagement if the share object is missing
        engagement = ArticleFacebookEngagement.newBuilder()
            .setUrl(url)
            .setLikeCount(0)
            .setShareCount(0)
            .setCommentCount(0)
            .setCreateTime(System.currentTimeMillis())
            .build();
      }
      else {
        JsonObject shareObject = urlObject.getJsonObject("share");
        int shareCount = shareObject.getInt("share_count");
        int commentCount = shareObject.getInt("comment_count");
        
        // Get likes
        String objectId = urlObject.getJsonObject("og_object")
            .getString("id");
        JsonObject likesObject = facebookClient.fetchObject(objectId, 
            JsonObject.class,
            Parameter.with("fields", "likes.summary(true)"));
        int likeCount = likesObject.getJsonObject("likes")
            .getJsonObject("summary")
            .getInt("total_count");
        
        engagement = ArticleFacebookEngagement.newBuilder()
            .setUrl(url)
            .setLikeCount(likeCount)
            .setShareCount(shareCount)
            .setCommentCount(commentCount)
            .setCreateTime(System.currentTimeMillis())
            .build();
      }
    } catch (FacebookOAuthException e) {
      e.printStackTrace();
      throw new DataInternalException("Can't get FB engagement for url " 
          + url + ": " + e.getMessage(), e);
    } catch (JsonException e) {
      e.printStackTrace();
      throw new DataInternalException("Can't parse Facebook JSON: " + e.getMessage(), e);
    }
    
    // Save the engagement object
    try {
      Database.insert(engagement);
    } catch (ValidationException e) {
      throw new DataInternalException("Error inserting facebook engagement:" + e.getMessage(), e);
    }
    
    return engagement;
  }
  
  private static void generateFacebookClient() {
    try {
      Properties properties = getFacebookProperties();
      String appSecret = properties.getProperty("appSecret");
      String appId = properties.getProperty("appId");
      facebookClient = new DefaultFacebookClient(appId + "|" + appSecret, appSecret, Version.VERSION_2_2);
    } catch (IOException e) {
      e.printStackTrace();
    }
  }
  
  private static Properties getFacebookProperties() throws IOException {
    Properties properties = new Properties();
    String propFileName = "facebook.properties";
    InputStream inputStream = new FileInputStream(propFileName);
    properties.load(inputStream);
    return properties;
  }
}