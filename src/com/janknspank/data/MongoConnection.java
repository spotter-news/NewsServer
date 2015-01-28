package com.janknspank.data;

import java.net.UnknownHostException;
import java.util.Arrays;

import com.mongodb.DB;
import com.mongodb.MongoClient;
import com.mongodb.MongoCredential;
import com.mongodb.ServerAddress;

public class MongoConnection {
  private static final String MONGO_HOST;
  private static final String MONGO_DATABASE = "newsserver";
  private static final String MONGO_USER;
  private static final String MONGO_PASSWORD;
  static {
    MONGO_HOST = System.getenv("MONGO_HOST");
    if (MONGO_HOST == null) {
      throw new IllegalStateException("$MONGO_HOST is undefined");
    }
    MONGO_USER = System.getenv("MONGO_USER");
    if (MONGO_USER == null) {
      throw new IllegalStateException("$MONGO_USER is undefined");
    }
    MONGO_PASSWORD = System.getenv("MONGO_PASSWORD");
    if (MONGO_PASSWORD == null) {
      throw new IllegalStateException("$MONGO_PASSWORD is undefined");
    }
  }

  private static MongoClient CLIENT = null;

  public static synchronized MongoClient getClient() throws DataInternalException {
    if (CLIENT == null) {
      try {
        MongoCredential credential = MongoCredential.createMongoCRCredential(
            MONGO_USER, MONGO_DATABASE, MONGO_PASSWORD.toCharArray());
        CLIENT = new MongoClient(new ServerAddress(MONGO_HOST), Arrays.asList(credential));
      } catch (UnknownHostException e) {
        throw new DataInternalException("Could not connect to MongoDB: " + e.getMessage(), e);
      }
    }
    return CLIENT;
  }

  public static DB getDatabase() throws DataInternalException {
    return getClient().getDB(MONGO_DATABASE);
  }
}