package com.janknspank.server;

import java.net.URISyntaxException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.codec.binary.Base64;
import org.apache.http.client.utils.URIBuilder;
import org.json.JSONObject;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.janknspank.bizness.Articles;
import com.janknspank.bizness.BiznessException;
import com.janknspank.bizness.LinkedInLoginHandler;
import com.janknspank.bizness.Sessions;
import com.janknspank.common.Asserts;
import com.janknspank.database.DatabaseRequestException;
import com.janknspank.database.DatabaseSchemaException;
import com.janknspank.database.Serializer;
import com.janknspank.fetch.FetchException;
import com.janknspank.fetch.Fetcher;
import com.janknspank.proto.ArticleProto.Article;
import com.janknspank.proto.CoreProto.Session;
import com.janknspank.proto.UserProto.User;

@ServletMapping(urlPattern = "/v1/linked_in_login")
public class LinkedInLoginServlet extends StandardServlet {
  private final Fetcher fetcher = new Fetcher();
  private static final String OAUTH_STATE_SALT = "this is my very fancy salt today";
  static final String LINKED_IN_API_KEY;
  static final String LINKED_IN_SECRET_KEY;
  static {
    LINKED_IN_API_KEY = System.getenv("LINKED_IN_API_KEY");
    if (LINKED_IN_API_KEY == null) {
      throw new Error("$LINKED_IN_API_KEY is undefined");
    }
    LINKED_IN_SECRET_KEY = System.getenv("LINKED_IN_SECRET_KEY");
    if (LINKED_IN_SECRET_KEY == null) {
      throw new Error("$LINKED_IN_SECRET_KEY is undefined");
    }
  }

  private String getRedirectUrl(HttpServletRequest req) {
    try {
      URIBuilder builder = new URIBuilder()
          .setScheme(req.getScheme())
          .setHost(req.getServerName())
          .setPath("/v1/linked_in_login");
      int port = req.getServerPort();
      if (!(port == 0 ||
            port == 80 && "http".equals(req.getScheme()) ||
            port == 443 && "https".equals(req.getScheme()))) {
        builder.setPort(port);
      }
      return builder.build().toString();
    } catch (URISyntaxException e) {
      throw new Error(e);
    }
  }

  /**
   * Exchanges an authorization code for a access token by bouncing it off
   * of LinkedIn's API.
   * @throws BiznessException 
   */
  private String getAccessTokenFromAuthorizationCode(
      HttpServletRequest req, String authorizationCode, String state) throws BiznessException {
    verifyLinkedInOAuthState(state);

    String response;
    try {
      String url = new URIBuilder()
          .setScheme("https")
          .setHost("www.linkedin.com")
          .setPath("/uas/oauth2/accessToken")
          .addParameter("grant_type", "authorization_code")
          .addParameter("code", authorizationCode)
          .addParameter("redirect_uri", getRedirectUrl(req))
          .addParameter("client_id", LinkedInLoginServlet.LINKED_IN_API_KEY)
          .addParameter("client_secret", LinkedInLoginServlet.LINKED_IN_SECRET_KEY)
          .build().toString();
      System.out.println("Fetching " + url);
      response = fetcher.getResponseBody(url);
    } catch (FetchException | URISyntaxException e) {
      throw new BiznessException("Could not fetch access token", e);
    }
    JSONObject responseObj = new JSONObject(response);
    return responseObj.getString("access_token");
  }

  @Override
  protected JSONObject doGetInternal(HttpServletRequest req, HttpServletResponse resp)
      throws BiznessException, RequestException, DatabaseSchemaException, DatabaseRequestException,
          RedirectException {
    String authorizationCode = getParameter(req, "code");
    String state = getParameter(req, "state");
    if (!Strings.isNullOrEmpty(authorizationCode) && !Strings.isNullOrEmpty(state)) {
      String accessToken = getAccessTokenFromAuthorizationCode(req, authorizationCode, state);
      return loginFromLinkedIn(accessToken);
    }

    try {
      throw new RedirectException(new URIBuilder()
          .setScheme("https")
          .setHost("www.linkedin.com")
          .setPath("/uas/oauth2/authorization")
          .addParameter("response_type", "code")
          .addParameter("client_id", LINKED_IN_API_KEY)
          .addParameter("scope", "r_basicprofile r_emailaddress")
          .addParameter("state", getLinkedInOAuthState())
          .addParameter("redirect_uri", getRedirectUrl(req))
          .build().toString());
    } catch (URISyntaxException e) {
      throw new DatabaseSchemaException("Error creating LinkedIn OAuth URL", e);
    }
  }

  protected JSONObject doPostInternal(HttpServletRequest req, HttpServletResponse resp)
      throws DatabaseSchemaException, DatabaseRequestException, NotFoundException, RequestException,
          BiznessException {
    return loginFromLinkedIn(getRequiredParameter(req, "linked_in_access_token"));
  }

  private JSONObject loginFromLinkedIn(String linkedInAccessToken)
      throws RequestException, DatabaseSchemaException, BiznessException, DatabaseRequestException {
    LinkedInLoginHandler linkedInLoginHandler = new LinkedInLoginHandler(linkedInAccessToken);
    User user = linkedInLoginHandler.getUser();
    Session session =
        Sessions.createFromLinkedProfile(linkedInLoginHandler.getLinkedInProfileDocument(), user);

    // Create the response.
    JSONObject response = this.createSuccessResponse();
    response.put("user", new UserHelper(user).getUserJson());
    response.put("session", Serializer.toJSON(session));

    // To help with client latency, return the articles for the user's home
    // screen in this response.
    Iterable<Article> articles = Articles.getMainStream(user);
    response.put("articles", ArticleSerializer.serialize(articles, user,
        false /* includeLinkedInContacts */,
        false /* includeAddressBookContacts */,
        null /* followedEntity */));

    return response;
  }

  /**
   * Returns a base64-encoded String of the current millisecond timestamp
   * concatenated with a salted SHA1 hash of the same timestamp, joined by a
   * forward slash (/).  Basic premise here is that folks won't know our salt,
   * so they won't be able to figure out how to make an OAuth state.
   */
  @VisibleForTesting
  static String getLinkedInOAuthState() {
    try {
      long millis = System.currentTimeMillis();
      MessageDigest md = MessageDigest.getInstance("SHA1");
      md.update((OAUTH_STATE_SALT + Long.toString(millis)).getBytes());
      String oAuthState = Long.toString(millis) + "/" + new String(md.digest());
      return Base64.encodeBase64URLSafeString(oAuthState.getBytes());
    } catch (NoSuchAlgorithmException e) {
      throw new Error("SHA1 hashing algorithm not found", e);
    }
  }

  /**
   * Verifies that the passed OAuth state's salted SHA1 section matches the
   * milliseconds at the front of the string.
   */
  static void verifyLinkedInOAuthState(String oAuthStateBase64) throws BiznessException {
    try {
      String oAuthState = new String(Base64.decodeBase64(oAuthStateBase64));
      String[] components = oAuthState.split("\\/", 2);
      MessageDigest md = MessageDigest.getInstance("SHA1");
      md.update((OAUTH_STATE_SALT + components[0]).getBytes());
      Asserts.assertTrue(components.length == 2 && components[1].equals(new String(md.digest())),
          "OAuthState does not match", BiznessException.class);
    } catch (NoSuchAlgorithmException e) {
      throw new Error("SHA1 hashing algorithm not found", e);
    }
  }
}
