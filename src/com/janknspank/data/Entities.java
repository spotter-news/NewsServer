package com.janknspank.data;

import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import com.google.common.base.Predicates;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.CacheLoader;
import com.google.common.cache.LoadingCache;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.janknspank.proto.Core.Entity;
import com.janknspank.proto.Core.Entity.EntityTopic;

public class Entities extends CacheLoader<String, Entity> {
  public static final int MAX_KEYWORD_LENGTH =
      Database.getStringLength(Entity.class, "keyword");
  public static final int MAX_TOPIC_KEYWORD_LENGTH =
      Database.getStringLength(EntityTopic.class, "keyword");

  private static LoadingCache<String, Entity> ENTITY_CACHE =
      CacheBuilder.newBuilder()
          .maximumSize(10000)
          .expireAfterAccess(5, TimeUnit.DAYS)
          .expireAfterWrite(0, TimeUnit.MINUTES)
          .build(new Entities());
  private static final String SELECT_BY_KEYWORD_COMMAND =
      "SELECT * FROM " + Database.getTableName(Entity.class) + " WHERE keyword=? LIMIT 1";

  public static Entity getEntityByKeyword(String keyword) throws DataInternalException {
    return Iterables.getFirst(getEntitiesByKeyword(ImmutableList.of(keyword)), null);
  }

  public static Iterable<Entity> getEntitiesByKeyword(Iterable<String> keywords)
      throws DataInternalException {
    List<Entity> entities = Lists.newArrayList();
    Map<String, Integer> keywordsToFetch = Maps.newHashMap();
    for (String keyword : keywords) {
      Entity entity = ENTITY_CACHE.getIfPresent(keyword);
      entities.add(entity);
      if (entity == null) {
        keywordsToFetch.put(keyword, entities.size() - 1);
      }
    }
    Database database = Database.getInstance();
    for (Entity entity : database.get("keyword", keywordsToFetch.keySet(), Entity.class)) {
      if (!keywordsToFetch.containsKey(entity.getKeyword())) {
        System.out.println("GOT ENTITY W/ UNREQUESTED KEYWORD!! " + entity.getKeyword());
        continue;
      }
      entities.set(keywordsToFetch.get(entity.getKeyword()), entity);
    }
    return Iterables.filter(entities, Predicates.not(Predicates.isNull()));
  }

  /**
   * DO NOT CALL THIS DIRECTLY.
   * @see #getEntityByKeyword(String)
   */
  @Override
  public Entity load(final String keyword) throws Exception {
    try {
      PreparedStatement statement =
          Database.getInstance().prepareStatement(SELECT_BY_KEYWORD_COMMAND);
      statement.setString(1, keyword);
      Entity entity = Database.createFromResultSet(statement.executeQuery(), Entity.class);

      // Unfortunately Guava's cache won't let us return null here, so we do
      // heroics.
      return entity == null ? Entity.getDefaultInstance() : entity;

    } catch (SQLException e) {
      throw new DataInternalException("Could not select entity: " + e.getMessage(), e);
    }
  }

  /** Helper method for creating the Article table. */
  public static void main(String args[]) throws Exception {
    Database database = Database.getInstance();
    database.prepareStatement(database.getCreateTableStatement(Entity.class)).execute();
    for (String statement : database.getCreateIndexesStatement(Entity.class)) {
      database.prepareStatement(statement).execute();
    }
  }
}