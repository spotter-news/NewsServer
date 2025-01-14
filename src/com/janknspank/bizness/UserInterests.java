package com.janknspank.bizness;

import java.util.List;
import java.util.Set;

import com.google.api.client.util.Lists;
import com.google.common.base.Predicate;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Sets;
import com.janknspank.classifier.FeatureId;
import com.janknspank.proto.UserProto.AddressBookContact;
import com.janknspank.proto.UserProto.Interest;
import com.janknspank.proto.UserProto.Interest.InterestSource;
import com.janknspank.proto.UserProto.Interest.InterestType;
import com.janknspank.proto.UserProto.LinkedInContact;
import com.janknspank.proto.UserProto.User;

public class UserInterests {
  /**
   * Returns only valid, currently followed user interests. 
   * Filters out TOMBSTONEd interests
   */
  public static List<Interest> getInterests(User user) {
    // There's a method behind this madness: Interests can come from anywhere.
    // A user can follow Tumblr, then go work for Tumblr (which we should pick
    // up and automatically add as an interest), then quit Tumblr... In which
    // case, he should keep following Tumblr, because he followed it before we
    // picked it up from LinkedIn.  But, if at any point the user stopped
    // following Tumblr, we should store a TOMBSTONE deletion marker of this
    // interest, which will win over interests whether they're implicit or
    // explicit.
    // NOTE(jonemerson): Yes, this is O(N^2), but N is <<< 100, so whatev.
    List<Interest> activeInterests = Lists.newArrayList();
    final List<Interest> tombstoneInterests = Lists.newArrayList();
    for (Interest interest : user.getInterestList()) {
      if (interest.getSource() == InterestSource.TOMBSTONE) {
        if (!equivalentExists(tombstoneInterests, interest)) {
          tombstoneInterests.add(interest);
        }
      } else {
        if (!equivalentExists(activeInterests, interest)) {
          activeInterests.add(interest);
        }
      }
    }
    return ImmutableList.copyOf(Iterables.filter(activeInterests, new Predicate<Interest>() {
      @Override
      public boolean apply(Interest interest) {
        return !equivalentExists(tombstoneInterests, interest);
      }
    }));
  }

  /**
   * Returns true if the specified {@code interest} is equivalent to any
   * Interest in {@code interestList}.
   */
  private static boolean equivalentExists(
      final Iterable<Interest> interestList, final Interest interest) {
    return Iterables.any(interestList, new Predicate<Interest>() {
      @Override
      public boolean apply(Interest interestListInterest) {
        return isConflict(interestListInterest, interest);
      }
    });
  }

  /**
   * Returns if the two interests are about the same thing, but not if they are
   * from the same source.
   */
  public static boolean isConflict(Interest interest1, Interest interest2) {
    if (interest1.getType() == interest2.getType()) {
      if (interest1.getType() == InterestType.ADDRESS_BOOK_CONTACTS ||
          interest1.getType() == InterestType.LINKED_IN_CONTACTS) {
        return true;
      } else if (interest1.getType() == InterestType.ENTITY) {
        EntityType entityType1 = EntityType.fromValue(interest1.getEntity().getType());
        EntityType entityType2 = EntityType.fromValue(interest2.getEntity().getType());
        return interest1.getEntity().getKeyword().equals(interest2.getEntity().getKeyword())
            && (entityType1 == null
                || entityType2 == null
                || entityType1.isA(entityType2)
                || entityType2.isA(entityType1));
      } else if (interest1.getType() == InterestType.INDUSTRY) {
        return interest1.getIndustryCode() == interest2.getIndustryCode();
      }
    }

    // Expressions where there's a YES and a NO intersect each other.
    Set<InterestType> expressionInterestTypeSet = ImmutableSet.of(
        InterestType.EXPRESSION_YES,
        InterestType.EXPRESSION_NO);
    if (expressionInterestTypeSet.contains(interest1.getType())
        && expressionInterestTypeSet.contains(interest2.getType())
        && interest1.getExpressionId().equals(interest2.getExpressionId())) {
      return true;
    }

    return false;
  }

  /**
   * Returns a lower-cased Set of all the strings for entities the user's
   * following.
   */
  public static Set<String> getUserKeywordSet(User user, Set<InterestType> forcedInterests) {
    Set<String> userKeywordSet = Sets.newHashSet();
    boolean includeLinkedInContacts =
        forcedInterests.contains(InterestType.LINKED_IN_CONTACTS);
    boolean includeAddressBookContacts =
        forcedInterests.contains(InterestType.ADDRESS_BOOK_CONTACTS);
    for (Interest interest : getInterests(user)) {
      if (interest.getType() == InterestType.ENTITY) {
        userKeywordSet.add(interest.getEntity().getKeyword().toLowerCase());
      } else if (interest.getType() == InterestType.LINKED_IN_CONTACTS) {
        includeLinkedInContacts = true;
      } else if (interest.getType() == InterestType.ADDRESS_BOOK_CONTACTS) {
        includeAddressBookContacts = true;
      }
    }
    if (includeLinkedInContacts) {
      for (LinkedInContact contact : user.getLinkedInContactList()) {
        userKeywordSet.add(contact.getName().toLowerCase());
      }
    }
    if (includeAddressBookContacts) {
      for (AddressBookContact contact : user.getAddressBookContactList()) {
        userKeywordSet.add(contact.getName().toLowerCase());
      }
    }
    return userKeywordSet;
  }

  /**
   * Returns a Set of all the keywords the user has unfollowed.
   */
  public static Set<String> getTombstones(User user) {
    Set<String> tombstones = Sets.newHashSet();
    for (Interest interest : user.getInterestList()) {
      // TODO(jonemerson): Differentiate people tombstones from company
      // tombstones.  But right now the client doesn't tell us which is
      // which either, so it's actually a bit of work...
      if (interest.getType() == InterestType.ENTITY
          && interest.getSource() == InterestSource.TOMBSTONE) {
        tombstones.add(interest.getEntity().getKeyword());
      }
    }
    return tombstones;
  }

  /**
   * Returns a Set of all the industries the passed user is following, as
   * FeatureId objects.
   */
  public static Set<FeatureId> getUserIndustryFeatureIds(User user) {
    ImmutableSet.Builder<FeatureId> setBuilder = ImmutableSet.builder();
    for (Interest interest : UserInterests.getInterests(user)) {
      if (interest.getType() == InterestType.INDUSTRY) {
        FeatureId featureId = FeatureId.fromId(interest.getIndustryCode());
        if (featureId != null) {
          setBuilder.add(featureId);
        }
      }
    }
    return setBuilder.build();
  }

  public static Set<Integer> getUserIndustryFeatureIdIds(User user) {
    ImmutableSet.Builder<Integer> setBuilder = ImmutableSet.builder();
    for (FeatureId featureId : getUserIndustryFeatureIds(user)) {
      setBuilder.add(featureId.getId());
    }
    return setBuilder.build();
  }
}
