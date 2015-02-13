package com.janknspank.interpreter;

import java.io.Reader;

import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.IOUtils;

import com.janknspank.dom.parser.DocumentBuilder;
import com.janknspank.dom.parser.DocumentNode;
import com.janknspank.dom.parser.ParserException;
import com.janknspank.fetch.FetchException;
import com.janknspank.fetch.FetchResponse;
import com.janknspank.fetch.Fetcher;
import com.janknspank.proto.CoreProto.InterpretedData;
import com.janknspank.proto.CoreProto.Url;

/**
 * Built off the power of SiteParser, this class further interprets a web page
 * by taking the paragraphs and breaking them down into sentences, tokens, and
 * then into people, organizations, and locations.
 */
public class Interpreter {
  private static final Fetcher FETCHER = new Fetcher();

  /**
   * Retrieves the passed URL by making a request to the respective website,
   * and then interprets the returned results.
   */
  public static InterpretedData interpret(Url url)
      throws FetchException, ParserException, RequiredFieldException {

    FetchResponse response = null;
    Reader reader = null;
    try {
      response = FETCHER.fetch(url);
      if (response.getStatusCode() != HttpServletResponse.SC_OK) {
        throw new FetchException(
            "URL not found (" + response.getStatusCode() + "): " + url.getUrl());
      }
      reader = response.getReader();
      return interpret(url, reader);
    } finally {
      IOUtils.closeQuietly(reader);
    }
  }

  /**
   * Advanced method: If we already have the data from the URL, use this method
   * to interpret the web page using said data.
   */
  public static InterpretedData interpret(Url url, Reader reader)
      throws FetchException, ParserException, RequiredFieldException {

    DocumentNode documentNode = DocumentBuilder.build(url.getUrl(), reader);
    String urlId = url.getId();
    return InterpretedData.newBuilder()
        .setArticle(ArticleCreator.create(urlId, documentNode))
        .addAllUrl(UrlFinder.findUrls(documentNode))
        .build();
  }
}
