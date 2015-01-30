package com.janknspank.common;

import java.lang.reflect.InvocationTargetException;

import com.google.api.client.repackaged.com.google.common.base.Strings;

public class Asserts {
  public static <T extends Object> T assertNotNull(T o, String desc) throws AssertionException {
    return assertNotNull(o, desc, AssertionException.class);
  }

  public static <T extends Object, X extends Exception> T assertNotNull(
      T o, String desc, Class<X> clazz) throws X {
    if (o == null) {
      throwException("Object is null: " + desc, clazz);
    }
    return o;
  }

  public static void assertTrue(boolean b, String desc) throws AssertionException {
    assertTrue(b, desc, AssertionException.class);
  }

  public static <X extends Exception> void assertTrue(boolean b, String desc, Class<X> clazz)
      throws X {
    if (!b) {
      throwException("Condition is false: " + desc, clazz);
    }
  }

  public static String assertNonEmpty(String s, String desc) throws AssertionException {
    return assertNonEmpty(s, desc, AssertionException.class);
  }

  public static <X extends Exception> String assertNonEmpty(String s, String desc, Class<X> clazz)
      throws X {
    assertNotNull(s, desc, clazz);
    if (Strings.isNullOrEmpty(s)) {
      throwException("String is empty: " + desc, clazz);
    }
    return s;
  }

  private static <X extends Exception> void throwException(String message, Class<X> clazz)
      throws X {
    try {
      X x = clazz.getConstructor(String.class).newInstance(message);
      x.printStackTrace(); // TODO(jonemerson): Probably remove this some day.
      throw x;
    } catch (InstantiationException | IllegalAccessException
        | IllegalArgumentException | InvocationTargetException
        | NoSuchMethodException | SecurityException e) {
      throw new RuntimeException("Exception class " + clazz.getSimpleName()
          + " should implement (String message) constructor");
    }
  }
}
