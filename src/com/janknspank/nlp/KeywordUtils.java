package com.janknspank.nlp;

import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.text.WordUtils;

import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Sets;
import com.janknspank.database.Database;
import com.janknspank.proto.ArticleProto.ArticleKeyword;

public class KeywordUtils {
  public static final int MAX_KEYWORD_LENGTH =
      Database.getStringLength(ArticleKeyword.class, "keyword");

  // Matches: "5", "5) Topic.", "5. Example".
  private static final Pattern NUMBER_PATTERN = Pattern.compile("[0-9]+([\\.\\)\\,\\-].*)?");
  private static final Pattern BEST_OF_PATTERN = Pattern.compile("^best( [a-z]+)? of ");
  private static final Pattern ABBREVIATION_PATTERN = Pattern.compile("([A-Z]+\\.){2,10}");
  private static final Set<String> BLACKLIST = Sets.newHashSet();
  static {
    for (String keyword : new String[] {
        "and",
        "apartments",
        "blog",
        "business school",
        "business schools",
        "bw business schools page",
        "capitals",
        "ceo",
        "cinema",
        "committee",
        "commodities",
        "company",
        "corrections",
        "crime",
        "department",
        "dialogue",
        "economy",
        "economic",
        "education",
        "films",
        "financial calculator",
        "financial calculators",
        "first",
        "graduate reviews",
        "hedge funds",
        "hello",
        "is",
        "internet",
        "inequality",
        "insurance",
        "jobs",
        "kids",
        "kidz",
        "leadership",
        "lifestyle",
        "litigation",
        "markets",
        "media",
        "mobile",
        "movies",
        "my",
        "news",
        "opinion",
        "party",
        "pm",
        "profile",
        "restaurants",
        "retail",
        "retailing",
        "shopping",
        "show",
        "society",
        "son",
        "sports",
        "stocks",
        "stock market",
        "story",
        "story-video",
        "teams",
        "tech",
        "technology",
        "that",
        "the",
        "their",
        "there",
        "they",
        "times",
        "top business schools worldwide",
        "trial",
        "trials",
        "try",
        "university",
        "up",
        "video",
        "web",
        "why",
        "with",
        "yet"}) {
      BLACKLIST.add(keyword.toLowerCase());
    }
  }
  private static final ImmutableMap<Pattern, String> CAPITALIZATION_FIX_PATTERNS =
      ImmutableMap.<Pattern, String>builder()
          .put(Pattern.compile("Aol", Pattern.CASE_INSENSITIVE), "AOL")
          .put(Pattern.compile("Ios", Pattern.CASE_INSENSITIVE), "iOS")
          .put(Pattern.compile("Ipad", Pattern.CASE_INSENSITIVE), "iPad")
          .put(Pattern.compile("Iphone", Pattern.CASE_INSENSITIVE), "iPhone")
          .put(Pattern.compile("Iwatch", Pattern.CASE_INSENSITIVE), "iWatch")
          .put(Pattern.compile("Ipod", Pattern.CASE_INSENSITIVE), "iPod")
          .build();

  public static boolean isValidKeyword(String keyword) {
    keyword = keyword.trim();
    String lowercaseKeyword = keyword.toLowerCase();
    if (lowercaseKeyword.length() < 2 ||
        !Character.isAlphabetic(keyword.charAt(0)) ||
        NUMBER_PATTERN.matcher(keyword).matches() ||
        BEST_OF_PATTERN.matcher(lowercaseKeyword).find() ||
        lowercaseKeyword.contains("…") ||
        lowercaseKeyword.startsWith("#") ||
        lowercaseKeyword.startsWith("@") ||
        lowercaseKeyword.startsWith("bloomberg ") ||
        lowercaseKeyword.startsWith("mba ") ||
        lowercaseKeyword.endsWith("@bloomberg") ||
        lowercaseKeyword.endsWith(" jobs") ||
        lowercaseKeyword.endsWith(" news") ||
        lowercaseKeyword.endsWith(" profile") ||
        lowercaseKeyword.endsWith(" restaurants") ||
        lowercaseKeyword.endsWith(" trends") ||
        (lowercaseKeyword.contains("&") && lowercaseKeyword.contains(";")) || // XML entities.
        lowercaseKeyword.length() > MAX_KEYWORD_LENGTH) {
      return false;
    }
    return !BLACKLIST.contains(lowercaseKeyword);
  }

  /**
   * Removes cruft/dirt from keywords.  Doesn't attempt to do anything more.
   */
  public static String scrubKeyword(String keyword) {
    keyword = keyword.trim();
    while (keyword.startsWith("‘") || keyword.startsWith("'") ||
        keyword.startsWith("“") || keyword.startsWith("\"") ||
        keyword.startsWith("(") || keyword.startsWith(")") ||
        keyword.startsWith("-") || keyword.startsWith(".") ||
        keyword.startsWith("?") || keyword.startsWith(":") ||
        keyword.startsWith("&") || keyword.startsWith(",") ||
        keyword.startsWith("!") || keyword.startsWith("*") ||
        keyword.startsWith("+") || keyword.startsWith("/") ||
        keyword.startsWith("-") || keyword.startsWith("—") ||
        keyword.startsWith("%") || keyword.startsWith("—") ||
        keyword.startsWith("…") || keyword.startsWith("[")) {
      keyword = keyword.substring(1).trim();
    }
    if (keyword.endsWith("’s") || keyword.endsWith("'s")) {
      return keyword.substring(0, keyword.length() - "’s".length()).trim();
    }
    while (keyword.endsWith("’") || keyword.endsWith("'") ||
        keyword.endsWith("”") || keyword.endsWith("\"") ||
        keyword.endsWith(",") || keyword.endsWith(";") ||
        keyword.endsWith("-") || keyword.endsWith("!")||
        keyword.endsWith("?") || keyword.endsWith("*")||
        keyword.endsWith(")") || keyword.endsWith("/") ||
        keyword.endsWith("$") || keyword.endsWith("—") ||
        keyword.endsWith("″") || keyword.endsWith("]")) {
      keyword = keyword.substring(0, keyword.length() - 1).trim();
    }
    if (keyword.endsWith(".") && StringUtils.countMatches(keyword, ".") == 1) {
      keyword = keyword.substring(0, keyword.length() - 1).trim();
    }
    if (keyword.endsWith("-based")) {
      keyword = keyword.substring(0, keyword.length() - "-based".length()).trim();
    }
    return keyword;
  }

  /**
   * Removes possessives and other dirt from strings our parser found (since we
   * trained our parser to include them, so instead of ignoring them as false
   * negatives).
   */
  public static String cleanKeyword(String keyword) {
    keyword = scrubKeyword(keyword);

    // Since we want to canonicalize as much as we can, and have nice short
    // keywords, for now convert possessive objects to only their posessive
    // part, dropping the rest.  This does mean we lose significant information
    // about the keyword, but perhaps(?) the canonicalization is worth it.
    if (keyword.contains("’")) {
      keyword = keyword.substring(0, keyword.indexOf("’")).trim();
    }
    if (keyword.contains("'")) {
      keyword = keyword.substring(0, keyword.indexOf("'")).trim();
    }

    if (ABBREVIATION_PATTERN.matcher(keyword).matches()) {
      // Get rid of the dots.
      StringBuilder sb = new StringBuilder();
      for (int i = 0; i < keyword.length(); i += 2) {
        sb.append(keyword.charAt(i));
      }
      keyword = sb.toString();
    } else if (keyword.length() > 0) {
      if (!Character.isUpperCase(keyword.charAt(0))) {
        keyword = WordUtils.capitalizeFully(keyword);
      }
      for (Map.Entry<Pattern, String> capitalizationFixEntry
          : CAPITALIZATION_FIX_PATTERNS.entrySet()) {
        keyword = capitalizationFixEntry.getKey().matcher(keyword).replaceAll(
            capitalizationFixEntry.getValue());
      }
    } else if (keyword.length() > 4 && keyword.equals(keyword.toUpperCase())) {
      // For non-abbreviations, don't let folks capitalize everything.
      keyword = WordUtils.capitalizeFully(keyword.toLowerCase());
    }

    return keyword;
  }
}
