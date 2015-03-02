package com.janknspank.server;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;

import com.google.common.primitives.Doubles;
import com.janknspank.bizness.UrlRatings;
import com.janknspank.bizness.Urls;
import com.janknspank.database.Database;
import com.janknspank.database.DatabaseRequestException;
import com.janknspank.database.DatabaseSchemaException;
import com.janknspank.database.Serializer;
import com.janknspank.proto.CoreProto.Url;
import com.janknspank.proto.UserProto.User;

@AuthenticationRequired(requestMethod = "POST")
public class SetUserUrlRatingServlet extends StandardServlet {
  @Override
  protected JSONObject doPostInternal(HttpServletRequest req, HttpServletResponse resp)
      throws RequestException, DatabaseSchemaException, DatabaseRequestException {
    // Read parameters.
    String urlId = getRequiredParameter(req, "url_id");
    User user = Database.with(User.class).get(getSession(req).getUserId());

    // Parameter validation.
    Url articleUrl = Urls.getById(urlId);
    if (articleUrl == null) {
      throw new RequestException("URL does not exist");
    }
    Double ratingScore = Doubles.tryParse(getParameter(req, "rating"));
    if (ratingScore == null || ratingScore < 0 || ratingScore > 1) {
      throw new RequestException("rating must be between 0 and 1, inclusive");
    }

    // Business logic.
    UrlRatings.upsertRating(articleUrl.getUrl(), ratingScore, user);

    // Create response.
    JSONObject response = createSuccessResponse();
    response.put("user", Serializer.toJSON(user));
    return response;
  }
}
