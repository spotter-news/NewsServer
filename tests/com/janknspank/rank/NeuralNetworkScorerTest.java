package com.janknspank.rank;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.Set;

import org.junit.Test;

import com.janknspank.classifier.FeatureId;
import com.janknspank.proto.UserProto.User;

public class NeuralNetworkScorerTest {
  @Test
  public void test() {
    User jonUser = Personas.convertToUser(Personas.getByEmail("panaceaa@gmail.com"));
    Set<FeatureId> userIndustryFeatureIds = NeuralNetworkScorer.getUserIndustryFeatureIds(jonUser);
    assertTrue(userIndustryFeatureIds.contains(FeatureId.SOFTWARE));
    assertFalse(userIndustryFeatureIds.contains(FeatureId.VETERINARY));
  }
}
