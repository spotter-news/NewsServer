package com.janknspank.server;

import static org.junit.Assert.assertEquals;

import java.io.FileReader;
import java.util.List;

import org.junit.Test;

import com.janknspank.database.Validator;
import com.janknspank.dom.parser.DocumentBuilder;
import com.janknspank.dom.parser.DocumentNode;
import com.janknspank.proto.UserProto.LinkedInProfile.Employer;

public class LoginServletTest {
  @SuppressWarnings("resource")
  @Test
  public void testGetEmployers() throws Exception {
    LoginServlet servlet = new LoginServlet();
    DocumentNode documentNode = DocumentBuilder.build(
        "https://api.linkedin.com/~me",
        new FileReader("testdata/linkedinprofile.xml"));
    List<Employer> employers = servlet.getEmployers(documentNode);
    assertEquals(6, employers.size());
    assertEquals("Spotter", employers.get(0).getName());
    assertEquals("Google", employers.get(1).getName());

    for (Employer employer : employers) {
      Validator.assertValid(employer);
    }
  }
}
