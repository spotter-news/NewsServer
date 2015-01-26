package com.janknspank.data;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.janknspank.classifier.DocumentVector;
import com.janknspank.proto.Core.Article;
import com.janknspank.proto.Core.WordDocumentFrequency;

/**
 * Computes and returns the # of documents in the corpus
 * that contain a given term. This is the DF part of TF-IDF
 * @author tomch
 *
 */
public class WordDocumentFrequencies {
  private Map<String, Integer> documentFrequency;
  private static WordDocumentFrequencies instance = null;
  private Integer totalDocumentCount;
  private static final String PROPERTY_KEY_N = "numArticlesUsedForModel";
  
  private WordDocumentFrequencies() throws DataInternalException, IOException {
    System.out.println("WordDocumentFrequencies constructor");
    documentFrequency = loadPreviouslyComputedDocumentFrequencies();
    if (documentFrequency == null) {
      documentFrequency = generateDocumentFrequenciesFromCorpus();
      saveDocumentFrequenciesToServer(documentFrequency);
    }
  }
  
  public static synchronized WordDocumentFrequencies getInstance() 
      throws DataInternalException, IOException {
    if(instance == null) {
       instance = new WordDocumentFrequencies();
    }
    return instance;
  }
  
  
  // Expensive - only do infrequently as the corpus grows
  private static Map<String, Integer> generateDocumentFrequenciesFromCorpus() 
      throws DataInternalException, IOException {
    System.out.println("Warning: calling expensive function: generateWordFrequenciesFromCorpus");
    System.out.println("Use loadPreviouslyComputedDocumentFrequencies instead");
    
    Map<String, Integer> wordDocumentFrequency = new HashMap<>();
    int limit = 1000;
    int offset = 0;
    int N = 0;
    List<Article> articles;
    Set<String> words;
    Integer frequency;
    do {
      articles = Articles.getPageOfArticles(limit, offset);
      System.out.println("Generating Word Document Frequency - article offest: " + offset);
      for (Article article : articles) {
        words = new DocumentVector(article).getWordsInDocument();
        for (String word : words) {
          frequency = wordDocumentFrequency.get(word);
          if (frequency == null) {
            wordDocumentFrequency.put(word, new Integer(1));
          }
          else {
            wordDocumentFrequency.put(word, new Integer(frequency.intValue() + 1));
          }
        }
      }
      offset += limit;
      N += articles.size();
    } while (articles.size() == limit);
    saveNLocally(N);
    return wordDocumentFrequency;
  }
  
  private static void saveDocumentFrequenciesToServer(Map<String, Integer> frequencies) {
    String word;
    Integer value;
    WordDocumentFrequency wdf;
    for (Map.Entry<String, Integer> frequency : frequencies.entrySet()) {
      word = frequency.getKey();
      value = frequency.getValue();
      
      wdf = WordDocumentFrequency.newBuilder()
          .setWord(word)
          .setFrequency(value.intValue())
          .build();
      try {
        Database.insert(wdf);
      } catch (ValidationException | DataInternalException e) {
        System.out.println("Error saving document frequency to server for word: " + word);
        System.out.println("Skipping it.");
        //throw new DataInternalException("Error creating document frequency ", e);
      }
    }
  }
  
  private static void saveNLocally(int numDocs) throws IOException {
    Properties properties = getVectorSpaceProperties();
    properties.setProperty(PROPERTY_KEY_N, String.valueOf(numDocs));
  }
  
  private Map<String, Integer> loadPreviouslyComputedDocumentFrequencies() 
      throws DataInternalException {
    System.out.println("loadPreviouslyComputedDocumentFrequencies");
    Map<String, Integer> frequencyMap = new HashMap<>();
    List<WordDocumentFrequency> wdfs = Database.with(WordDocumentFrequency.class).get();
    for (WordDocumentFrequency wdf : wdfs) {
      frequencyMap.put(wdf.getWord(), (int) wdf.getFrequency());
    }
    
    return frequencyMap;
  }
  
  public int getFrequency(String word) {
    return documentFrequency.get(word);
  }
  
  public int getN() throws IOException {
    if (totalDocumentCount == null) {
      Properties properties = getVectorSpaceProperties();
      totalDocumentCount = Integer.parseInt(
          properties.getProperty(PROPERTY_KEY_N));
    }
    return totalDocumentCount;
  }
  
  private static Properties getVectorSpaceProperties() throws IOException {
    Properties properties = new Properties();
    String propFileName = "classifier/vectorspace.properties";
     
    InputStream inputStream = new FileInputStream(propFileName);
     
    if (inputStream != null) {
      properties.load(inputStream);
    } else {
      throw new FileNotFoundException("property file '" + propFileName + "' not found in the classpath");
    }
    
    return properties;
  }
  
  /** Helper method for creating the Article table. */
  public static void main(String args[]) throws Exception {
    try {
      Database.with(WordDocumentFrequency.class).createTable();      
    } catch (DataInternalException e) {
      System.out.println("WordDocumentFrequency table already exists. No need to create it.");
    }
    Map<String, Integer> df = generateDocumentFrequenciesFromCorpus();
    saveDocumentFrequenciesToServer(df);
  }
}