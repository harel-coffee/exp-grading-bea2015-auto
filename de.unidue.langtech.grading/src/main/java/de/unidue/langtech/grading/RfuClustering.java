package de.unidue.langtech.grading;

import static java.util.Arrays.asList;
import static org.apache.uima.fit.factory.AnalysisEngineFactory.createEngineDescription;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.uima.analysis_engine.AnalysisEngineDescription;
import org.apache.uima.fit.component.NoOpAnnotator;
import org.apache.uima.resource.ResourceInitializationException;

import weka.attributeSelection.InfoGainAttributeEval;
import weka.attributeSelection.Ranker;
import weka.classifiers.functions.SMO;
import weka.clusterers.SimpleKMeans;
import de.tudarmstadt.ukp.dkpro.core.api.resources.DkproContext;
import de.tudarmstadt.ukp.dkpro.core.clearnlp.ClearNlpDependencyParser;
import de.tudarmstadt.ukp.dkpro.core.clearnlp.ClearNlpLemmatizer;
import de.tudarmstadt.ukp.dkpro.core.clearnlp.ClearNlpPosTagger;
import de.tudarmstadt.ukp.dkpro.core.clearnlp.ClearNlpSegmenter;
import de.tudarmstadt.ukp.dkpro.core.jazzy.SpellChecker;
import de.tudarmstadt.ukp.dkpro.lab.Lab;
import de.tudarmstadt.ukp.dkpro.lab.task.Dimension;
import de.tudarmstadt.ukp.dkpro.lab.task.ParameterSpace;
import de.tudarmstadt.ukp.dkpro.lab.task.impl.BatchTask.ExecutionPolicy;
import de.tudarmstadt.ukp.dkpro.tc.core.Constants;
import de.tudarmstadt.ukp.dkpro.tc.features.length.NrOfTokensDFE;
import de.tudarmstadt.ukp.dkpro.tc.features.ngram.DependencyDFE;
import de.tudarmstadt.ukp.dkpro.tc.features.ngram.LuceneCharacterNGramDFE;
import de.tudarmstadt.ukp.dkpro.tc.features.ngram.LuceneNGramDFE;
import de.tudarmstadt.ukp.dkpro.tc.features.ngram.LuceneSkipNGramDFE;
import de.tudarmstadt.ukp.dkpro.tc.weka.report.BatchOutcomeIDReport;
import de.tudarmstadt.ukp.dkpro.tc.weka.report.BatchRuntimeReport;
import de.tudarmstadt.ukp.dkpro.tc.weka.report.BatchTrainTestReport;
import de.tudarmstadt.ukp.dkpro.tc.weka.writer.WekaDataWriter;
import de.unidue.langtech.grading.io.RfuReader;
import de.unidue.langtech.grading.report.KappaReport;
import de.unidue.langtech.grading.tc.BatchTaskClusterClassification;
import de.unidue.langtech.grading.tc.BatchTaskClustering;

public class RfuClustering
    implements Constants
{
	
    public static final String LANGUAGE_CODE = "en";

    public static final Boolean[] toLowerCase = new Boolean[] { true };
    
    public static final Boolean[] onlyPure = new Boolean[] { false, true};
          
    public static final String stopwordList = "classpath:/stopwords/english_stopwords.txt";
    
    public static final String SPELLING_VOCABULARY = "classpath:/vocabulary/en_US_dict.txt";

    public static final int NUM_FOLDS = 5;

    public static final boolean useTagger = false;
    public static final boolean useParsing = false;
    public static final boolean useSpellChecking = false;

    public static void main(String[] args)
        throws Exception
    {
        File baseDir = new File(new DkproContext().getWorkspace("ETS").getAbsolutePath() + "/RFU");

        for (String question : RfuReader.rfuQuestions) {
        	System.out.println("Q: " + question);
	        ParameterSpace pSpace = getParameterSpace(baseDir.getAbsolutePath(), question);

	        RfuClustering experiment = new RfuClustering();
//	        experiment.runClustering(pSpace);
	        experiment.runClusterClassification(pSpace);

        }
    }
    
    @SuppressWarnings("unchecked")
    public static ParameterSpace getParameterSpace(String basedir, String question) 
    		throws IOException
    {
        // configure training and test data reader dimension
        // train/test will use both, while cross-validation will only use the train part
        Map<String, Object> dimReaders = new HashMap<String, Object>();
        dimReaders.put(DIM_READER_TRAIN, RfuReader.class);
        dimReaders.put(
                DIM_READER_TRAIN_PARAMS,
                Arrays.asList(
                		RfuReader.PARAM_INPUT_FILE, basedir + "/" + question + "_train.txt"
        ));
        dimReaders.put(DIM_READER_TEST, RfuReader.class);
        dimReaders.put(
                DIM_READER_TEST_PARAMS,
                Arrays.asList(
                		RfuReader.PARAM_INPUT_FILE, basedir + "/" + question + "_test.txt"
        ));

        Dimension<List<String>> dimClusteringArgs = Dimension.create("clusteringArguments",
                Arrays.asList(new String[] { SimpleKMeans.class.getName(), "-N", "50", })
        );  
    
        Dimension<List<String>> dimClassificationArgs = Dimension.create(DIM_CLASSIFICATION_ARGS,
                Arrays.asList(new String[] { SMO.class.getName() })
        );

        Dimension<List<Object>> dimPipelineParameters = Dimension.create(
                DIM_PIPELINE_PARAMS,
//                Arrays.asList(new Object[] {
//                		LuceneNGramDFE.PARAM_NGRAM_USE_TOP_K, 500,
//                        LuceneNGramDFE.PARAM_NGRAM_STOPWORDS_FILE, stopwordList
//                }),
                Arrays.asList(new Object[] {
                		LuceneNGramDFE.PARAM_NGRAM_USE_TOP_K, 5000,
                        LuceneNGramDFE.PARAM_NGRAM_STOPWORDS_FILE, stopwordList,
                        LuceneSkipNGramDFE.PARAM_NGRAM_USE_TOP_K, 5000
                })
        );

        Dimension<List<String>> dimFeatureSets = Dimension.create(
                DIM_FEATURE_SET,
                Arrays.asList(new String[] {
                    NrOfTokensDFE.class.getName(),
                    LuceneNGramDFE.class.getName(),
                    LuceneSkipNGramDFE.class.getName(),
                    LuceneCharacterNGramDFE.class.getName(),
                    DependencyDFE.class.getName()
                })
        );
        
        // single-label feature selection (Weka specific options), reduces the feature set to k features
        Map<String, Object> dimFeatureSelection = new HashMap<String, Object>();
        dimFeatureSelection.put(DIM_FEATURE_SEARCHER_ARGS,
                asList(new String[] { Ranker.class.getName(), "-N", "5000" }));
        dimFeatureSelection.put(DIM_ATTRIBUTE_EVALUATOR_ARGS,
                asList(new String[] { InfoGainAttributeEval.class.getName() }));
        dimFeatureSelection.put(DIM_APPLY_FEATURE_SELECTION, true);

        ParameterSpace pSpace = new ParameterSpace(Dimension.createBundle("readers", dimReaders),
                Dimension.create(DIM_DATA_WRITER, WekaDataWriter.class.getName()),
                Dimension.create(DIM_LEARNING_MODE, LM_SINGLE_LABEL),
                Dimension.create(DIM_FEATURE_MODE, FM_DOCUMENT),
                dimPipelineParameters,
                dimFeatureSets,
                dimClassificationArgs,
                dimClusteringArgs,
                Dimension.create("onlyPureClusters", onlyPure)
//                Dimension.createBundle("featureSelection", dimFeatureSelection)
        );

        return pSpace;
    }

    // ##### CLUSTERING #####
    protected void runClustering(ParameterSpace pSpace)
        throws Exception
    {
        BatchTaskClustering batch = new BatchTaskClustering("RFU-Clustering",
                getPreprocessing());    
        batch.setParameterSpace(pSpace);
        batch.setExecutionPolicy(ExecutionPolicy.RUN_AGAIN);
        batch.addReport(BatchTrainTestReport.class);
        batch.addReport(BatchOutcomeIDReport.class);
        batch.addReport(BatchRuntimeReport.class);

        // Run
        Lab.getInstance().run(batch);
    }
    
    // ##### CLUSTERING + CLASSIFICATION #####
    protected void runClusterClassification(ParameterSpace pSpace)
        throws Exception
    {
        BatchTaskClusterClassification batch = new BatchTaskClusterClassification("Powergrading-ClusterClassification",
                getPreprocessing());    
        batch.setParameterSpace(pSpace);
        batch.setExecutionPolicy(ExecutionPolicy.RUN_AGAIN);
        batch.addInnerReport(KappaReport.class);
        batch.addReport(BatchTrainTestReport.class);
        batch.addReport(BatchOutcomeIDReport.class);
        batch.addReport(BatchRuntimeReport.class);

        // Run
        Lab.getInstance().run(batch);
    }
    
    public static AnalysisEngineDescription getPreprocessing()
        throws ResourceInitializationException
    {
        AnalysisEngineDescription tagger       = createEngineDescription(NoOpAnnotator.class);
        AnalysisEngineDescription lemmatizer   = createEngineDescription(NoOpAnnotator.class);
        AnalysisEngineDescription chunker      = createEngineDescription(NoOpAnnotator.class);
        AnalysisEngineDescription spellChecker = createEngineDescription(NoOpAnnotator.class);
        AnalysisEngineDescription parser       = createEngineDescription(NoOpAnnotator.class);
        
        if (useTagger) {
        	System.out.println("Running tagger ...");
//            tagger = createEngineDescription(
//                    TreeTaggerPosLemmaTT4J.class,
//                    TreeTaggerPosLemmaTT4J.PARAM_LANGUAGE, LANGUAGE_CODE
//            );
            tagger = createEngineDescription(
                    ClearNlpPosTagger.class,
                    ClearNlpPosTagger.PARAM_LANGUAGE, LANGUAGE_CODE
            );
            lemmatizer = createEngineDescription(
                    ClearNlpLemmatizer.class
            );
        }

        if (useSpellChecking) {
            spellChecker = createEngineDescription(
            		SpellChecker.class,
            		SpellChecker.PARAM_MODEL_LOCATION, SPELLING_VOCABULARY
            );
        }
        
        if (useParsing) {
        	System.out.println("Running parser ...");
//            parser = createEngineDescription(
//                    StanfordParser.class,
//                    StanfordParser.PARAM_VARIANT, "pcfg"
//            );
            parser = createEngineDescription(
                    ClearNlpDependencyParser.class,
                    ClearNlpDependencyParser.PARAM_VARIANT, "ontonotes"
            );
        }
        
        return createEngineDescription(
                createEngineDescription(
                        ClearNlpSegmenter.class
                ),
                spellChecker,
                tagger,
                lemmatizer,
                chunker,
                parser
        );
    }
}