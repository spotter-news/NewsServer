package com.janknspank.rank;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.apache.commons.lang3.ArrayUtils;
import org.neuroph.core.NeuralNetwork;
import org.neuroph.core.data.DataSet;
import org.neuroph.core.data.DataSetRow;
import org.neuroph.core.events.LearningEvent;
import org.neuroph.core.events.LearningEventListener;
import org.neuroph.core.learning.LearningRule;
import org.neuroph.nnet.MultiLayerPerceptron;
import org.neuroph.nnet.learning.BackPropagation;
import org.neuroph.nnet.learning.MomentumBackpropagation;
import org.neuroph.util.TransferFunctionType;

import com.google.api.client.util.Maps;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Lists;
import com.janknspank.bizness.BiznessException;
import com.janknspank.common.Averager;
import com.janknspank.database.Database;
import com.janknspank.database.DatabaseSchemaException;
import com.janknspank.proto.ArticleProto.Article;
import com.janknspank.proto.UserProto.User;

public class NeuralNetworkTrainer implements LearningEventListener {
  private static final int MAX_ITERATIONS = 20000;

  // Helper object that gives us human readable names for each input node.
  private static List<String> INPUT_NODE_KEYS = null;

  private double lowestError = 1.0;
  private Double[] lowestErrorNetworkWeights;
  private int lowestErrorIteration = 0;

  @SuppressWarnings("unused")
  private Double[] lastNetworkWeights;

  private NeuralNetwork<BackPropagation> generateTrainedNetwork(
      DataSet trainingSet, int hiddenNodeCount) throws DatabaseSchemaException {
    NeuralNetwork<BackPropagation> neuralNetwork;
    List<String> inputNodeKeys = getInputNodeKeys();
    int inputNodesCount = inputNodeKeys.size();
    if (hiddenNodeCount == 0) {
      neuralNetwork = new MultiLayerPerceptron(
          TransferFunctionType.SIGMOID, // TODO(jonemerson): Should this be LINEAR?
          inputNodesCount,
          1 /* output nodes count */);
    } else if (hiddenNodeCount == 0) {
      neuralNetwork = new MultiLayerPerceptron(
          TransferFunctionType.LINEAR, // Trying this out vs. SIGMOID...
          inputNodesCount,
          1 /* output nodes count */);
    } else {
      neuralNetwork = new MultiLayerPerceptron(
          TransferFunctionType.SIGMOID, // TODO(jonemerson): Should this be LINEAR?
          inputNodesCount,
          hiddenNodeCount,
          1 /* output nodes count */);
    }
    neuralNetwork.setLearningRule(new MomentumBackpropagation());

    // Set learning parameters.
    LearningRule learningRule = neuralNetwork.getLearningRule();
    learningRule.addListener(this);

    System.out.println("Training neural network...");
    neuralNetwork.learn(trainingSet);
    System.out.println("Trained - iteration " + lowestErrorIteration + " used w/ "
        + lowestError + " error rate");

    // NOTE(jonemerson): We used to do this, but I found that about 30% of the
    // time, the neural network would come out ranking crappy articles highly.
    // I'm experimenting for now to see if taking the longest iteration has a
    // higher propensity to generate a high-quality stream.
    // // Return the network with the lowest error.
    // System.out.println("Lowest error: " + lowestError + " at iteration " + lowestErrorIteration);
    // neuralNetwork.setWeights(ArrayUtils.toPrimitive(lowestErrorNetworkWeights));
    neuralNetwork.setWeights(ArrayUtils.toPrimitive(lowestErrorNetworkWeights));

    // Print correlation of each input node to the output.
    for (int i = 0; i < inputNodesCount; i++) {
      double[] isolatedInput = generateIsolatedInputNodes(i);
      neuralNetwork.setInput(isolatedInput);
      neuralNetwork.calculate();
      System.out.println("Input node '" + inputNodeKeys.get(i) + "' correlation to output: " 
          + neuralNetwork.getOutput()[0]);
    }

    return neuralNetwork;
  }

  /**
   * Generate an nodes array with all indexes = 0 except for the specified
   * index.
   * @throws DatabaseSchemaException 
   */
  static double[] generateIsolatedInputNodes(int enabledIndex) throws DatabaseSchemaException {
    double[] inputs = new double[getInputNodeKeys().size()];
    for (int i = 0; i < inputs.length; i++) {
      inputs[i] = (i == enabledIndex) ? 1.0 : 0.0;
    }
    return inputs;
  }

  /**
   * Creates a complete training data set given good URLs and bad URLs defined
   * in the user /personas/*.persona files.
   */
  private static DataSet generateTrainingDataSet() throws DatabaseSchemaException, BiznessException {
    int goodUrlCount = 0;
    int badUrlCount = 0;
    List<DataSetRow> dataSetRows = Lists.newArrayList();
    for (TrainingArticle trainingArticle : TrainingArticles.getTrainingArticles()) {
      dataSetRows.add(trainingArticle.getDataSetRow());
      if (trainingArticle.getScore() > 0.5) {
        goodUrlCount++;
      } else {
        badUrlCount++;
      }
    }
    System.out.println("Training set compiled. good=" + goodUrlCount + ", bad=" + badUrlCount);

    // UNBELIEVABLE!!  Neuroph comes up with far different results depending on
    // the order of the data set rows.  To combat this, and to allow us to try
    // different configurations to hopefully find an optimal, randomize the
    // rows.
    Collections.shuffle(dataSetRows);

    DataSet trainingSet = new DataSet(getInputNodeKeys().size(), 1 /* output nodes count */);
    for (DataSetRow dataSetRow : dataSetRows) {
      trainingSet.addRow(dataSetRow);
    }
    return trainingSet;
  }

  @Override
  public void handleLearningEvent(LearningEvent event) {
    BackPropagation bp = (BackPropagation) event.getSource();
    double error = bp.getTotalNetworkError();
    if (bp.getCurrentIteration() % 1000 == 1) {
      System.out.println(bp.getCurrentIteration() + ". iteration : "+ error);
    }
    lastNetworkWeights = bp.getNeuralNetwork().getWeights();

//    if (error < lowestError) {
      lowestError = error;
      lowestErrorIteration = bp.getCurrentIteration();
      lowestErrorNetworkWeights = bp.getNeuralNetwork().getWeights();
//    }
    if (bp.getCurrentIteration() >= MAX_ITERATIONS) {
      bp.stopLearning();
    }
  }

  /**
   * Definitely not-so-efficient hack for getting a list of input name labels,
   * e.g. "industries", "facebook", "acquisitions", etc.
   */
  private static synchronized List<String> getInputNodeKeys() throws DatabaseSchemaException {
    if (INPUT_NODE_KEYS == null) {
      INPUT_NODE_KEYS = ImmutableList.copyOf(NeuralNetworkScorer.generateInputNodes(
          Database.with(User.class).getFirst(),
          Database.with(Article.class).getFirst()).keySet());
    }
    return INPUT_NODE_KEYS;
  }

  private static void printAverageInputValues(DataSet dataSet) throws DatabaseSchemaException {
    // Calculate average input values, for debugging/optimization purposes.
    int inputNodesCount = getInputNodeKeys().size();
    Averager[] averageInputValues = new Averager[inputNodesCount];
    Averager[] averageInputValuesPositive = new Averager[inputNodesCount];
    Averager[] averageInputValuesNegative = new Averager[inputNodesCount];
    for (int i = 0; i < inputNodesCount; i++) {
      averageInputValues[i] = new Averager();
      averageInputValuesPositive[i] = new Averager();
      averageInputValuesNegative[i] = new Averager();
    }
    for (DataSetRow row : dataSet.getRows()) {
      double[] inputs = row.getInput();
      for (int i = 0; i < inputs.length; i++) {
        averageInputValues[i].add(inputs[i]);
        if (row.getDesiredOutput()[0] > 0.5) {
          averageInputValuesPositive[i].add(inputs[i]);
        }
        if (row.getDesiredOutput()[0] < 0.5) {
          averageInputValuesNegative[i].add(inputs[i]);
        }
      }
    }

    System.out.println("Average input values:");
    List<String> inputNodeKeys = getInputNodeKeys();
    for (int i = 0; i < averageInputValues.length; i++) {
      System.out.println("  [" + inputNodeKeys.get(i) + "] = " + averageInputValues[i].get()
          + " \t(goodUrls=" + averageInputValuesPositive[i].get() + ", \t"
          + "badUrls=" + averageInputValuesNegative[i].get() + ", \t"
          + "diff=" + Math.abs(averageInputValuesPositive[i].get() - averageInputValuesNegative[i].get()) + ")");
    }
  }

  public static class NeuralNetworkFinder implements Callable<NeuralNetwork<BackPropagation>> {
    private final int hiddenNodeCount;
    private final DataSet dataSet;
    private double grade = Double.MIN_VALUE;

    public NeuralNetworkFinder(DataSet dataSet, int hiddenNodeCount) {
      this.dataSet = dataSet;
      this.hiddenNodeCount = hiddenNodeCount;
    }

    @Override
    public NeuralNetwork<BackPropagation> call() throws Exception {
      NeuralNetworkTrainer neuralNetworkTrainer = new NeuralNetworkTrainer();
      NeuralNetwork<BackPropagation> neuralNetwork =
          neuralNetworkTrainer.generateTrainedNetwork(dataSet, hiddenNodeCount);
      System.out.println("** SCORE FOR " + hiddenNodeCount + " HIDDEN NODES...");
      grade = Benchmark.grade(new NeuralNetworkScorer(neuralNetwork));
      return neuralNetwork;
    }
  }

  /**
   * Helper method for triggering a train. 
   * run ./trainneuralnet.sh to execute
   * */
  public static void main(String args[]) throws Exception {
    DataSet dataSet = generateTrainingDataSet();
    printAverageInputValues(dataSet);

    // Create a threads to general neural networks for each of our
    // desired hidden node counts.
    ExecutorService executor = Executors.newFixedThreadPool(8);
    List<NeuralNetworkFinder> neuralNetworkFinderList = Lists.newArrayList();
    Map<NeuralNetworkFinder, Future<NeuralNetwork<BackPropagation>>> neuralNetworkFutureMap =
        Maps.newHashMap();
    for (int hiddenNodeCount : new int[] { 0, 2, 3, 4, 5, 6, 7, 8 }) {
      for (int tries = 0; tries < 15; tries++) {
        NeuralNetworkFinder neuralNetworkFinder = new NeuralNetworkFinder(dataSet, hiddenNodeCount);
        neuralNetworkFinderList.add(neuralNetworkFinder);
        neuralNetworkFutureMap.put(neuralNetworkFinder, executor.submit(neuralNetworkFinder));
      }
    }
    executor.shutdown();

    // Evaluate the outcomes from each thread.
    int bestHiddenNodeCount = Integer.MIN_VALUE;
    double bestNeuralNetworkGrade = Double.MIN_VALUE;
    Map<Integer, Double> bestGradePerTopology = Maps.newHashMap();
    NeuralNetwork<BackPropagation> bestNeuralNetwork = null;
    for (NeuralNetworkFinder neuralNetworkFinder : neuralNetworkFinderList) {
      int hiddenNodeCount = neuralNetworkFinder.hiddenNodeCount;
      NeuralNetwork<BackPropagation> neuralNetwork =
          neuralNetworkFutureMap.get(neuralNetworkFinder).get();
      double grade = neuralNetworkFinder.grade;
      if (grade > bestNeuralNetworkGrade) {
        bestNeuralNetworkGrade = grade;
        bestNeuralNetwork = neuralNetwork;
        bestHiddenNodeCount = hiddenNodeCount;
      }
      if (bestGradePerTopology.containsKey(hiddenNodeCount)) {
        bestGradePerTopology.put(hiddenNodeCount,
            Math.max(grade, bestGradePerTopology.get(hiddenNodeCount)));
      } else {
        bestGradePerTopology.put(hiddenNodeCount, grade);
      }
    }

    // Display the outcomes per hidden node count.
    System.out.println();
    System.out.println("Performances of different topologies:");
    for (int i = 0; i < 100; i++) {
      if (bestGradePerTopology.containsKey(i)) {
        System.out.println(i + " hidden nodes: " + bestGradePerTopology.get(i));
      }
    }

    // Save the best one.
    System.out.println("Saving best neural network "
        + "(btw it has " + bestHiddenNodeCount + " hidden nodes)");
    bestNeuralNetwork.save(NeuralNetworkScorer.DEFAULT_NEURAL_NETWORK_FILE);
  }
}
