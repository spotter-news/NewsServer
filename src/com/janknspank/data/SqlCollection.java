package com.janknspank.data;

import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.util.List;

import com.google.common.base.Joiner;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.ObjectArrays;
import com.google.protobuf.Descriptors.EnumValueDescriptor;
import com.google.protobuf.Descriptors.FieldDescriptor;
import com.google.protobuf.Message;
import com.janknspank.common.Asserts;
import com.janknspank.data.QueryOption.WhereEqualsIgnoreCase;
import com.janknspank.data.QueryOption.WhereNotEquals;
import com.janknspank.data.QueryOption.WhereNotLike;
import com.janknspank.data.QueryOption.WhereNotNull;
import com.janknspank.proto.Extensions;
import com.janknspank.proto.Extensions.Required;
import com.janknspank.proto.Extensions.StorageMethod;
import com.janknspank.proto.Extensions.StringCharset;
import com.janknspank.proto.Validator;
import com.mysql.jdbc.exceptions.jdbc4.MySQLSyntaxErrorException;

public class SqlCollection<T extends Message> extends Collection<T> {
  private static final String PROTO_COLUMN_NAME = "proto";
  private Connection __connectionCache; // DO NOT USE THIS DIRECTLY!!

  public SqlCollection(Class<T> clazz) {
    super(clazz);
  }

  protected Connection getConnection() throws DataInternalException {
    if (__connectionCache == null) {
      __connectionCache = SqlConnection.getConnection();
    }
    return __connectionCache;
  }

  @Override
  public void createTable() throws DataInternalException {
    try {
      getConnection().prepareStatement(getCreateTableSql()).execute();
      for (String statement : getCreateIndexesSql(clazz)) {
        getConnection().prepareStatement(statement).execute();
      }
    } catch (SQLException e) {
      throw new DataInternalException(
          "Could not create table " + clazz.getSimpleName() + ": " + e.getMessage(), e);
    }
    System.out.println("Table created: " + getTableName());
  }

  private String getSqlTypeForField(FieldDescriptor fieldDescriptor) {
    switch (fieldDescriptor.getJavaType()) {
      case STRING:
        int stringLength = fieldDescriptor.getOptions().getExtension(Extensions.stringLength);
        if (stringLength == -1) {
          throw new IllegalStateException("String length undefined for "
              + fieldDescriptor.getName());
        } else if (stringLength <= 0) {
          throw new IllegalStateException("Unsupported string length " + stringLength + " on "
              + fieldDescriptor.getName());
        } else if (stringLength > 65535) {
          throw new IllegalStateException("MySQL only allows strings up to is 65535 chars long");
        }
        StringCharset charset = fieldDescriptor.getOptions().getExtension(Extensions.stringCharset);
        String sqlType = (stringLength > 767)
            ? "BLOB"
            : "VARCHAR(" + stringLength + ")";
        if (charset == StringCharset.UTF8) {
          return sqlType + " CHARACTER SET utf8 COLLATE utf8_bin";
        } else {
          return sqlType + " CHARACTER SET latin1 COLLATE latin1_bin";
        }
      case LONG:
        return "BIGINT";
      case ENUM:
      case INT:
        return "INT";
      case BOOLEAN:
        return "BOOLEAN";
      case DOUBLE:
        return "DOUBLE";
      default:
        throw new IllegalStateException("Unsupported type: " + fieldDescriptor.getJavaType().name());
    }
  }

  /**
   * Given a protocol buffer message, returns the MySQL statement for creating
   * an appropriate table for storing it.
   */
  private String getCreateTableSql() {
    // Start creating the SQL statement.
    StringBuilder sql = new StringBuilder();
    sql.append("CREATE TABLE " + getTableName() + " (");

    // Add fields.
    for (FieldDescriptor field : storageMethodMap.keySet()) {
      StorageMethod storageMethod = storageMethodMap.get(field);
      if (storageMethod == StorageMethod.PRIMARY_KEY ||
          storageMethod == StorageMethod.INDEX ||
          storageMethod == StorageMethod.UNIQUE_INDEX ||
          storageMethod == StorageMethod.PULL_OUT) {
        sql.append(field.getName() + " " + getSqlTypeForField(field));
        if (storageMethod == StorageMethod.PRIMARY_KEY) {
          sql.append(" PRIMARY KEY");
        }
        if (Required.YES == field.getOptions().getExtension(Extensions.required)) {
          sql.append(" NOT NULL");
        }
        sql.append(", ");
      }
    }

    // Create an opaque blob field for storing the raw proto.  For consistency,
    // we do this even if all the fields are pulled out.
    sql.append(PROTO_COLUMN_NAME + " BLOB NOT NULL, ");

    // Also keep a timestamp, so we know when rows are updated (why not?).
    sql.append("timestamp TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP)");

    return sql.toString();
  }

  /**
   * Given a protocol buffer message, returns a List of MySQL statements for
   * creating indexes on the requested fields.
   */
  private List<String> getCreateIndexesSql(Class<T> clazz) {
    List<String> statements = Lists.newArrayList();
    for (FieldDescriptor field : storageMethodMap.keySet()) {
      StorageMethod storageMethod = storageMethodMap.get(field);
      if (storageMethod == StorageMethod.INDEX ||
          storageMethod == StorageMethod.UNIQUE_INDEX) {
        StringBuilder sql = new StringBuilder();
        String fieldName = field.getName();
        sql.append("CREATE ");
        if (storageMethod == StorageMethod.UNIQUE_INDEX) {
          sql.append("UNIQUE ");
        }
        sql.append("INDEX " + fieldName + "_index ON " + getTableName());
        sql.append(" (" + fieldName + ") USING HASH");
        statements.add(sql.toString());
      }
    }
    return statements;
  }

  /**
   * Prepares an INSERT or UPDATE SQL statement by setting the variable fields
   * with the relevant values from the passed proto.  Any primary key, index,
   * or pull-out column is updates in the order it exists in the proto
   * definition.
   */
  private void prepareInsertOrUpdateStatement(PreparedStatement statement, Message message)
      throws SQLException {
    int offset = 0;
    for (FieldDescriptor field : storageMethodMap.keySet()) {
      StorageMethod storageMethod = storageMethodMap.get(field);
      if (storageMethod == StorageMethod.PRIMARY_KEY ||
          storageMethod == StorageMethod.INDEX ||
          storageMethod == StorageMethod.UNIQUE_INDEX ||
          storageMethod == StorageMethod.PULL_OUT) {
        ++offset;
        if (!message.hasField(field)) {
          statement.setNull(offset, Types.VARCHAR);
        } else {
          switch (field.getJavaType()) {
            case STRING:
              statement.setString(offset, (String) message.getField(field));
              break;
            case INT:
              statement.setInt(offset, (int) message.getField(field));
              break;
            case LONG:
              statement.setLong(offset, (long) message.getField(field));
              break;
            case ENUM:
              EnumValueDescriptor v = (EnumValueDescriptor) message.getField(field);
              statement.setInt(offset, v.getNumber());
              break;
            case BOOLEAN:
              statement.setBoolean(offset, (boolean) message.getField(field));
              break;
            case DOUBLE:
              statement.setDouble(offset, (double) message.getField(field));
              break;
            default:
              throw new IllegalStateException("Unsupported type: " + field.getJavaType().name());
          }
        }
      }
    }

    statement.setBytes(offset + 1, cleanDoNotStoreFields(message).toByteArray());
  }

  /**
   * Returns an INSERT INTO statement for inserting the given protocol buffer
   * message into its respective MySQL table.
   */
  private PreparedStatement getRawInsertStatement(T message)
      throws DataInternalException, SQLException {
    // Start creating the SQL statement.
    StringBuilder sql = new StringBuilder();
    sql.append("INSERT INTO " + getTableName() + " (");

    // Add fields.
    int pulledOutFieldCount = 0;
    for (FieldDescriptor field : storageMethodMap.keySet()) {
      StorageMethod storageMethod = storageMethodMap.get(field);
      if (storageMethod == StorageMethod.PRIMARY_KEY ||
          storageMethod == StorageMethod.INDEX ||
          storageMethod == StorageMethod.UNIQUE_INDEX ||
          storageMethod == StorageMethod.PULL_OUT) {
        ++pulledOutFieldCount;
        sql.append(field.getName() + ", ");
      }
    }
    sql.append(PROTO_COLUMN_NAME + ") VALUES (");

    // Add prepared statement fill-in marks (whatever these are called).
    // Add an extra one for the proto serialization.
    for (int i = 0; i < pulledOutFieldCount + 1; i++) {
      sql.append("?");
      if (i != pulledOutFieldCount) {
        sql.append(", ");
      }
    }
    sql.append(")");

    System.out.println(sql.toString());

    // Prepare the statement!
    return getConnection().prepareStatement(sql.toString());
  }

  /**
   * Inserts the passed messages into the database.  All messages passed must be
   * of the same type.
   */
  @Override
  public int insert(Iterable<T> messages)
      throws ValidationException, DataInternalException {
    if (Iterables.isEmpty(messages)) {
      return 0;
    }

    T firstMessage = Iterables.getFirst(messages, null);
    PreparedStatement statement = null;
    try {
      statement = getRawInsertStatement(firstMessage);
      for (T message : messages) {
        Validator.assertValid(message);
        Asserts.assertTrue(firstMessage.getClass().equals(message.getClass()),
            "Types do not match");
        prepareInsertOrUpdateStatement(statement, message);
        statement.addBatch();
      }
      return sumIntArray(statement.executeBatch());

    } catch (SQLException e) {
      throw new DataInternalException(
          "Could not insert " + getTableName() + ": " + e.getMessage(), e);
    } finally {
      if (statement != null) {
        try {
          statement.close();
        } catch (SQLException e) {}
      }
    }
  }

  /**
   * Returns the number of columns in the table this passed proto message
   * class is stored in.
   */
  private int getColumnCount() {
    // Start with 2, since there's always 'proto' and 'timestamp', regardless
    // of the schema.
    int columnCount = 2;

    for (FieldDescriptor field : storageMethodMap.keySet()) {
      StorageMethod storageMethod = storageMethodMap.get(field);
      if (storageMethod == StorageMethod.PRIMARY_KEY ||
          storageMethod == StorageMethod.INDEX ||
          storageMethod == StorageMethod.UNIQUE_INDEX ||
          storageMethod == StorageMethod.PULL_OUT) {
        ++columnCount;
      }
    }
    return columnCount;
  }

  /**
   * Returns an UPDATE statement for updating the given protocol buffer message
   * in its respective MySQL table.
   */
  private PreparedStatement getRawUpdateStatement(QueryOption.WhereOption... whereOptions)
      throws DataInternalException, SQLException {
    // Start creating the SQL statement.
    StringBuilder sql = new StringBuilder();
    sql.append("UPDATE " + getTableName() + " SET ");

    // Add fields.
    for (FieldDescriptor field : storageMethodMap.keySet()) {
      StorageMethod storageMethod = storageMethodMap.get(field);
      if (storageMethod == StorageMethod.PRIMARY_KEY ||
          storageMethod == StorageMethod.INDEX ||
          storageMethod == StorageMethod.UNIQUE_INDEX ||
          storageMethod == StorageMethod.PULL_OUT) {
        sql.append(field.getName() + "=?, ");
      }
    }
    sql.append(PROTO_COLUMN_NAME + "=?");
    sql.append(getWhereClauseSql(whereOptions));

    System.out.println(sql.toString());

    return getConnection().prepareStatement(sql.toString());
  }

  /**
   * Updates the passed messages, overwriting whatever was stored in the
   * database before.  Returns the number of modified objects.
   */
  @Override
  public int update(
      Iterable<T> messages, QueryOption.WhereOption... whereOptions)
      throws ValidationException, DataInternalException {
    if (Iterables.isEmpty(messages)) {
      return 0;
    }

    T firstMessage = Iterables.getFirst(messages, null);
    for (QueryOption.WhereOption whereOption : whereOptions) {
      if (primaryKeyField != null && primaryKeyField.equals(whereOption.getFieldName())) {
        throw new IllegalStateException("Update WhereEquals options cannot put "
            + "additional constraints on the primary key");
      }
    }
    whereOptions = ObjectArrays.concat(
        new QueryOption.WhereEquals(primaryKeyField, Database.getPrimaryKey(firstMessage)),
        whereOptions);

    int columnCount = getColumnCount();
    PreparedStatement statement = null;
    try {
      statement = getRawUpdateStatement(whereOptions);
      for (T message : messages) {
        Validator.assertValid(message);
        Asserts.assertTrue(firstMessage.getClass().equals(message.getClass()),
            "Types do not match");
        whereOptions[0] = new QueryOption.WhereEquals(primaryKeyField,
            Database.getPrimaryKey(message));
        prepareInsertOrUpdateStatement(statement, message);
        int i = 0;
        for (String whereValue : getWhereValues(whereOptions)) {
          statement.setString(columnCount + (i++), whereValue);
        }
        statement.addBatch();
      }
      return SqlCollection.sumIntArray(statement.executeBatch());

    } catch (SQLException e) {
      throw new DataInternalException(
          "Could not insert " + getTableName() + ": " + e.getMessage(), e);
    } finally {
      try {
        statement.close();
      } catch (SQLException e) {}
    }
  }

  private String getLimitSql(QueryOption[] options) {
    List<QueryOption.Limit> queryOptionList = QueryOption.getList(options, QueryOption.Limit.class);
    if (queryOptionList.size() > 1) {
      throw new IllegalStateException("Duplicate definitions of QueryOption.Limit not allowed");
    }
    if (queryOptionList.isEmpty()) {
      return "";
    }
    QueryOption.Limit limitOption = (QueryOption.Limit) queryOptionList.get(0);
    StringBuilder sql = new StringBuilder();
    sql.append(" LIMIT ");
    if (limitOption instanceof QueryOption.LimitWithOffset) {
      sql.append(((QueryOption.LimitWithOffset) limitOption).getOffset()).append(", ");
    }
    sql.append(limitOption.getLimit());
    return sql.toString();
  }

  private String getWhereClauseSql(QueryOption[] options) {
    StringBuilder sql = new StringBuilder();
    for (QueryOption.WhereEquals whereEquals :
        QueryOption.getList(options, QueryOption.WhereEquals.class)) {
      int size = Iterables.size(whereEquals.getValues());
      if (size == 0) {
        if (whereEquals instanceof WhereNotEquals) {
          // OK, don't write anything - Everything doesn't equal nothing.
          continue;
        }
        throw new IllegalStateException("Where clause contains no values - "
            + "This should have been caught earlier.");
      }
      if (whereEquals instanceof WhereEqualsIgnoreCase) {
        sql.append(sql.length() == 0 ? " WHERE " : " AND ")
            .append("LOWER(").append(whereEquals.getFieldName()).append(")")
            .append(" IN (")
            .append(Joiner.on(",").join(Iterables.limit(Iterables.cycle("?"), size)))
            .append(")");
      } else {
        sql.append(sql.length() == 0 ? " WHERE " : " AND ")
            .append(whereEquals.getFieldName())
            .append(whereEquals instanceof WhereNotEquals ? " NOT" : "")
            .append(" IN (")
            .append(Joiner.on(",").join(Iterables.limit(Iterables.cycle("?"), size)))
            .append(")");
      }
    }
    for (QueryOption.WhereLike whereLike :
        QueryOption.getList(options, QueryOption.WhereLike.class)) {
      sql.append(sql.length() == 0 ? " WHERE " : " AND ")
          .append(whereLike.getFieldName())
          .append(whereLike instanceof WhereNotLike ? " NOT" : "")
          .append(" LIKE ?");
    }
    for (QueryOption.WhereNull whereNull :
        QueryOption.getList(options, QueryOption.WhereNull.class)) {
      sql.append(sql.length() == 0 ? " WHERE " : " AND ")
          .append(whereNull.getFieldName())
          .append(" IS")
          .append(whereNull instanceof WhereNotNull ? " NOT" : "")
          .append(" NULL");
    }
    return sql.toString();
  }

  private Iterable<String> getWhereValues(QueryOption[] options) {
    List<String> values = Lists.newArrayList();
    for (QueryOption.WhereEquals whereEquals :
        QueryOption.getList(options, QueryOption.WhereEquals.class)) {
      if (whereEquals instanceof WhereEqualsIgnoreCase) {
        for (String value : whereEquals.getValues()) {
          values.add(value.toLowerCase());
        }
      } else {
        Iterables.addAll(values, whereEquals.getValues());
      }
    }
    for (QueryOption.WhereLike whereLike :
      QueryOption.getList(options, QueryOption.WhereLike.class)) {
      values.add(whereLike.getValue());
    }
    return values;
  }

  private String getOrderBySql(QueryOption[] options) {
    StringBuilder sb = new StringBuilder();
    for (QueryOption.Sort sort : QueryOption.getList(options, QueryOption.Sort.class)) {
      sb.append((sb.length() == 0) ? " ORDER BY " : ", ");
      sb.append(sort.getFieldName());
      if (sort instanceof QueryOption.DescendingSort) {
        sb.append(" DESC");
      }
    }
    return sb.toString();
  }

  /**
   * Gets Messages with the specified class {@code clazz} and the field values,
   * if they exist.
   */
  public List<T> get(QueryOption... options)
      throws DataInternalException {
    if (QueryOption.isWhereClauseEmpty(options)) {
      return ImmutableList.of();
    }

    StringBuilder sql = new StringBuilder();
    sql.append("SELECT * FROM " + getTableName());
    sql.append(getWhereClauseSql(options));
    sql.append(getOrderBySql(options));
    sql.append(getLimitSql(options));

    PreparedStatement statement = null;
    try {
      statement = getConnection().prepareStatement(sql.toString());
      int i = 0;
      for (String key : getWhereValues(options)) {
        statement.setString(++i, key);
      }
      return createListFromResultSet(statement.executeQuery());
    } catch (MySQLSyntaxErrorException e) {
      throw new DataInternalException("Invalid query: " + sql, e);
    } catch (SQLException e) {
      throw new DataInternalException("Could not execute get: " + e.getMessage()
          + ": " + e.getMessage(), e);
    } finally {
      if (statement != null) {
        try {
          statement.close();
        } catch (SQLException e) {}
      }
    }
  }

  /**
   * Deletes objects from the specified object {@code clazz} that match the
   * given query options.
   */
  @Override
  public int delete(QueryOption... options)
      throws DataInternalException {
    StringBuilder sql = new StringBuilder();
    sql.append("DELETE FROM " + getTableName());
    sql.append(getWhereClauseSql(options));
    sql.append(getOrderBySql(options));
    sql.append(getLimitSql(options));
    
    PreparedStatement statement = null;
    try {
      statement = getConnection().prepareStatement(sql.toString());
      int i = 0;
      for (String key : getWhereValues(options)) {
        statement.setString(++i, key);
      }
      return statement.executeUpdate();
    } catch (SQLException e) {
      throw new DataInternalException("Could not execute get: " + e.getMessage()
          + ": " + e.getMessage(), e);
    } finally {
      if (statement != null) {
        try {
          statement.close();
        } catch (SQLException e) {}
      }
    }
  }

  /**
   * Returns the sum of all the integers in the passed array.  Useful for
   * collating # of rows modified by batch statements.
   */
  public static int sumIntArray(int[] array) {
    int sum = 0;
    for (int i = 0; i < array.length; i++) {
      sum += array[i];
    }
    return sum;
  }

  /**
   * Returns the MySQL table name that this collection should be stored in.
   */
  public String getTableName() {
    return clazz.getSimpleName();
  }

  /**
   * Through reflection, returns a protocol buffer message of the type specified
   * in {@code clazz} using the passed MySQL result set.
   */
  public T createFromResultSet(ResultSet result)
      throws SQLException, DataInternalException {
    if (result.next()) {
      try {
        Method parseFromMethod = clazz.getMethod("parseFrom", InputStream.class);

        @SuppressWarnings("unchecked")
        T message = (T) parseFromMethod.invoke(null,
            result.getBlob(PROTO_COLUMN_NAME).getBinaryStream());

        Validator.assertValid(message);
        return message;
      } catch (ValidationException|NoSuchMethodException|IllegalAccessException
          |IllegalArgumentException|InvocationTargetException e) {
        throw new DataInternalException(
            "Could not create " + clazz.getSimpleName() + " object: " + e.getMessage(), e);
      }
    }
    return null;
  }

  public List<T> createListFromResultSet(ResultSet result) throws DataInternalException {
    List<T> messages = Lists.newArrayList();
    try {
      while (!result.isAfterLast()) {
        T message = createFromResultSet(result);
        if (message == null) {
          break;
        }
        messages.add(message);
      }
    } catch (SQLException e) {
      throw new DataInternalException("Error fetching " + clazz.getSimpleName() + " list: "
          + e.getMessage(), e);
    }
    return messages;
  }
}
