package com.janknspank.database;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.logging.Logger;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;

public class SqlConnection {
  private static final Logger LOG = Logger.getLogger(SqlConnection.class.getName());

  // JDBC driver name and database URL
  private static final String JDBC_DRIVER = "com.mysql.jdbc.Driver";
  private static final String DB_URL =
      "jdbc:mysql://newsserver.ceibyxjobuqr.us-west-2.rds.amazonaws.com:4406/newsserver?"
          + Joiner.on("&").join(ImmutableList.of(
              "rewriteBatchedStatements=true",
              "useUnicode=true",
              "characterEncoding=UTF-8",
              "characterSetResults=utf8",
              "connectionCollation=utf8_bin"));

  private static final String MYSQL_USER;
  private static final String MYSQL_PASSWORD;
  static {
    MYSQL_USER = System.getenv("MYSQL_USER");
    if (MYSQL_USER == null) {
      throw new Error("$MYSQL_USER is undefined");
    }
    MYSQL_PASSWORD = System.getenv("MYSQL_PASSWORD");
    if (MYSQL_PASSWORD == null) {
      throw new Error("$MYSQL_PASSWORD is undefined");
    }
    try {
      // Make sure the MySQL JDBC driver is loaded.
      Class.forName(JDBC_DRIVER);
    } catch (ClassNotFoundException e) {
      throw new Error(e);
    }
  }

  protected static Connection connection = null;

  static synchronized Connection getConnection() throws DatabaseSchemaException {
    if (connection == null) {
      LOG.info("Connecting to remote database...");
      try {
        connection = DriverManager.getConnection(DB_URL, MYSQL_USER, MYSQL_PASSWORD);
      } catch (SQLException e) {
        e.printStackTrace();
        throw new DatabaseSchemaException("Could not connect to database: " + e.getMessage(), e);
      }
      LOG.info("Connected to remote database successfully.");
    }
    return connection;
  }

  // Please don't call this.  It won't work when we switch to MongoDB.
  public static PreparedStatement xXprepareStatement(String sql) throws SQLException {
    return connection.prepareStatement(sql);
  }
}
