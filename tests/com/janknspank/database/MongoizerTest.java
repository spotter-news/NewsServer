package com.janknspank.database;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.bson.types.BasicBSONList;
import org.junit.Test;

import com.janknspank.proto.ArticleProto.ArticleKeyword;
import com.janknspank.proto.CoreProto.Entity;
import com.janknspank.proto.CoreProto.Entity.EntityTopic;
import com.janknspank.proto.CoreProto.Entity.EntityTopic.Context;
import com.janknspank.proto.CoreProto.Entity.Source;
import com.mongodb.BasicDBObject;

public class MongoizerTest {
  private static final String ID = "01234567890123456789abcd";
  private static final Entity ENTITY = Entity.newBuilder()
      .setId(ID)
      .setSource(Source.ANGELLIST)
      .setKeyword("keyWoorrrd")
      .setType("mT")
      .addTopic(EntityTopic.newBuilder()
          .setEntityId("entityId")
          .setKeyword("moose")
          .setStrength(500)
          .setType("T1")
          .setContext(Context.WIKIPEDIA_SUBTOPIC)
          .build())
      .addTopic(EntityTopic.newBuilder()
          .setEntityId("secondEntity")
          .setKeyword("ducks")
          .setStrength(2)
          // Skip type.
          // Skip context.
          .build())
      .build();

  @Test
  public void testToDBObject() throws Exception {
    BasicDBObject dbObject = Mongoizer.toDBObject(ENTITY);
    assertEquals("01234567890123456789abcd", dbObject.getObjectId("_id").toHexString());
    assertEquals(ENTITY.getSource(), Source.valueOf(dbObject.getString("source")));
    assertEquals(ENTITY.getKeyword(), dbObject.getString("keyword"));
    assertEquals(ENTITY.getType(), dbObject.getString("type"));

    BasicBSONList topicList = ((BasicBSONList) dbObject.get("topic"));
    assertEquals(ENTITY.getTopicCount(), topicList.size());

    BasicDBObject dbTopic1 = (BasicDBObject) topicList.get(0);
    assertEquals(ENTITY.getTopic(0).getEntityId(), dbTopic1.getString("entity_id"));
    assertEquals(ENTITY.getTopic(0).getKeyword(), dbTopic1.getString("keyword"));
    assertEquals(ENTITY.getTopic(0).getStrength(), dbTopic1.getInt("strength"));
    assertEquals(ENTITY.getTopic(0).getType(), dbTopic1.getString("type"));
    assertEquals(ENTITY.getTopic(0).getContext().toString(), dbTopic1.getString("context"));

    BasicDBObject dbTopic2 = (BasicDBObject) ((BasicBSONList) dbObject.get("topic")).get(1);
    assertEquals(ENTITY.getTopic(1).getEntityId(), dbTopic2.getString("entity_id"));
    assertEquals(ENTITY.getTopic(1).getKeyword(), dbTopic2.getString("keyword"));
    assertEquals(ENTITY.getTopic(1).getStrength(), dbTopic2.getInt("strength"));
    assertFalse(dbTopic2.containsField("type"));
    assertFalse(dbTopic2.containsField("context"));

    Entity translatedEntity = Mongoizer.fromDBObject(dbObject, Entity.class);
    assertEquals(ENTITY.getId(), translatedEntity.getId());
    assertEquals(ENTITY.getSource(), translatedEntity.getSource());
    assertEquals(ENTITY.getKeyword(), translatedEntity.getKeyword());
    assertEquals(ENTITY.getType(), translatedEntity.getType());
    assertEquals(ENTITY.getTopicCount(), translatedEntity.getTopicCount());
    assertEquals(ENTITY.getTopic(0).getEntityId(), translatedEntity.getTopic(0).getEntityId());
    assertEquals(ENTITY.getTopic(0).getKeyword(), translatedEntity.getTopic(0).getKeyword());
    assertEquals(ENTITY.getTopic(0).getStrength(), translatedEntity.getTopic(0).getStrength());
    assertEquals(ENTITY.getTopic(0).getType(), translatedEntity.getTopic(0).getType());
    assertEquals(ENTITY.getTopic(0).getContext(), translatedEntity.getTopic(0).getContext());
    assertEquals(ENTITY.getTopic(1).getEntityId(), translatedEntity.getTopic(1).getEntityId());
    assertEquals(ENTITY.getTopic(1).getKeyword(), translatedEntity.getTopic(1).getKeyword());
    assertEquals(ENTITY.getTopic(1).getStrength(), translatedEntity.getTopic(1).getStrength());
    assertFalse(ENTITY.getTopic(1).hasType()); // Test of this test.
    assertFalse(translatedEntity.getTopic(1).hasType());
    assertFalse(ENTITY.getTopic(1).hasContext()); // Test of this test.
    assertFalse(translatedEntity.getTopic(1).hasContext());
  }

  @Test
  public void test() throws Exception {
    ArticleKeyword articleKeyword = ArticleKeyword.newBuilder()
        .setKeyword("Jorge Pasilda")
        .setSource(ArticleKeyword.Source.META_TAG)
        .setStrength(15)
        .setType("p")
        .build();
    BasicDBObject dbObject = Mongoizer.toDBObject(articleKeyword);
    assertEquals("Jorge Pasilda", dbObject.getString("keyword"));
    assertEquals(ArticleKeyword.Source.META_TAG.toString(), dbObject.getString("source"));
    assertEquals(15, dbObject.getInt("strength"));
    assertEquals("p", dbObject.getString("type"));

    // Now, put an invalid enum value into the dbObject, and make sure that we
    // don't crash while parsing it.  (Instead, the enum should  be undefined.)
    dbObject.put("source", "FUTURE_SOURCE_XXX");
    ArticleKeyword keywordFromFuture = Mongoizer.fromDBObject(dbObject, ArticleKeyword.class);
    assertEquals("Jorge Pasilda", keywordFromFuture.getKeyword());
    assertFalse("Source should not be set.", keywordFromFuture.hasSource());
  }
}
