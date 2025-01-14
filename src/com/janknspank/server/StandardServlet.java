package com.janknspank.server;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;

import com.google.template.soy.data.SoyMapData;
import com.janknspank.bizness.BiznessException;
import com.janknspank.common.Asserts;
import com.janknspank.database.DatabaseRequestException;
import com.janknspank.database.DatabaseSchemaException;

@AuthenticationRequired(requestMethod = "POST")
public abstract class StandardServlet extends NewsServlet {
  protected JSONObject doGetInternal(HttpServletRequest req, HttpServletResponse resp)
      throws DatabaseSchemaException, DatabaseRequestException, NotFoundException,
          RedirectException, BiznessException, RequestException {
    return null;
  }

  /**
   * Returns any Soy data necessary for rendering the .main template for this
   * servlet's Soy page.
   */
  protected SoyMapData getSoyMapData(HttpServletRequest req)
      throws DatabaseSchemaException, DatabaseRequestException, NotFoundException,
          RedirectException, BiznessException, RequestException {
    return null;
  }

  @Override
  protected final void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    try {
      JSONObject response = doGetInternal(req, resp);
      if (response == null) {
        resp.setHeader("Content-Type", "text/html; charset=utf-8");
        writeSoyTemplate(resp, ".main", getSoyMapData(req));
      } else {
        Asserts.assertTrue(response.getBoolean("success"), "success in response",
            BiznessException.class);
        writeJson(req, resp, response);
      }
    } catch (RedirectException e) {
      resp.setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
      resp.setHeader("Location", e.getNextUrl());
    } catch (NotFoundException e) {
      resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
      writeJson(req, resp, getErrorJson(e.getMessage()));
    } catch (DatabaseSchemaException | DatabaseRequestException | BiznessException e) {
      resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      writeJson(req, resp, getErrorJson(e.getMessage()));
    } catch (AuthenticationRequiredException e) {
      handleAuthenticationError(req, resp, "User not found");
    } catch (RequestException e) {
      resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      writeJson(req, resp, getErrorJson(e.getMessage()));
    }
  }

  protected JSONObject doPostInternal(HttpServletRequest req, HttpServletResponse resp)
      throws DatabaseSchemaException, DatabaseRequestException, RequestException,
          NotFoundException, BiznessException, RedirectException {
    throw new UnsupportedOperationException();
  }

  @Override
  protected final void doPost(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    Throwable ex = null;
    try {
      JSONObject response = doPostInternal(req, resp);
      Asserts.assertTrue(response.getBoolean("success"), "success in response",
          BiznessException.class);
      writeJson(req, resp, response);
    } catch (RedirectException e) {
      resp.setStatus(HttpServletResponse.SC_MOVED_TEMPORARILY);
      resp.setHeader("Location", e.getNextUrl());
    } catch (UnsupportedOperationException e) {
      ex = e;
      resp.setStatus(HttpServletResponse.SC_NOT_IMPLEMENTED);
      writeJson(req, resp, getErrorJson(e.getMessage()));
    } catch (NotFoundException e) {
      ex = e;
      resp.setStatus(HttpServletResponse.SC_NOT_FOUND);
      writeJson(req, resp, getErrorJson(e.getMessage()));
    } catch (DatabaseSchemaException | DatabaseRequestException | BiznessException e) {
      ex = e;
      resp.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
      writeJson(req, resp, getErrorJson(e.getMessage()));
    } catch (AuthenticationRequiredException e) {
      handleAuthenticationError(req, resp, "User not found");
    } catch (RequestException e) {
      ex = e;
      resp.setStatus(HttpServletResponse.SC_BAD_REQUEST);
      writeJson(req, resp, getErrorJson(e.getMessage()));
    } catch (Throwable e) {
      ex = e;
      throw e;
    } finally {
      if (ex != null) {
        ex.printStackTrace();
      }
    }
  }
}
