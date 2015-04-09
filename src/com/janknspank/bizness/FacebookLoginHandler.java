package com.janknspank.bizness;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.regex.Pattern;

import com.google.api.client.util.Lists;
import com.google.common.base.Predicate;
import com.google.common.base.Splitter;
import com.google.common.base.Strings;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.ListenableFuture;
import com.janknspank.classifier.Feature;
import com.janknspank.classifier.FeatureId;
import com.janknspank.classifier.FeatureType;
import com.janknspank.classifier.Vector;
import com.janknspank.classifier.VectorFeature;
import com.janknspank.common.TopList;
import com.janknspank.crawler.social.FacebookData;
import com.janknspank.crawler.social.SocialException;
import com.janknspank.database.Database;
import com.janknspank.database.DatabaseRequestException;
import com.janknspank.database.DatabaseSchemaException;
import com.janknspank.database.QueryOption;
import com.janknspank.nlp.KeywordCanonicalizer;
import com.janknspank.nlp.KeywordFinder;
import com.janknspank.nlp.KeywordUtils;
import com.janknspank.proto.CoreProto.Entity;
import com.janknspank.proto.CoreProto.Entity.Source;
import com.janknspank.proto.CoreProto.VectorData;
import com.janknspank.proto.CoreProto.VectorData.WordFrequency;
import com.janknspank.proto.UserProto.Interest;
import com.janknspank.proto.UserProto.Interest.InterestSource;
import com.janknspank.proto.UserProto.Interest.InterestType;
import com.janknspank.proto.UserProto.User;
import com.janknspank.server.RequestException;
import com.restfb.DefaultFacebookClient;
import com.restfb.FacebookClient;
import com.restfb.Version;
import com.restfb.types.NamedFacebookType;
import com.restfb.types.User.Education;
import com.restfb.types.User.EducationClass;
import com.restfb.types.User.Work;

/**
 * Handles User creation and/or retrieval from Facebook OAuth tokens.  Updates
 * the user's implicit interests with industries and entities from the user's
 * latest and greatest Facebook profile.
 */
public class FacebookLoginHandler {
  private static final Pattern WHITESPACE_PATTERN = Pattern.compile("(\\s|\\n|\\r|\\xA0)+");

  public static com.restfb.types.User getFacebookUser(String fbAccessToken, String fbUserId)
      throws SocialException, RequestException {
    FacebookClient facebookClient = new DefaultFacebookClient(
        fbAccessToken, FacebookData.getFacebookAppSecret(), Version.VERSION_2_2);
    com.restfb.types.User fbUser =
        facebookClient.fetchObject("/me", com.restfb.types.User.class);
    if (fbUser == null) {
      throw new RequestException("Could not retrieve user from Facebook");
    } else if (!fbUser.getId().equals(fbUserId)) {
      throw new RequestException("fb_access_token is not for fb_user_id");
    }
    return fbUser;
  }

  /**
   * Constructs a plain new User object starting with the passed-in email
   * address.
   */
  private static User.Builder getNewUserBuilder(
      com.restfb.types.User fbUser, String fbAccessToken) {
    User.Builder newUserBuilder = User.newBuilder()
        .setId(GuidFactory.generate())
        .setFirstName(fbUser.getFirstName())
        .setLastName(fbUser.getLastName())
        .setFacebookId(fbUser.getId())
        .setFacebookAccessToken(fbAccessToken)
        .setCreateTime(System.currentTimeMillis())
        .setLastLoginTime(System.currentTimeMillis());
    if (!Strings.isNullOrEmpty(fbUser.getEmail())) {
      newUserBuilder.setEmail(fbUser.getEmail());
    }
    return newUserBuilder;
  }

  /**
   * Returns a NewsServer user builder object for the passed Facebook user
   * object, if we've already created a user object for the Facebook user
   * before.
   */
  private static User getExistingUser(
      com.restfb.types.User fbUser, String fbAccessToken)
      throws DatabaseSchemaException {
    ListenableFuture<Iterable<User>> userByFacebookIdFuture =
        Database.with(User.class).getFuture(
            new QueryOption.WhereEquals("facebook_id", fbUser.getId()));
    ListenableFuture<Iterable<User>> userByEmailFuture =
        Database.with(User.class).getFuture(
            new QueryOption.WhereEquals("email", fbUser.getEmail()));
    try {
      User existingUser = Iterables.getFirst(userByFacebookIdFuture.get(), null);
      return (existingUser != null)
          ? existingUser
          : Iterables.getFirst(userByEmailFuture.get(), null);
    } catch (InterruptedException | ExecutionException e) {
      e.printStackTrace();
    }
    return null;
  }

  /**
   * Returns a Set of the names of the companies the given user has worked at.
   */
  private static Set<String> getCompanyNames(com.restfb.types.User fbUser) {
    Set<String> companyNames = Sets.newHashSet();
    for (Work work : fbUser.getWork()) {
      companyNames.add(work.getEmployer().getName());
    }
    return companyNames;
  }

  /**
   * Puts all the human-readable-text we have about the current user, at least
   * as far as professional interests go, into a Vector that we can use to
   * compare against industries.
   */
  private static Vector getFacebookUserVector(com.restfb.types.User fbUser) {
    VectorData.Builder builder = VectorData.newBuilder();
    for (String companyName : getCompanyNames(fbUser)) {
      for (String word : Splitter.on(WHITESPACE_PATTERN).split(companyName)) {
        builder.addWordFrequency(WordFrequency.newBuilder()
            .setWord(word)
            .setFrequency(10));
      }
    }
    for (Work work : fbUser.getWork()) {
      if (work.getPosition() != null) {
        for (String word : Splitter.on(WHITESPACE_PATTERN).split(work.getPosition().getName())) {
          builder.addWordFrequency(WordFrequency.newBuilder()
              .setWord(word)
              .setFrequency(10));
        }
      }
      if (!Strings.isNullOrEmpty(work.getDescription())) {
        for (String sentence : KeywordFinder.getInstance().getSentences(work.getDescription())) {
          for (String token : KeywordFinder.getInstance().getTokens(sentence)) {
            builder.addWordFrequency(WordFrequency.newBuilder()
                .setWord(KeywordUtils.cleanKeyword(token))
                .setFrequency(5));
          }
        }
      }
    }
    for (Education education : fbUser.getEducation()) {
      for (NamedFacebookType concentration : education.getConcentration()) {
        if (!Strings.isNullOrEmpty(concentration.getName())) {
          for (String word : Splitter.on(WHITESPACE_PATTERN).split(concentration.getName())) {
            builder.addWordFrequency(WordFrequency.newBuilder()
                .setWord(word)
                .setFrequency(5));
          }
        }
      }
      NamedFacebookType school = education.getSchool();
      if (school != null && !Strings.isNullOrEmpty(school.getName())) {
        for (String word : Splitter.on(WHITESPACE_PATTERN).split(school.getName())) {
          builder.addWordFrequency(WordFrequency.newBuilder()
              .setWord(word)
              .setFrequency(1));
        }
      }
      for (EducationClass educationClass : education.getClasses()) {
        if (!Strings.isNullOrEmpty(educationClass.getName())) {
          for (String word : Splitter.on(WHITESPACE_PATTERN).split(educationClass.getName())) {
            builder.addWordFrequency(WordFrequency.newBuilder()
                .setWord(word)
                .setFrequency(1));
          }
        }
      }
    }
    NamedFacebookType location = fbUser.getLocation();
    if (location != null) {
      String locationName = location.getName();
      if (!Strings.isNullOrEmpty(locationName)) {
        for (String word : Splitter.on(WHITESPACE_PATTERN).split(locationName)) {
          builder.addWordFrequency(WordFrequency.newBuilder()
              .setWord(word)
              .setFrequency(1));
        }
      }
    }
    String about = fbUser.getAbout();
    if (!Strings.isNullOrEmpty(about)) {
      for (String sentence : KeywordFinder.getInstance().getSentences(about)) {
        for (String token : KeywordFinder.getInstance().getTokens(sentence)) {
          builder.addWordFrequency(WordFrequency.newBuilder()
              .setWord(KeywordUtils.cleanKeyword(token))
              .setFrequency(1));
        }
      }
    }
    return new Vector(builder.build());
  }

  private static Iterable<FeatureId> getTop3IndustryFeatureIds(com.restfb.types.User fbUser) {
    Vector facebookUserVector = getFacebookUserVector(fbUser);
    TopList<FeatureId, Double> top3IndustryFeatureIds = new TopList<>(3);
    for (Feature feature : Feature.getAllFeatures()) {
      if (feature.getFeatureId().getFeatureType() == FeatureType.INDUSTRY
          && feature instanceof VectorFeature) {
        top3IndustryFeatureIds.add(feature.getFeatureId(),
            ((VectorFeature) feature).score(facebookUserVector, 0 /* boost */));
      }
    }
    return top3IndustryFeatureIds.getKeys();
  }

  /**
   * Returns a List of Interest objects for industries, companies, skills, and
   * other noun-like entities in the user's Facebook profile.  Each Interest
   * will have a Source of FACEBOOK_PROFILE.
   */
  private static Iterable<Interest> getFacebookProfileInterests(com.restfb.types.User fbUser)
      throws DatabaseSchemaException {
    // Create a Map of company name to existing Entity objects, using either
    // our very-helpful-fuzzy-logic-friendly KeywordToEntityId table, or by
    // hoping the user happened to type an Entity we know exactly about.
    Set<String> companyNames = getCompanyNames(fbUser);
    Map<String, Entity> companyEntityMap = Maps.newHashMap();
    for (String companyName : companyNames) {
      Entity entity = KeywordCanonicalizer.getEntityForKeyword(companyName);
      if (entity != null) {
        companyEntityMap.put(companyName.toLowerCase(), entity);
      }
    }
    for (Entity entity : Entities.getEntitiesByKeyword(
        Sets.difference(companyNames, ImmutableSet.copyOf(companyEntityMap.keySet())))) {
      // Here we're fetching Entities for any companies for which there weren't
      // KeywordToEntityId table rows for.
      companyEntityMap.put(entity.getKeyword().toLowerCase(), entity);
    }

    // Start building interests.
    List<Interest> interests = Lists.newArrayList();
    for (String companyName : companyNames) {
      Interest.Builder companyInterestBuilder = Interest.newBuilder()
          .setId(GuidFactory.generate())
          .setType(InterestType.ENTITY)
          .setSource(InterestSource.FACEBOOK_PROFILE)
          .setCreateTime(System.currentTimeMillis());
      if (companyEntityMap.containsKey(companyName.toLowerCase())) {
        companyInterestBuilder.setEntity(companyEntityMap.get(companyName.toLowerCase()));
      } else {
        companyInterestBuilder.setEntity(Entity.newBuilder()
            .setId(GuidFactory.generate())
            .setKeyword(companyName)
            .setType(EntityType.COMPANY.toString())
            .setSource(Source.USER)
            .build());
      }
      interests.add(companyInterestBuilder.build());
    }
    for (FeatureId industryFeatureId : getTop3IndustryFeatureIds(fbUser)) {
      interests.add(Interest.newBuilder()
          .setId(GuidFactory.generate())
          .setType(InterestType.INDUSTRY)
          .setIndustryCode(industryFeatureId.getId())
          .setSource(InterestSource.FACEBOOK_PROFILE)
          .setCreateTime(System.currentTimeMillis())
          .build());
    }
    return interests;
  }

  public static User login(com.restfb.types.User fbUser, String fbAccessToken)
      throws RequestException, SocialException, DatabaseSchemaException,
      DatabaseRequestException {
    User user;
    User existingUser = getExistingUser(fbUser, fbAccessToken);
    Iterable<Interest> facebookProfileInterests = getFacebookProfileInterests(fbUser);
    if (existingUser != null) {
      Iterable<Interest> existingInterestsToRetain = Iterables.filter(
          existingUser.getInterestList(),
          new Predicate<Interest>() {
            @Override
            public boolean apply(Interest interest) {
              // Clear out historical LinkedIn implicit interests while we're
              // here.  NOTE(jonemerson): I think it's a good idea to do this,
              // for our beta testers.  But maybe it's not.  We'll see.
              return interest.getSource() != InterestSource.FACEBOOK_PROFILE
                  && interest.getSource() != InterestSource.LINKED_IN_PROFILE;
            }
          });
      user = existingUser.toBuilder()
          .clearInterest()
          .addAllInterest(existingInterestsToRetain)
          .addAllInterest(facebookProfileInterests)
          .setFacebookId(fbUser.getId())
          .setFacebookAccessToken(fbAccessToken)
          .setLastLoginTime(System.currentTimeMillis())
          .setEmail(!Strings.isNullOrEmpty(fbUser.getEmail())
              ? fbUser.getEmail()
              : existingUser.getEmail())
          .build();
      Database.update(user);
    } else {
      user = getNewUserBuilder(fbUser, fbAccessToken)
          .addAllInterest(facebookProfileInterests)
          .build();
      Database.insert(user);
    }

    return user;
  }
}
