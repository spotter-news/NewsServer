package com.janknspank.data;

import java.util.List;

import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;

public class QueryOption {
  public static class Limit extends QueryOption {
    private final int limit;

    public Limit(int limit) {
      Preconditions.checkArgument(limit > 0);
      this.limit = limit;
    }
    
    public int getLimit() {
      return limit;
    }
  }

  public final static class LimitWithOffset extends Limit {
    private final int offset;

    public LimitWithOffset(int limit, int offset) {
      super(limit);
      this.offset = offset;
    }
    
    public int getOffset() {
      return offset;
    }
  }

  public abstract static class WhereOption extends QueryOption {
    private final String fieldName;
    
    private WhereOption(String fieldName) {
      this.fieldName = fieldName;
    }
    
    public String getFieldName() {
      return fieldName;
    }
  }

  public static class WhereLike extends WhereOption {
    private final String value;

    public WhereLike(String fieldName, String value) {
      super(fieldName);
      this.value = value;
    }

    public String getValue() {
      return value;
    }
  }

  public static class WhereNotLike extends WhereLike {
    public WhereNotLike(String fieldName, String value) {
      super(fieldName, value);
    }
  }

  public static class WhereEquals extends WhereOption {
    private final Iterable<String> values;

    public WhereEquals(String fieldName, String value) {
      this(fieldName, ImmutableList.of(value));
    }

    public WhereEquals(String fieldName, Iterable<String> values) {
      super(fieldName);
      this.values = values;
    }

    public Iterable<String> getValues() {
      return values;
    }
  }

  public final static class WhereNotEquals extends WhereEquals {
    public WhereNotEquals(String fieldName, String value) {
      super(fieldName, value);
    }
    
    public WhereNotEquals(String fieldName, Iterable<String> values) {
      super(fieldName, values);
    }
  }

  public static class WhereNull extends WhereOption {
    public WhereNull(String fieldName) {
      super(fieldName);
    }
  }
  
  public static class WhereNotNull extends WhereNull {
    public WhereNotNull(String fieldName) {
      super(fieldName);
    }
  }
  
  static class Sort extends QueryOption {
    private final String fieldName;

    Sort(String fieldName) {
      super();
      this.fieldName = fieldName;
    }
    
    public String getFieldName() {
      return fieldName;
    }
  }

  public final static class AscendingSort extends Sort {
    public AscendingSort(String fieldName) {
      super(fieldName);
    }
  }

  public final static class DescendingSort extends Sort {
    public DescendingSort(String fieldName) {
      super(fieldName);
    }
  }

  @SuppressWarnings("unchecked")
  static final <X extends QueryOption> List<X> getList(
      QueryOption[] options, Class<X> queryOption) {
    List<X> queryOptions = Lists.newArrayList();
    for (QueryOption option : options) {
      if (queryOption.isInstance(option)) {
        queryOptions.add((X) option);
      }
    }
    return queryOptions;
  }
}