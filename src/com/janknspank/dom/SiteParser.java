package com.janknspank.dom;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.http.client.config.RequestConfig;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;

import com.google.common.collect.Maps;

public class SiteParser {
  private static final Map<String, String[]> DOMAIN_TO_DOM_ADDRESSES =
      Maps.newHashMap();
  static {
    DOMAIN_TO_DOM_ADDRESSES.put("curbed.com", new String[] {
        ".post-body > p",
        ".post-body > .post-more > p"});
    DOMAIN_TO_DOM_ADDRESSES.put("default", new String[] {
        "article > p",
        "article > div > p"});
    DOMAIN_TO_DOM_ADDRESSES.put("nytimes.com", new String[] {
        "article > p",
        "article > div > p"});
    DOMAIN_TO_DOM_ADDRESSES.put("techcrunch.com", new String[] {
        ".article-entry > p",
        ".article-entry > h2"});
  }

  public static DocumentNode crawl(String url) throws IOException {
    HttpGet httpget = new HttpGet(url);

    RequestConfig config = RequestConfig.custom()
//        .setCookieSpec(CookieSpecs.IGNORE_COOKIES) // Don't pick up cookies.
        .build();
    CloseableHttpClient httpclient = HttpClients.custom()
        .setDefaultRequestConfig(config)
        .build();

    CloseableHttpResponse response = httpclient.execute(httpget);
    if (response.getStatusLine().getStatusCode() == 200) {
      return new HtmlHandler(response.getEntity().getContent()).getDocumentNode();
    }
    throw new IOException("Bad response, status code = " +
        response.getStatusLine().getStatusCode());
  }

  /**
   * Returns the best set of DOM addresses we currently know about for the given
   * domain (or subdomain, if one is so configured).  Looks in
   * DOMAIN_TO_DOM_ADDRESSES hierarchically through each subdomain until a match
   * is found, or a default set is returned instead.
   */
  private String[] getDomAddressesForUrl(String url) {
    String domain;
    try {
      domain = new URL(url).getHost();
    } catch (MalformedURLException e) {
      throw new RuntimeException(e);
    }

    while (domain.contains(".")) {
      String[] domAddresses = DOMAIN_TO_DOM_ADDRESSES.get(domain);
      if (domAddresses != null) {
        return domAddresses;
      }
      domain = domain.substring(domain.indexOf(".") + 1);
    }
    return DOMAIN_TO_DOM_ADDRESSES.get("default");
  }

  /**
   * Returns Nodes for all the paragraph / header / quote / etc content within
   * an article's web page.
   */
  public List<Node> getParagraphNodes(String url) throws IOException {
    DocumentNode documentNode = crawl(url);
    List<Node> paragraphs = new ArrayList<>();
    for (String domAddress : getDomAddressesForUrl(url)) {
      paragraphs.addAll(documentNode.findAll(domAddress));
    }
    Collections.sort(paragraphs, new NodeOffsetComparator());
    return paragraphs;
  }
}