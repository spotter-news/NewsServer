package com.janknspank;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.common.base.Function;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.common.collect.Sets;
import com.janknspank.bizness.BiznessException;
import com.janknspank.bizness.Links;
import com.janknspank.bizness.Urls;
import com.janknspank.common.ArticleUrlDetector;
import com.janknspank.common.Asserts;
import com.janknspank.common.UrlCleaner;
import com.janknspank.common.UrlWhitelist;
import com.janknspank.database.Database;
import com.janknspank.database.DatabaseRequestException;
import com.janknspank.database.DatabaseSchemaException;
import com.janknspank.database.QueryOption;
import com.janknspank.dom.parser.ParserException;
import com.janknspank.fetch.FetchException;
import com.janknspank.interpreter.Interpreter;
import com.janknspank.interpreter.RequiredFieldException;
import com.janknspank.interpreter.UrlFinder;
import com.janknspank.proto.ArticleProto.Article;
import com.janknspank.proto.CoreProto.InterpretedData;
import com.janknspank.proto.CoreProto.Url;

public class ArticleCrawler implements Runnable {
  // NOTE(jonemerson): This needs to be 1 if the database is empty.  Or, just
  // run ./rss.sh first!
  public static final int THREAD_COUNT = 20;

  @Override
  public void run() {
    // Uncomment this to start the crawl at a specific page.
    try {
      Urls.put("http://recode.net/", "http://jonemerson.net/", false);
    } catch (BiznessException | DatabaseSchemaException e) {
      e.printStackTrace();
    }

    while (true) {
      final Url url;
      try {
        url = Urls.markCrawlStart(Urls.getNextUrlToCrawl());
//      final Url url = Url.newBuilder()
//          .setId("pYZDE7M36zxQNxbFTUVFCQ")
//          .setUrl("http://techcrunch.com/2015/01/03/the-sharing-economy-and-the-"
//              + "future-of-finance/")
//          .setTweetCount(0)
//          .setDiscoveryTime(System.currentTimeMillis())
//          .build();
        if (url == null) {
          // Some other thread has likely claimed this URL - Go get another.
          continue;
        }
      } catch (BiznessException | DatabaseSchemaException e) {
        throw new RuntimeException("Could not read URL to crawl");
      }

      // Save this article and its keywords.
      try {
        if (!UrlWhitelist.isOkay(url.getUrl())) {
          System.err.println("Removing now-blacklisted page: " + url.getUrl());
          Links.deleteIds(ImmutableList.of(url.getId()));
          Database.delete(url);
          continue;
        }

        // Let's do this.
        crawl(url, false /* markCrawlStart */);

      } catch (DatabaseSchemaException | DatabaseRequestException | BiznessException e) {
        // Internal error (bug in our code).
        e.printStackTrace();
      } catch (FetchException|ParserException|RequiredFieldException e) {
        // Bad article.
        e.printStackTrace();
      }
    }
  }

  /**
   * Crawls a URL, putting the article (if found) in the database, and storing
   * any outbound links.
   */
  public static Article crawl(Url url, boolean markCrawlStart) throws FetchException,
       ParserException, RequiredFieldException, DatabaseSchemaException, DatabaseRequestException,
       BiznessException {
    System.err.println("Crawling: " + url.getUrl());

    if (markCrawlStart) {
      Urls.markCrawlStart(url);
    }

    List<String> urls;
    Article article = null;
    if (ArticleUrlDetector.isArticle(url.getUrl())) {
      InterpretedData interpretedData = Interpreter.interpret(url);
      article = interpretedData.getArticle();
      try {
        Database.insert(article);
      } catch (DatabaseRequestException | DatabaseSchemaException e) {
        // It could be that some other process decided to steal this article
        // and process it first (mainly due to human error).  If so, delete
        // everything and store it again.
        System.out.println("Handling human error: " + url.getUrl());
        Database.with(Article.class).delete(url.getId());
        Links.deleteFromOriginUrlId(ImmutableList.of(url.getId()));

        // Try again!
        Database.insert(article);
      }
      urls = interpretedData.getUrlList();
    } else {
      urls = UrlFinder.findUrls(url.getUrl());
    }

    // Make sure to filter and clean the URLs - only store the ones we want to crawl!
    Iterable<Url> destinationUrls = Urls.put(
        Iterables.transform(
            Iterables.filter(urls, UrlWhitelist.PREDICATE),
            UrlCleaner.TRANSFORM_FUNCTION),
        url.getUrl(),
        false /* isTweet */);
    Links.put(url, destinationUrls);
    Urls.markCrawlFinish(url);
    return article;
  }

  /**
   * Returns a map of URL -> Article for each given article.
   */
  private static Map<String, Article> createArticleMap(Iterable<Article> articles) {
    return Maps.uniqueIndex(
        articles,
        new Function<Article, String>() {
          @Override
          public String apply(Article article) {
            return article.getUrl();
          }
        });
  }

  /**
   * Gets a Map of all the existing Articles for the requested {@code
   * urlStrings}.  The Map maps each article's URL to its Article object.
   */
  private static Map<String, Article> getExistingArticles(Iterable<String> urlStrings)
      throws DatabaseSchemaException {
    return createArticleMap(
        Database.with(Article.class).get(new QueryOption.WhereEquals("url", urlStrings)));
  }

  /**
   * Inserts URL objects for each of the passed URL strings, then crawls said
   * URLs, putting the resulting Urls and Articles into the database.  The
   * retrieved Articles are returned, mapped to their original URLs.
   */
  private static Map<String, Article> getNewArticles(Iterable<String> urlStrings)
      throws DatabaseRequestException, DatabaseSchemaException, FetchException, ParserException,
          RequiredFieldException, BiznessException {
    Iterable<Url> urls = Urls.put(urlStrings, "http://jonemerson.net/benchmark", false /* isTweet */);
    List<Article> articles = Lists.newArrayList();
    for (Url url : urls) {
      Article article = crawl(url, true /* markCrawlStart */);
      if (article == null) {
        throw new IllegalStateException("URL is not an Article: " + url.getUrl());
      }
      articles.add(article);
    }
    return createArticleMap(articles);
  }

  /**
   * Gets articles for each of the requested URL strings, whether they've been
   * previously crawled or not.  (Ones that haven't been crawled will be
   * crawled.)
   */
  public static Map<String, Article> getArticles(Iterable<String> urlStrings)
      throws BiznessException {
    Map<String, Article> articles;
    try {
      articles = getExistingArticles(urlStrings);
      articles = ImmutableMap.<String, Article>builder()
          .putAll(articles)
          .putAll(getNewArticles(Sets.<String>symmetricDifference(
              new HashSet<String>(Lists.newArrayList(urlStrings)), articles.keySet())))
          .build();
    } catch (DatabaseSchemaException | DatabaseRequestException | FetchException
        | ParserException | RequiredFieldException e) {
      throw new BiznessException("Could not get articles: " + e.getMessage(), e);
    }
    Asserts.assertTrue(Iterables.size(urlStrings) == articles.size(),
        "Should have received Articles fo all requested URLs.", BiznessException.class);
    return articles;
  }

  public static void main(String args[]) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
    for (int i = 0; i < THREAD_COUNT; i++) {
      Runnable worker = new ArticleCrawler();
      executor.execute(worker);
    }
    executor.shutdown();
  }
}