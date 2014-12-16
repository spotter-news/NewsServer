package com.janknspank.server;

import java.io.IOException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.json.JSONObject;

import com.janknspank.data.DataInternalException;
import com.janknspank.data.DataRequestException;
import com.janknspank.data.Session;
import com.janknspank.data.User;

public class LoginServlet extends NewsServlet {
  @Override
  protected void doGet(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    writeSoyTemplate(resp, ".main", null);
  }

  @Override
  protected void doPost(HttpServletRequest req, HttpServletResponse resp)
      throws ServletException, IOException {
    // Read parameters.
    String email = getParameter(req, "email");
    String password = getParameter(req, "password");

    // TODO(jonemerson): Add parameter validation.

    // Business logic.
    Session session;
    User user;
    try {
      session = Session.create(email, password);
      user = User.get(email);
    } catch (DataRequestException|DataInternalException e) {
      resp.setStatus(e instanceof DataInternalException ?
          HttpServletResponse.SC_INTERNAL_SERVER_ERROR : HttpServletResponse.SC_BAD_REQUEST);
      writeJson(resp, getErrorJson(e.getMessage()));
      return;
    }

    // Write response.
    JSONObject response = new JSONObject();
    response.put("success", true);
    response.put("session", session.toJSONObject());
    response.put("user", user.toJSONObject());
    writeJson(resp, response);
  }
}