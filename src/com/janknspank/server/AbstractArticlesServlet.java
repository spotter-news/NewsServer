package com.janknspank.server;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;

import com.janknspank.bizness.BiznessException;
import com.janknspank.database.DatabaseRequestException;
import com.janknspank.database.DatabaseSchemaException;
import com.janknspank.proto.ArticleProto.Article;

public abstract class AbstractArticlesServlet extends StandardServlet {
  protected abstract Iterable<Article> getArticles(HttpServletRequest req)
      throws BiznessException, DatabaseSchemaException, DatabaseRequestException, RequestException;

  @Override
  protected JSONObject doGetInternal(HttpServletRequest req, HttpServletResponse resp)
      throws DatabaseSchemaException, DatabaseRequestException, RequestException, BiznessException {
    JSONObject response = createSuccessResponse();

    String contactsParameter = getParameter(req, "contacts");
    boolean includeLinkedInContacts = "linked_in".equals(contactsParameter);
    boolean includeAddressBookContacts = "address_book".equals(contactsParameter);
    response.put("articles", ArticleSerializer.serialize(
        getArticles(req), getUser(req), includeLinkedInContacts, includeAddressBookContacts));
    return response;
  }
}
